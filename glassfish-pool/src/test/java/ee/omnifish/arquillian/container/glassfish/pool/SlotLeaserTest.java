/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
import java.util.stream.Collectors;

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
        // Shared-slot tests must fully release; a leaked refcount would poison
        // later tests (the registry is JVM-static).
        assertThat(SharedSlotRegistry.isIdle(), equalTo(true));
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
        // claimSlot's contract requires grow.lock held. PoolLocks.withGrowLock
        // lets the threads of one JVM take turns before taking the file lock,
        // which on its own would throw OverlappingFileLockException for the
        // second thread.
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
                        try {
                            claim = PoolLocks.withGrowLock(tmp, () -> leaser.claimSlot(tmp));
                        } catch (IOException e) {
                            violation.compareAndSet(null, "claim threw: " + e);
                            return;
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
     * already holds, and leave it held. Probing it through a second channel
     * threw {@link java.nio.channels.OverlappingFileLockException}, and
     * closing that channel released the first lease's lock on Linux and
     * macOS, so another JVM could lease the slot too. Here the held slot is
     * forced to sort first in the scan so it is re-encountered; the leaser
     * must fall through to the other ready slot.
     */
    @Test
    void scanSkipsSlotThisJvmAlreadyHoldsInsteadOfThrowing(@TempDir Path tmp) throws Exception {
        seedSlot(tmp, 1, openFake());
        seedSlot(tmp, 2, openFake());
        // Slot 1 sorts first (oldest lock mtime), so the first lease takes it
        // and the next scan re-encounters it. The times are set up front:
        // setting them opens and closes the file, which would itself release
        // a held lock on Linux and macOS.
        Path lock1 = PoolPaths.lockFile(tmp, 1);
        Path lock2 = PoolPaths.lockFile(tmp, 2);
        Files.createFile(lock1);
        Files.createFile(lock2);
        Files.setLastModifiedTime(lock1, java.nio.file.attribute.FileTime.fromMillis(1000));
        Files.setLastModifiedTime(lock2, java.nio.file.attribute.FileTime.fromMillis(2000));
        SlotLeaser leaser = leaserFor(tmp);

        try (SlotLease first = leaser.lease()) {
            assertThat(first.slotIndex(), equalTo(1));
            try (SlotLease second = leaser.lease()) {
                assertThat(second.slotIndex(), equalTo(2));
            }
            assertThat(ForeignJvm.canLock(lock1), equalTo(false));
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

    // --- shared-lease (SharedSlotRegistry / LeaseHandle) ---

    @Test
    void sharedGroupReusesOneSlotForSiblings(@TempDir Path tmp) throws Exception {
        int adminPort = openFake();
        seedSlot(tmp, 1, adminPort);
        SlotLeaser leaser = leaserFor(tmp);
        SharedSlotRegistry.Key key = new SharedSlotRegistry.Key("group", tmp);

        // Two siblings attach to the SAME slot — the second does not lease a new
        // one (it would have to skip the self-held slot and time out otherwise).
        LeaseHandle first = SharedSlotRegistry.acquire(key, leaser::lease);
        LeaseHandle second = SharedSlotRegistry.acquire(key, leaser::lease);
        assertThat(first.slotIndex(), equalTo(1));
        assertThat(second.slotIndex(), equalTo(1));
        assertThat(second.ports().adminPort(), equalTo(adminPort));

        // Non-last release keeps the slot up; last release hands the lease back.
        assertThat(second.release(), nullValue());
        SlotLease toFinalize = first.release();
        assertThat(toFinalize, notNullValue());
        assertThat(first.release(), nullValue()); // idempotent
        toFinalize.close();

        // Slot is free again — a fresh lease re-acquires it.
        try (SlotLease reacquired = leaserFor(tmp).lease()) {
            assertThat(reacquired.slotIndex(), equalTo(1));
        }
    }

    @Test
    void onlyLastOfThreeHoldersFinalizes(@TempDir Path tmp) throws Exception {
        seedSlot(tmp, 1, openFake());
        SlotLeaser leaser = leaserFor(tmp);
        SharedSlotRegistry.Key key = new SharedSlotRegistry.Key("group", tmp);

        LeaseHandle a = SharedSlotRegistry.acquire(key, leaser::lease);
        LeaseHandle b = SharedSlotRegistry.acquire(key, leaser::lease);
        LeaseHandle c = SharedSlotRegistry.acquire(key, leaser::lease);

        assertThat(a.release(), nullValue()); // 3 -> 2
        assertThat(c.release(), nullValue()); // 2 -> 1
        SlotLease toFinalize = b.release();    // 1 -> 0
        assertThat(toFinalize, notNullValue());
        toFinalize.close();
    }

    @Test
    void singleHolderReleaseFreesSlotForReLease(@TempDir Path tmp) throws Exception {
        // Pins the start()-failure rollback contract: releasing a leader that
        // never gained a sibling must hand back the lease so the slot is reusable.
        seedSlot(tmp, 1, openFake());
        SharedSlotRegistry.Key key = new SharedSlotRegistry.Key("group", tmp);

        LeaseHandle only = SharedSlotRegistry.acquire(key, leaserFor(tmp)::lease);
        SlotLease toFinalize = only.release();
        assertThat(toFinalize, notNullValue());
        toFinalize.close();

        try (SlotLease reacquired = leaserFor(tmp).lease()) {
            assertThat(reacquired.slotIndex(), equalTo(1));
        }
    }

    @Test
    void differentGroupsLeaseDistinctSlots(@TempDir Path tmp) throws Exception {
        seedSlot(tmp, 1, openFake());
        seedSlot(tmp, 2, openFake());
        SlotLeaser leaser = leaserFor(tmp);

        LeaseHandle a = SharedSlotRegistry.acquire(new SharedSlotRegistry.Key("a", tmp), leaser::lease);
        LeaseHandle b = SharedSlotRegistry.acquire(new SharedSlotRegistry.Key("b", tmp), leaser::lease);
        try {
            assertThat(a.slotIndex(), not(equalTo(b.slotIndex())));
        } finally {
            finalizeAndClose(a);
            finalizeAndClose(b);
        }
    }

    @Test
    void directHandleReturnsLeaseOnceThenNull(@TempDir Path tmp) throws Exception {
        seedSlot(tmp, 1, openFake());
        SlotLease lease = leaserFor(tmp).lease();
        LeaseHandle handle = new LeaseHandle.Direct(lease);

        assertThat(handle.slotIndex(), equalTo(1));
        assertThat(handle.release(), sameInstance(lease));
        assertThat(handle.release(), nullValue()); // idempotent
        lease.close();
    }

    // --- growing (the real PoolProvisioner against a fake install) ---

    /**
     * Regression: recycling a dead slot must re-create it. The provisioner's
     * reuse check counted the claim this JVM had just taken as a busy lock
     * and handed the dead slot back unchanged.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void growRecreatesARecycledDeadSlot(@TempDir Path tmp) throws Exception {
        Path poolDir = tmp.resolve("pool");
        PoolConfig config = growingConfig(poolDir, fakeInstall(tmp.resolve("source"), 0));
        seedSlot(poolDir, 1, freePort()); // dead → recyclable
        Path stale = Files.createDirectories(PoolPaths.slotDir(poolDir, 1)
                .resolve(PoolProvisioner.SLOT_INSTALL_DIRNAME)).resolve("stale");
        Files.createFile(stale);

        try (SlotLease lease = new SlotLeaser(config, 3L).lease()) {
            assertThat(lease.slotIndex(), equalTo(1));
            assertThat(lease.ports().adminPort(), equalTo(config.adminPort(1)));
            assertThat(Files.exists(stale), equalTo(false));
            assertThat(asadminCalls(lease), contains("--terse start-domain domain1"));
        }
    }

    /**
     * Regression: a failed grow left its half-made slot directory behind, so
     * every retry took the next index and made another clone.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void failedGrowFreesItsSlotIndex(@TempDir Path tmp) throws Exception {
        Path poolDir = Files.createDirectories(tmp.resolve("pool"));
        SlotLeaser leaser = new SlotLeaser(growingConfig(poolDir, fakeInstall(tmp.resolve("source"), 1)), 1L);

        assertThrows(IllegalStateException.class, leaser::lease);
        assertThat(slotDirs(poolDir), empty());
    }

    private static void finalizeAndClose(LeaseHandle handle) {
        SlotLease toFinalize = handle.release();
        if (toFinalize != null) {
            toFinalize.close();
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

    /** A pool that can grow from {@code source}, on ports nothing listens on. */
    private PoolConfig growingConfig(Path poolDir, Path source) throws IOException {
        return new PoolConfig(poolDir, source, 0, freePort(), PoolConfig.DEFAULT_PORT_STRIDE);
    }

    /**
     * A source install whose {@code asadmin} is a script that appends its
     * arguments to {@code calls.log} next to itself and exits with
     * {@code exitCode}: enough for {@link PoolProvisioner} to clone, patch
     * and "start" a slot without GlassFish.
     */
    private static Path fakeInstall(Path dir, int exitCode) throws IOException {
        Path asadmin = Files.createDirectories(dir.resolve("glassfish/bin")).resolve("asadmin");
        Files.writeString(asadmin, "#!/bin/sh\n"
                + "echo \"$@\" >> \"$(dirname \"$0\")/calls.log\"\n"
                + "exit " + exitCode + "\n");
        assertThat(asadmin.toFile().setExecutable(true), equalTo(true));
        Path config = Files.createDirectories(dir.resolve("glassfish/domains/domain1/config"));
        Files.writeString(config.resolve("domain.xml"), "<domain><configs><config name=\"server-config\">"
                + "<network-config><network-listeners>"
                + "<network-listener name=\"admin-listener\" port=\"4848\"/>"
                + "<network-listener name=\"http-listener-1\" port=\"8080\"/>"
                + "<network-listener name=\"http-listener-2\" port=\"8181\"/>"
                + "</network-listeners></network-config></config></configs></domain>");
        return dir;
    }

    private static List<String> asadminCalls(SlotLease lease) throws IOException {
        return Files.readAllLines(Paths.get(lease.ports().glassFishHome()).resolve("glassfish/bin/calls.log"));
    }

    private static List<String> slotDirs(Path poolDir) throws IOException {
        try (var entries = Files.list(poolDir)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> PoolPaths.slotIndex(name) != null)
                    .collect(Collectors.toList());
        }
    }
}
