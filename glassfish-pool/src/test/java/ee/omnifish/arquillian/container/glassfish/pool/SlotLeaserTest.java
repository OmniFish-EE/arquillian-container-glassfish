/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tests {@link SlotLeaser} against a tmp pool dir, using real
 * {@link ServerSocket}s on loopback to play the role of "alive" admin ports.
 * Fake sockets let us exercise the health probe path without spinning up a
 * real GlassFish — much faster than the integration test, and runs in CI.
 */
class SlotLeaserTest {

    private final List<ServerSocket> fakes = new ArrayList<>();

    @AfterEach
    void closeFakes() throws IOException {
        for (ServerSocket s : fakes) {
            s.close();
        }
        fakes.clear();
    }

    @Test
    void leasesAReadySlot(@TempDir Path tmp) throws Exception {
        int adminPort = openFake();
        seedSlot(tmp, 1, adminPort);

        try (SlotLease lease = leaserFor(tmp).lease()) {
            assertThat(lease, notNullValue());
            assertThat(lease.slotIndex(), equalTo(1));
            assertThat(lease.ports().adminPort(), equalTo(adminPort));
        }
    }

    @Test
    void prefersLeastRecentlyUsedSlot(@TempDir Path tmp) throws Exception {
        int port1 = openFake();
        int port2 = openFake();
        seedSlot(tmp, 1, port1);
        seedSlot(tmp, 2, port2);

        // Touch slot-2's lock with an older mtime so it sorts first.
        Path lock1 = PoolPaths.lockFile(tmp, 1);
        Path lock2 = PoolPaths.lockFile(tmp, 2);
        Files.createFile(lock1);
        Files.createFile(lock2);
        Files.setLastModifiedTime(lock1, java.nio.file.attribute.FileTime.fromMillis(2000));
        Files.setLastModifiedTime(lock2, java.nio.file.attribute.FileTime.fromMillis(1000));

        try (SlotLease lease = leaserFor(tmp).lease()) {
            assertThat(lease.slotIndex(), equalTo(2));
        }
    }

    @Test
    void skipsDeadSlotAndPicksAlive(@TempDir Path tmp) throws Exception {
        int dead = freePort();      // closed before we try
        int alive = openFake();
        seedSlot(tmp, 1, dead);
        seedSlot(tmp, 2, alive);

        try (SlotLease lease = leaserFor(tmp).lease()) {
            assertThat(lease.slotIndex(), equalTo(2));
        }
    }

    @Test
    void timesOutWhenNoLiveSlotExists(@TempDir Path tmp) throws Exception {
        seedSlot(tmp, 1, freePort());

        SlotLeaser leaser = new SlotLeaser(
                new PoolConfig(tmp, null, 0, PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE),
                1L);
        assertThrows(IllegalStateException.class, leaser::lease);
    }

    @Test
    void skipsSlotsWithoutPortsFile(@TempDir Path tmp) throws Exception {
        // Mid-bootstrap dir: exists, no ports.properties — leaser should ignore
        // it and find slot-2.
        Files.createDirectories(PoolPaths.slotDir(tmp, 1));
        int alive = openFake();
        seedSlot(tmp, 2, alive);

        try (SlotLease lease = leaserFor(tmp).lease()) {
            assertThat(lease.slotIndex(), equalTo(2));
        }
    }

    /**
     * Regression for the tryGrow race fixed alongside this test: a successful
     * claim must keep the slot's lockfile held so a concurrent claim cannot
     * re-pick the same index. We exercise it sequentially — that's enough to
     * catch the regression, since the broken behaviour (probe-then-release)
     * would let two back-to-back claims both return the SAME dead slot, with
     * or without overlapping threads.
     */
    @Test
    void claimRetainsLockSoSecondClaimCannotRePickSameSlot(@TempDir Path tmp) throws Exception {
        seedSlot(tmp, 1, freePort()); // dead → recyclable
        SlotLeaser leaser = leaserFor(tmp);

        SlotLeaser.ClaimedSlot first = leaser.claimSlot(tmp);
        try {
            assertThat(first, notNullValue());
            assertThat(first.slot, equalTo(1));

            SlotLeaser.ClaimedSlot second = leaser.claimSlot(tmp);
            try {
                assertThat(second, notNullValue());
                // Recycle path must skip slot-1 (lock held by `first`) and
                // fall through to the fresh-index path, creating slot-2.
                assertThat(second.slot, equalTo(2));
            } finally {
                second.release();
            }
        } finally {
            first.release();
        }
    }

