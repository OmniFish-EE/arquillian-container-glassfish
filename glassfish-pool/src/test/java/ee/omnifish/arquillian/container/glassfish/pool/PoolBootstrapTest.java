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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
