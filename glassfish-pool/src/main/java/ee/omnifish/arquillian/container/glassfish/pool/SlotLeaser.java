/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;

/**
 * Pure-Java port of the file-lock-based slot lease protocol from the Faces
 * TCK's {@code SlotLeaserAgent}. Used by the Arquillian extension at test-JVM
 * startup to acquire one of the pre-started slots, and by tools/tests for
 * the same purpose.
 *
 * <p>Protocol per slot:
 * <ul>
 *   <li>{@code slot-N/lock} — one byte per JVM, exclusively locked through
 *       {@link PoolLocks}, which keeps one channel per lock file in a JVM.
 *       Linux/macOS use {@code fcntl} locks under the hood; on Windows the
 *       locking is mandatory.</li>
 *   <li>{@code slot-N/ports.properties} — appears once the slot's setup
 *       completes, and is removed while it restarts. A slot dir without it is
 *       skipped by the scan.</li>
 *   <li>{@code slot-N/run.lock} — also held while a {@code glassfish-pool:run}
 *       has the slot. The pool's automatic stop in other JVMs leaves such a
 *       slot running.</li>
 * </ul>
 *
 * <p>If every existing slot is busy the leaser tries to grow the pool: it
 * acquires {@code grow.lock}, picks the next index (preferring to recycle a
 * dead or abandoned slot) AND acquires its {@code lock} file, releases
 * {@code grow.lock}, and runs the provisioner with the slot lock held
 * throughout. The slot lock is the claim, so concurrent test JVMs that race
 * into {@code tryGrow} cannot pick the same dead slot for recycling — without
 * it, two threads would each see the lock free, both call
 * {@link PoolProvisioner#provisionClaimedSlot} on the same index, and race on
 * the {@code deleteRecursive}+clone of that slot's GF install tree. If
 * provisioning fails, the claim is released and the slot left without
 * {@code ports.properties}, so the next grow recycles it.
 */
public final class SlotLeaser {

    private static final Logger LOG = Logger.getLogger(SlotLeaser.class.getName());

    private static final long DEFAULT_TIMEOUT_SECONDS = 600L;
    private static final long POLL_INTERVAL_MILLIS = 500L;

    private final PoolConfig config;
    private final long timeoutSeconds;

    public SlotLeaser(PoolConfig config) {
        this(config, DEFAULT_TIMEOUT_SECONDS);
    }