    /**
     * Threaded stress version of the sequential lock-retention test: many
     * threads simultaneously claim under {@code grow.lock} (mirroring what
     * {@code tryGrow} does) and hold for a beat before releasing. At every
     * moment no two threads may hold the same slot index. Loops a few rounds
     * with mixed recycle + fresh-index seeding to flush out any non-determinism
     * that the single-shot sequential test cannot reach.
     */
    @Test
    void concurrentClaimsAlwaysReturnDistinctSlots(@TempDir Path tmp) throws Exception {
        // Seed two dead recyclable slots so both code paths (recycle and
        // fresh-index) are exercised across the eight concurrent claimants.
        seedSlot(tmp, 1, freePort());
        seedSlot(tmp, 2, freePort());
        SlotLeaser leaser = leaserFor(tmp);

        int threads = 8;
        int rounds = 25;
        Set<Integer> heldNow = ConcurrentHashMap.newKeySet();
        AtomicReference<String> violation = new AtomicReference<>();
        // claimSlot's contract requires grow.lock held. In production each test
        // JVM is a separate process so the on-disk grow.lock works as a
        // cross-JVM mutex; within ONE JVM Java's FileLock throws
        // OverlappingFileLockException, so we substitute a ReentrantLock here
        // — same role (serialise entry), different mechanism.
        ReentrantLock growLockSubstitute = new ReentrantLock();
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds && violation.get() == null; round++) {
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<CompletableFuture<Void>> tasks = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    tasks.add(CompletableFuture.runAsync(() -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        SlotLeaser.ClaimedSlot claim;
                        growLockSubstitute.lock();
                        try {
                            claim = leaser.claimSlot(tmp);
                        } catch (IOException e) {
                            violation.compareAndSet(null, "claim threw: " + e);
                            return;
                        } finally {
                            growLockSubstitute.unlock();
                        }
                        if (claim == null) {
                            violation.compareAndSet(null, "claim returned null");
                            return;
                        }
                        try {
                            if (!heldNow.add(claim.slot)) {
                                violation.compareAndSet(null,
                                        "duplicate claim of slot " + claim.slot);
                            }
                            // Hold briefly so overlap is observable.
                            try {
                                Thread.sleep(2);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        } finally {
                            heldNow.remove(claim.slot);
                            claim.release();
                        }
                    }, exec));
                }
                ready.await();
                go.countDown();
                CompletableFuture
                        .allOf(tasks.toArray(new CompletableFuture[0]))
                        .get(15, TimeUnit.SECONDS);
            }
        } finally {
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(violation.get(), nullValue());
    }

    /**
     * Regression: a second lease in the SAME JVM must skip a slot this JVM
     * already holds rather than crash. Re-locking a file already locked by
     * another channel in the same process throws
     * {@link java.nio.channels.OverlappingFileLockException} (a foreign JVM
     * would see {@code tryLock() == null} instead), so {@code tryAcquire} must
     * treat that as "busy, skip" exactly like the recycle path does. Here the
     * held slot is forced to sort first in the scan so it is re-encountered;
     * the leaser must fall through to the other ready slot.
     */
    @Test
    void scanSkipsSlotThisJvmAlreadyHoldsInsteadOfThrowing(@TempDir Path tmp) throws Exception {
        seedSlot(tmp, 1, openFake());
        seedSlot(tmp, 2, openFake());
        SlotLeaser leaser = leaserFor(tmp);

        try (SlotLease first = leaser.lease()) {
            int held = first.slotIndex();
            int other = held == 1 ? 2 : 1;
            // Force the held slot to sort first (oldest mtime) so the next
            // scan re-encounters the lock this JVM already owns.
            Path otherLock = PoolPaths.lockFile(tmp, other);
            if (!Files.exists(otherLock)) {
                Files.createFile(otherLock);
            }
            Files.setLastModifiedTime(PoolPaths.lockFile(tmp, held),
                    java.nio.file.attribute.FileTime.fromMillis(1000));
            Files.setLastModifiedTime(otherLock,
                    java.nio.file.attribute.FileTime.fromMillis(2000));

            try (SlotLease second = leaser.lease()) {
                assertThat(second.slotIndex(), equalTo(other));
            }
        }
    }

    @Test
    void releasingLeaseLetsNextLeaserAcquire(@TempDir Path tmp) throws Exception {
        int alive = openFake();
        seedSlot(tmp, 1, alive);
        SlotLeaser leaser = leaserFor(tmp);

        SlotLease first = leaser.lease();
        assertThat(first.slotIndex(), equalTo(1));
        first.close();

        try (SlotLease second = leaser.lease()) {
            assertThat(second.slotIndex(), equalTo(1));
        }
    }

    private SlotLeaser leaserFor(Path poolDir) {
        // Source is null because these tests never trigger grow.
        return new SlotLeaser(
                new PoolConfig(poolDir, null, 0,
                        PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE),
                3L);
    }

    private void seedSlot(Path poolDir, int idx, int adminPort) throws IOException {
        Files.createDirectories(PoolPaths.slotDir(poolDir, idx));
        new SlotPorts(adminPort, adminPort + 1, adminPort + 2, "/fake")
                .writeTo(PoolPaths.portsFile(poolDir, idx));
    }

    private int openFake() throws IOException {
        // Backlog 50 + an accept-and-close drainer thread so health probes can
        // hit the same fake port repeatedly without filling the listen queue.
        // Windows enforces backlog strictly: with backlog 1 and no accept(),
        // the second probe in a row gets refused.
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        fakes.add(socket);
        Thread drainer = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket accepted = socket.accept()) {
                    // Close immediately — probe just needs the connect to succeed.
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-admin-drainer");
        drainer.setDaemon(true);
        drainer.start();
        int port = socket.getLocalPort();
        assertThat(port, greaterThan(0));
        return port;
    }

    private int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
