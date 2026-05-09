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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 *   <li>{@code slot-N/lock} — one byte per JVM, exclusively locked via
 *       {@link FileChannel#tryLock()}. Linux/macOS use {@code fcntl} locks
 *       under the hood; on Windows the locking is mandatory.</li>
 *   <li>{@code slot-N/ports.properties} — appears once provisioning completes.
 *       A slot dir without it is mid-bootstrap and skipped by the scan.</li>
 * </ul>
 *
 * <p>If every existing slot is busy the leaser tries to grow the pool: it
 * acquires {@code grow.lock}, picks the next index (preferring to recycle a
 * dead slot), releases the lock, and runs the provisioner. The provisioning
 * itself is OUTSIDE {@code grow.lock} so concurrent test JVMs that all need
 * a cold slot grow in parallel rather than serialising.
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
            int grown = tryGrow(poolDir);
            if (grown > 0) {
                SlotLease grownLease = tryAcquire(poolDir, grown);
                if (grownLease != null) {
                    return grownLease;
                }
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
        FileChannel channel = FileChannel.open(PoolPaths.lockFile(poolDir, slot),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            SlotPorts ports;
            try {
                ports = SlotPorts.readFrom(portsFile);
            } catch (IOException | RuntimeException e) {
                // Half-written ports.properties — skip the slot, scan continues.
                lock.release();
                channel.close();
                return null;
            }
            // A free lock file does not imply a live GF: previous owner's GF may
            // have OOM-crashed, been killed, or been mid-shutdown when we grabbed
            // the lock. Probe before committing.
            if (!PoolProvisioner.isPortHealthy(ports.adminPort())) {
                lock.release();
                channel.close();
                return null;
            }
            // Expose the slot index as a system property so test-side
            // observers (logging filters, progress listeners, etc.) can tag
            // their output with the slot number. Set even on a single-slot
            // pool so consumers can rely on the property existing.
            System.setProperty("gf.pool.slot", String.valueOf(slot));
            LOG.fine(() -> "Leased slot " + slot + " (adminPort=" + ports.adminPort() + ")");
            return new SlotLease(slot, ports, channel, lock);
        } catch (RuntimeException | IOException e) {
            // Always release the channel between open() and successful return,
            // otherwise a transient failure leaks an FD per poll cycle.
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException ignored) {
                    /* nothing useful */
                }
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                /* nothing useful */
            }
            throw e;
        }
    }

    /**
     * Pick the next free slot index under {@code grow.lock}, then provision it
     * outside the lock. Returns the chosen index on successful provisioning,
     * or 0 if no provisioning happened (no source configured, someone else
     * won the race, or provisioning failed).
     */
    private int tryGrow(Path poolDir) throws IOException {
        if (config.source() == null) {
            // Caller didn't supply a source install — typical for test-JVM
            // leasers when the build hasn't forwarded gf.pool.source. Skip
            // grow so the lease loop just waits for an existing slot.
            return 0;
        }
        Path growLock = PoolPaths.growLock(poolDir);
        int chosen;
        try (FileChannel fc = FileChannel.open(growLock,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
             FileLock ignored = fc.lock()) {
            int recycle = findRecyclableSlot(poolDir);
            if (recycle > 0) {
                chosen = recycle;
                // Caller will overwrite contents; we keep the dir to keep the claim visible.
            } else {
                chosen = 1;
                while (Files.exists(PoolPaths.slotDir(poolDir, chosen))) {
                    chosen++;
                }
                Files.createDirectory(PoolPaths.slotDir(poolDir, chosen));
            }
        }
        final int chosenFinal = chosen;
        try {
            new PoolProvisioner(config).provisionSlot(chosenFinal);
        } catch (IOException e) {
            LOG.warning(() -> "Slot " + chosenFinal + " provisioning failed during grow: " + e.getMessage());
            return 0;
        }
        return chosenFinal;
    }

    /**
     * Lowest slot index whose GF JVM is dead (admin port closed) AND whose
     * lock file is currently free. Recycling avoids the pool exceeding the
     * concurrency cap implied by {@code -T} after a slot dies.
     */
    private int findRecyclableSlot(Path poolDir) throws IOException {
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
            try (FileChannel ch = FileChannel.open(PoolPaths.lockFile(poolDir, slot),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                FileLock probe = ch.tryLock();
                if (probe == null) {
                    continue;
                }
                probe.release();
                return slot;
            } catch (IOException e) {
                /* skip */
            }
        }
        return 0;
    }

    private static long lockMtime(Path poolDir, int slot) {
        try {
            return Files.getLastModifiedTime(PoolPaths.lockFile(poolDir, slot)).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
