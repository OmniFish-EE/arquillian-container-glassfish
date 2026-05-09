/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;

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
        ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        fakes.add(socket);
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
