/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoolBootstrapTest {

    private final List<ServerSocket> fakes = new ArrayList<>();

    @AfterEach
    void closeFakes() throws IOException {
        for (ServerSocket s : fakes) {
            s.close();
        }
        fakes.clear();
    }

    /**
     * If every slot already has a healthy admin port, {@code up} must fast-exit
     * without touching the source install. The test exercises this by pre-seeding
     * slot-1 with a fake "alive" admin port (loopback {@link ServerSocket}) and
     * passing {@code source=null} — if {@code up} tried to provision, it would
     * fail with NPE/IllegalStateException on the missing source.
     */
    @Test
    void upFastExitsWhenPoolHealthy(@TempDir Path tmp) throws Exception {
        int adminPort = openFake();
        Files.createDirectories(PoolPaths.slotDir(tmp, 1));
        new SlotPorts(adminPort, adminPort + 1, adminPort + 2, "/fake")
                .writeTo(PoolPaths.portsFile(tmp, 1));

        PoolConfig config = new PoolConfig(tmp, /*source*/null, 1,
                PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);
        PoolBootstrap.up(config); // would throw if it tried to provision
    }

    /**
     * Two threads call {@code up} concurrently. Both should return cleanly
     * without exceptions; the {@code synchronized} block plus the fast-exit
     * means only the first one (if any) does work. With a pre-seeded healthy
     * pool, neither does work — but the synchronized block is still exercised.
     */
    @Test
    void concurrentUpsAreSerialised(@TempDir Path tmp) throws Exception {
        int adminPort = openFake();
        Files.createDirectories(PoolPaths.slotDir(tmp, 1));
        new SlotPorts(adminPort, adminPort + 1, adminPort + 2, "/fake")
                .writeTo(PoolPaths.portsFile(tmp, 1));

        PoolConfig config = new PoolConfig(tmp, null, 1,
                PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);

        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        PoolBootstrap.up(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, exec));
            }
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
        }
    }

    /**
     * Regression for the restartOnRelease vs. concurrent pool-up race:
     * a slot held by a leaser whose GF is mid-restart presents as
     * "port not healthy". Without the lock-busy carve-out in
     * {@link PoolBootstrap#isAlreadyProvisioned}, {@code up} would see the
     * unhealthy port and try to reprovision the slot under the live
     * leaser, then race-lose against the GF re-binding adminPort.
     *
     * <p>Hold the slot's lock file, point ports.properties at a port nothing
     * listens on (simulating mid-restart), and assert {@code up} fast-exits
     * — proven by {@code source=null}: provisioning would NPE on the missing
     * source.
     */
    @Test
    void upTreatsLockBusySlotAsHealthy(@TempDir Path tmp) throws Exception {
        Files.createDirectories(PoolPaths.slotDir(tmp, 1));
        // Reserve an unused port and immediately close it so the slot's
        // ports.properties points at a port nothing listens on.
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        new SlotPorts(deadPort, deadPort + 1, deadPort + 2, "/fake")
                .writeTo(PoolPaths.portsFile(tmp, 1));

        Path lockPath = PoolPaths.lockFile(tmp, 1);
        try (FileChannel ch = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock held = ch.lock()) {
            assertThat(held.isValid(), equalTo(true));
            PoolConfig config = new PoolConfig(tmp, /*source*/null, 1,
                    PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);
            PoolBootstrap.up(config); // must fast-exit; provisioning would fail on null source
        }
    }

    @Test
    void downIsNoopOnEmptyPool(@TempDir Path tmp) {
        PoolConfig config = new PoolConfig(tmp, null, 0,
                PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);
        PoolBootstrap.down(config); // must not throw
    }

    @Test
    void statusOnEmptyPoolDirPrintsHint(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope");
        PoolConfig config = new PoolConfig(missing, null, 0,
                PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);
        // Just verify no exception; output goes to stdout.
        PoolBootstrap.status(config);
        assertThat(Files.exists(missing), equalTo(false));
    }

    /**
     * A slot directory with no {@code ports.properties} and no live process
     * holding the slot lock represents an abandoned bootstrap (e.g. Maven was
     * Ctrl+C'd mid-provisioning). Status must label it {@code orphan}, not
     * {@code bootstrap}, so the operator knows to nuke vs. wait.
     */
    @Test
    void statusLabelsAbandonedSlotAsOrphan(@TempDir Path tmp) throws Exception {
        Files.createDirectories(PoolPaths.slotDir(tmp, 1));
        PoolConfig config = new PoolConfig(tmp, null, 1,
                PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            PoolBootstrap.status(config);
        } finally {
            System.setOut(original);
        }
        assertThat(buf.toString(StandardCharsets.UTF_8), containsString("orphan"));
    }

    private int openFake() throws IOException {
        // Backlog 50 so a flurry of concurrent isPortHealthy probes (used in
        // the parallel test) don't fall off the listen queue. backlog=1 makes
        // even 4-thread fan-in flaky on Linux.
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        fakes.add(socket);
        return socket.getLocalPort();
    }
}