    public SlotLeaser(PoolConfig config, long timeoutSeconds) {
        this.config = config;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Lease a slot, growing the pool if necessary. Blocks up to the configured
     * timeout. Throws if no slot can be obtained in time.
     *
     * @throws CommandFailedException if a command failed on a slot this
     *         leaser created, with {@code failOnCommandError} on
     */
    public SlotLease lease() throws IOException, InterruptedException {
        return lease(false);
    }

    /**
     * Lease a slot for a {@code glassfish-pool:run}: also hold the slot's
     * {@code run.lock}, from the moment the slot is claimed, so before a slot
     * the pool grows for it is set up. The pool's automatic stop in another
     * JVM leaves such a slot running. A slot that fails to grow fails the
     * lease, instead of being tried again until the timeout.
     */
    public SlotLease leaseForRun() throws IOException, InterruptedException {
        return lease(true);
    }

    private SlotLease lease(boolean forRun) throws IOException, InterruptedException {
        Path poolDir = config.poolDir();
        if (!Files.isDirectory(poolDir)) {
            throw new IllegalStateException(
                    "Pool dir does not exist: " + poolDir
                    + " — has the pool been provisioned (e.g. via PoolBootstrap.up)?");
        }
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            SlotLease leased = scanAndTryAcquire(poolDir, forRun);
            if (leased != null) {
                return leased;
            }
            SlotLease grown = tryGrow(poolDir, forRun);
            if (grown != null) {
                return grown;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException(
                "Could not lease a GlassFish pool slot within " + timeoutSeconds + "s (pool=" + poolDir + ")");
    }

    /**
     * One scan pass: enumerate ready slots (those with {@code ports.properties})
     * and try to lock each in least-recently-leased order. Returns the first
     * successful lease, or {@code null} if every ready slot is busy.
     *
     * <p>Sorting by lock-file mtime biases toward the slot that's been idle
     * the longest, which evens out load when tests have wildly different
     * durations.
     */
    private SlotLease scanAndTryAcquire(Path poolDir, boolean forRun) throws IOException {
        List<Integer> candidates = new ArrayList<>();
        try (var entries = Files.list(poolDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Integer idx = PoolPaths.slotIndex(entry.getFileName().toString());
                if (idx == null) {
                    continue;
                }
                if (!Files.exists(PoolPaths.portsFile(poolDir, idx))) {
                    continue;
                }
                candidates.add(idx);
            }
        }
        candidates.sort(Comparator.comparingLong(slot -> lockMtime(poolDir, slot)));
        for (int slot : candidates) {
            SlotLease leased = tryAcquire(poolDir, slot, forRun);
            if (leased != null) {
                return leased;
            }
        }
        return null;
    }

    private SlotLease tryAcquire(Path poolDir, int slot, boolean forRun) throws IOException {
        Path portsFile = PoolPaths.portsFile(poolDir, slot);
        if (!Files.exists(portsFile)) {
            return null;
        }
        // Also null for a slot this JVM already holds (e.g. a lease this JVM
        // hasn't released yet). PoolLocks answers that without opening a second
        // channel, whose close would release the held lease's lock.
        PoolLocks.Held lock = PoolLocks.tryLock(PoolPaths.lockFile(poolDir, slot));
        if (lock == null) {
            return null;
        }
        try {
            SlotPorts ports;
            try {
                ports = SlotPorts.readFrom(portsFile);
            } catch (IOException | RuntimeException e) {
                // Half-written ports.properties — skip the slot, scan continues.
                lock.close();
                return null;
            }
            // A free lock file does not imply a live GF: previous owner's GF may
            // have OOM-crashed, been killed, or been mid-shutdown when we grabbed
            // the lock. Probe before committing.
            if (!PoolProvisioner.isPortHealthy(ports.adminPort())) {
                lock.close();
                return null;
            }
            PoolLocks.Held runLock = null;
            if (forRun) {
                runLock = markRun(poolDir, slot);
                if (runLock == null) {
                    lock.close();
                    return null;
                }
            }
            publishSlotProperty(slot);
            LOG.fine(() -> "Leased slot " + slot + " (adminPort=" + ports.adminPort() + ")");
            return new SlotLease(slot, ports, lock, runLock);
        } catch (RuntimeException e) {
            // Always release the lock between tryLock() and successful return,
            // otherwise a transient failure leaks it.
            lock.close();
            throw e;
        }
    }

    /**
     * Pick the next slot index AND acquire its lockfile under {@code grow.lock},
     * then provision it outside the lock with the slot lock still held. Returns
     * a ready-to-use {@link SlotLease} on success, or {@code null} if no
     * provisioning happened (no source configured, no candidate index claimable,
     * or provisioning failed).
     *
     * <p>The slot lock — not the slot dir's existence — is the canonical claim:
     * the recycle path reuses an existing dir, so directory existence alone
     * cannot tell a second concurrent {@code tryGrow} caller to skip it.
     *
     * <p>A slot whose provisioning failed keeps its directory, without
     * {@code ports.properties}, and its claim is released. That makes it
     * recyclable, so the next grow re-creates it, once a server still running
     * from it has stopped. Deleting the directory instead could pull the
     * install out from under such a server, and the slot out of {@code down}'s
     * reach.
     */
    private SlotLease tryGrow(Path poolDir, boolean forRun) throws IOException {
        if (config.source() == null) {
            // Caller didn't supply a source install — typical for test-JVM
            // leasers when the build hasn't forwarded gf.pool.source. Skip
            // grow so the lease loop just waits for an existing slot.
            return null;
        }
        ClaimedSlot claim = PoolLocks.withGrowLock(poolDir, () -> claimSlot(poolDir));
        if (claim == null) {
            return null;
        }
        PoolLocks.Held runLock = null;
        if (forRun) {
            // Before the setup, which takes a while: until the run's mark is
            // there, another JVM's automatic stop would stop the slot.
            runLock = markRun(poolDir, claim.slot);
            if (runLock == null) {
                claim.release();
                return null;
            }
        }
        try {
            SlotPorts ports = new PoolProvisioner(config).provisionClaimedSlot(claim.slot);
            publishSlotProperty(claim.slot);
            return new SlotLease(claim.slot, ports, claim.lock, runLock);
        } catch (CommandFailedException e) {
            // failOnCommandError: the command would fail on every new slot,
            // so fail the lease rather than retry until the lease timeout.
            release(runLock, claim);
            throw e;
        } catch (IOException | RuntimeException e) {
            release(runLock, claim);
            if (forRun) {
                // A run waits for nobody: it would grow the same slot again.
                throw e;
            }
            LOG.warning(() -> "Slot " + claim.slot + " provisioning failed during grow: " + e.getMessage());
            return null;
        }
    }

    /**
     * Mark slot {@code slot}, whose lock the caller holds, as a run's.
     * {@code null} when it can't be: a run that ended keeps it only while it
     * releases the slot.
     */
    private static PoolLocks.Held markRun(Path poolDir, int slot) {
        try {
            return PoolLocks.tryLock(PoolPaths.runLockFile(poolDir, slot));
        } catch (IOException e) {
            LOG.fine(() -> "Slot " + slot + " can't be marked as a run's: " + e.getMessage());
            return null;
        }
    }

    private static void release(PoolLocks.Held runLock, ClaimedSlot claim) {
        if (runLock != null) {
            runLock.close();
        }
        claim.release();
    }

    /**
     * Pick a slot index and atomically hold its lockfile. Must be called with
     * {@code grow.lock} held. Returns {@code null} if no slot is claimable.
     *
     * <p>Recycling is preferred to keep the pool from outgrowing the
     * concurrency cap implied by {@code -T} after a slot dies. For a fresh
     * index, the slot dir is also created here (still under {@code grow.lock})
     * so a follow-up caller's index-scan skips it.
     *
     * <p>Package-private for the regression test that pins the lock-retention
     * invariant: a second claim must NOT re-pick a slot already claimed.
     */
    ClaimedSlot claimSlot(Path poolDir) throws IOException {
        ClaimedSlot recycled = claimRecyclableSlot(poolDir);
        if (recycled != null) {
            return recycled;
        }
        int chosen = 1;
        while (Files.exists(PoolPaths.slotDir(poolDir, chosen))) {
            chosen++;
        }
        Files.createDirectory(PoolPaths.slotDir(poolDir, chosen));
        PoolLocks.Held lock = PoolLocks.tryLock(PoolPaths.lockFile(poolDir, chosen));
        if (lock == null) {
            // Fresh slot dir with a locked lockfile — should not happen since
            // we just created the dir under grow.lock. Bail safely.
            return null;
        }
        return new ClaimedSlot(chosen, lock);
    }

    /**
     * Lowest slot index that is recyclable (see {@link #isRecyclable}) AND
     * whose lock file we can acquire. The acquired lock is RETAINED in the
     * returned {@link ClaimedSlot} — callers must release it (typically by
     * passing it into the resulting {@link SlotLease}) so the claim survives
     * the {@code grow.lock} release that follows.
     */
    private ClaimedSlot claimRecyclableSlot(Path poolDir) throws IOException {
        List<Integer> indices = new ArrayList<>();
        try (var entries = Files.list(poolDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Integer idx = PoolPaths.slotIndex(entry.getFileName().toString());
                if (idx != null) {
                    indices.add(idx);
                }
            }
        }
        indices.sort(Comparator.naturalOrder());
        for (int slot : indices) {
            if (!isRecyclable(poolDir, slot)) {
                continue;
            }
            // Also null when this JVM already holds the slot, so a single-JVM
            // caller (tests, the same leaser used twice) can't double-claim.
            PoolLocks.Held lock;
            try {
                lock = PoolLocks.tryLock(PoolPaths.lockFile(poolDir, slot));
            } catch (IOException e) {
                continue;
            }
            if (lock == null) {
                continue;
            }
            // Checked again under the lock: whoever held it may have finished
            // the slot's setup between the first check and tryLock.
            if (isRecyclable(poolDir, slot)) {
                return new ClaimedSlot(slot, lock);
            }
            lock.close();
        }
        return null;
    }

    /**
     * A slot whose GlassFish is dead (admin port closed), or that has no
     * {@code ports.properties}. Whoever sets a slot up or restarts it holds
     * its lock until {@code ports.properties} is written, so once the caller
     * holds the lock, a slot without one was abandoned, for example by a JVM
     * killed during its setup.
     */
    private static boolean isRecyclable(Path poolDir, int slot) {
        Path portsFile = PoolPaths.portsFile(poolDir, slot);
        if (!Files.exists(portsFile)) {
            return true;
        }

        try {
            return !PoolProvisioner.isPortHealthy(SlotPorts.readFrom(portsFile).adminPort());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Expose the leased slot index as a system property so test-side observers
     * (logging filters, progress listeners, etc.) can tag their output with the
     * slot number. Set even on a single-slot pool so consumers can rely on the
     * property existing.
     */
    private static void publishSlotProperty(int slot) {
        System.setProperty("gf.pool.slot", String.valueOf(slot));
    }

    private static long lockMtime(Path poolDir, int slot) {
        try {
            return Files.getLastModifiedTime(PoolPaths.lockFile(poolDir, slot)).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** A slot index plus the held lock backing the claim. */
    static final class ClaimedSlot {
        final int slot;
        final PoolLocks.Held lock;

        ClaimedSlot(int slot, PoolLocks.Held lock) {
            this.slot = slot;
            this.lock = lock;
        }

        void release() {
            lock.close();
        }
    }
}
