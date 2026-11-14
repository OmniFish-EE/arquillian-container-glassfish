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
 *   <li>{@code slot-N/ports.properties} — appears once provisioning completes.
 *       A slot dir without it is mid-bootstrap and skipped by the scan.</li>
 * </ul>
 *
 * <p>If every existing slot is busy the leaser tries to grow the pool: it
 * acquires {@code grow.lock}, picks the next index (preferring to recycle a
 * dead slot) AND acquires its {@code lock} file, releases {@code grow.lock},
 * and runs the provisioner with the slot lock held throughout. The slot lock
 * is the claim, so concurrent test JVMs that race into {@code tryGrow} cannot
 * pick the same dead slot for recycling — without it, two threads would each
 * see the lock free, both call {@link PoolProvisioner#provisionClaimedSlot} on
 * the same index, and race on the {@code deleteRecursive}+clone of that slot's
 * GF install tree. If provisioning fails, the slot directory is removed, so
 * the next grow can use the index again.
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
     */
    public SlotLease lease() throws IOException, InterruptedException {
        Path poolDir = config.poolDir();
        if (!Files.isDirectory(poolDir)) {
            throw new IllegalStateException(
                    "Pool dir does not exist: " + poolDir
                    + " — has the pool been provisioned (e.g. via PoolBootstrap.up)?");
        }
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            SlotLease leased = scanAndTryAcquire(poolDir);
            if (leased != null) {
                return leased;
            }
            SlotLease grown = tryGrow(poolDir);
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
    private SlotLease scanAndTryAcquire(Path poolDir) throws IOException {
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
            SlotLease leased = tryAcquire(poolDir, slot);
            if (leased != null) {
                return leased;
            }
        }
        return null;
    }

    private SlotLease tryAcquire(Path poolDir, int slot) throws IOException {
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
            publishSlotProperty(slot);
            LOG.fine(() -> "Leased slot " + slot + " (adminPort=" + ports.adminPort() + ")");
            return new SlotLease(slot, ports, lock);
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
     */
    private SlotLease tryGrow(Path poolDir) throws IOException {
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
        try {
            SlotPorts ports = new PoolProvisioner(config).provisionClaimedSlot(claim.slot);
            publishSlotProperty(claim.slot);
            return new SlotLease(claim.slot, ports, claim.lock);
        } catch (IOException | RuntimeException e) {
            LOG.warning(() -> "Slot " + claim.slot + " provisioning failed during grow: " + e.getMessage());
            discard(poolDir, claim);
            return null;
        }
    }

    /**
     * Give up a slot whose provisioning failed: release the claim and delete
     * the slot directory, so the next grow can use the index again instead of
     * skipping the half-made directory. Runs under {@code grow.lock}, which
     * every claim takes, so no claim picks the index while it is being deleted.
     */
    private static void discard(Path poolDir, ClaimedSlot claim) {
        try {
            PoolLocks.withGrowLock(poolDir, () -> {
                claim.release();
                PoolProvisioner.deleteRecursive(PoolPaths.slotDir(poolDir, claim.slot));
                return null;
            });
        } catch (IOException e) {
            LOG.warning(() -> "Could not remove slot " + claim.slot + " after its failed grow: " + e.getMessage());
        } finally {
            claim.release();
        }
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
     * Lowest slot index whose GF JVM is dead (admin port closed) AND whose
     * lock file we can acquire. The acquired lock is RETAINED in the returned
     * {@link ClaimedSlot} — callers must release it (typically by passing it
     * into the resulting {@link SlotLease}) so the claim survives the
     * {@code grow.lock} release that follows.
     */
    private ClaimedSlot claimRecyclableSlot(Path poolDir) throws IOException {
        List<Integer> indices = new ArrayList<>();
        try (var entries = Files.list(poolDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Integer idx = PoolPaths.slotIndex(entry.getFileName().toString());
                if (idx == null) {
                    continue;
                }
                if (!Files.exists(PoolPaths.portsFile(poolDir, idx))) {
                    continue; // mid-bootstrap; not ours to recycle
                }
                indices.add(idx);
            }
        }
        indices.sort(Comparator.naturalOrder());
        for (int slot : indices) {
            SlotPorts ports;
            try {
                ports = SlotPorts.readFrom(PoolPaths.portsFile(poolDir, slot));
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (PoolProvisioner.isPortHealthy(ports.adminPort())) {
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
            if (lock != null) {
                return new ClaimedSlot(slot, lock);
            }
        }
        return null;
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
