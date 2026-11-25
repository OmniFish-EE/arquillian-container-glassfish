/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
     * <p>Hold the slot's lock from another JVM, as the leasing test JVM would,
     * point ports.properties at a port nothing listens on (simulating
     * mid-restart), and assert {@code up} fast-exits — proven by
     * {@code source=null}: provisioning would NPE on the missing source.
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

        try (AutoCloseable leaser = ForeignJvm.hold(PoolPaths.lockFile(tmp, 1))) {
            PoolConfig config = new PoolConfig(tmp, /*source*/null, 1,
                    PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);
            PoolBootstrap.up(config); // must fast-exit; provisioning would fail on null source
        }
    }

    /**
     * Whoever sets a slot up or restarts it holds its lock until it writes
     * ports.properties, so {@code up} leaves a locked slot alone, with or
     * without one. Proven by {@code source=null}, as above.
     */
    @Test
    void upLeavesALockedSlotWithoutPortsFileAlone(@TempDir Path tmp) throws Exception {
        Files.createDirectories(PoolPaths.slotDir(tmp, 1));

        try (AutoCloseable settingUp = ForeignJvm.hold(PoolPaths.lockFile(tmp, 1))) {
            PoolConfig config = new PoolConfig(tmp, /*source*/null, 1,
                    PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);
            PoolBootstrap.up(config);
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void upFailsOnAFailingCommand(@TempDir Path tmp) throws Exception {
        SlotSetup setup = new SlotSetup(List.of(), null, null, List.of("boom"), List.of(), true);
        PoolConfig config = fakePool(tmp, FakeGlassFish.BOOM_FAILS, setup);

        IOException e = assertThrows(IOException.class, () -> PoolBootstrap.up(config));

        assertThat(e.getMessage(), containsString("Slot 1 provisioning failed: Command failed: boom"));
        assertThat(Files.exists(PoolPaths.portsFile(config.poolDir(), 1)), equalTo(false));
    }

    @Test
    void downIsNoopOnEmptyPool(@TempDir Path tmp) {
        PoolConfig config = new PoolConfig(tmp, null, 0,
                PoolConfig.DEFAULT_ADMIN_BASE, PoolConfig.DEFAULT_PORT_STRIDE);
        PoolBootstrap.down(config); // must not throw
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void downStopsEverySlot(@TempDir Path tmp) throws Exception {
        PoolConfig config = fakePool(tmp, "exit 0\n", SlotSetup.NONE);
        PoolBootstrap.up(config);

        PoolBootstrap.down(config);

        assertThat(FakeGlassFish.calls(slotInstall(config)),
                contains("--terse start-domain domain1", "--terse stop-domain domain1"));
    }

    /**
     * The next {@code up} reuses running slots as they are, so a slot that
     * keeps running after {@code stop-domain} and {@code stop-domain --kill}
     * fails {@code down}.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void downFailsWhenASlotKeepsRunning(@TempDir Path tmp) throws Exception {
        PoolConfig config = fakePool(tmp, "case \"$*\" in *stop-domain*) exit 1 ;; esac\n", SlotSetup.NONE);
        PoolBootstrap.up(config);
        fakes.add(FakeGlassFish.answerOn(config.adminPort(1)));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PoolBootstrap.down(config));

        assertThat(e.getMessage(), containsString("1 (admin port " + config.adminPort(1) + ")"));
        assertThat(FakeGlassFish.calls(slotInstall(config)),
                hasItems("--terse stop-domain domain1", "--terse stop-domain --kill domain1"));
    }

    /**
     * A slot that a run in another terminal holds keeps running when this
     * build's pool stops, and doesn't fail the stop, though it answers.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void theStopAfterTheBuildLeavesASlotOfARunInAnotherJvm(@TempDir Path tmp) throws Exception {
        PoolConfig config = fakePool(tmp, "exit 0\n", SlotSetup.NONE, 2);
        PoolBootstrap.up(config);
        fakes.add(FakeGlassFish.answerOn(config.adminPort(2)));

        try (ForeignJvm.Holder run = ForeignJvm.hold(PoolPaths.runLockFile(config.poolDir(), 2))) {
            PoolBootstrap.downAfterBuild(config);
        }

        assertThat(FakeGlassFish.calls(slotInstall(config, 1)),
                contains("--terse start-domain domain1", "--terse stop-domain domain1"));
        assertThat(FakeGlassFish.calls(slotInstall(config, 2)), contains("--terse start-domain domain1"));
    }

    /** On Ctrl+C, also with the build's own test JVMs still holding their slots. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void theShutdownHookLeavesOnlyASlotOfARunInAnotherJvm(@TempDir Path tmp) throws Exception {
        PoolConfig config = fakePool(tmp, "exit 0\n", SlotSetup.NONE, 2);
        PoolBootstrap.up(config);

        try (ForeignJvm.Holder test = ForeignJvm.hold(PoolPaths.lockFile(config.poolDir(), 1));
             ForeignJvm.Holder run = ForeignJvm.hold(PoolPaths.runLockFile(config.poolDir(), 2))) {
            PoolBootstrap.stopStartedPools();
        }

        assertThat(FakeGlassFish.calls(slotInstall(config, 1)),
                contains("--terse start-domain domain1", "--terse stop-domain domain1"));
        assertThat(FakeGlassFish.calls(slotInstall(config, 2)), contains("--terse start-domain domain1"));
    }

    /** As glassfish:stop ends glassfish:run, an explicit down ends every run. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void downStopsASlotOfARunInAnotherJvm(@TempDir Path tmp) throws Exception {
        PoolConfig config = fakePool(tmp, "exit 0\n", SlotSetup.NONE);
        PoolBootstrap.up(config);

        try (ForeignJvm.Holder run = ForeignJvm.hold(PoolPaths.runLockFile(config.poolDir(), 1))) {
            PoolBootstrap.down(config);
        }

        assertThat(FakeGlassFish.calls(slotInstall(config)),
                contains("--terse start-domain domain1", "--terse stop-domain domain1"));
    }

    /** The first half of up: a run's lease grows the pool by the slot it needs. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void prepareStagesAndStartsNoSlot(@TempDir Path tmp) throws Exception {
        PoolConfig config = fakePool(tmp, "exit 0\n", SlotSetup.NONE);
        List<String> staged = new ArrayList<>();

        PoolBootstrap.prepare(config, () -> staged.add("staged"));

        assertThat(staged, contains("staged"));
        assertThat(Files.isDirectory(config.poolDir()), equalTo(true));
        assertThat(Files.exists(PoolPaths.slotDir(config.poolDir(), 1)), equalTo(false));
    }

    /**
     * One JVM can start several pools: one per module, or one per build
     * under mvnd, whose JVM outlives the build. The shutdown hook stops each
     * one {@code down} didn't stop, not just the first.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void theShutdownHookStopsEveryPoolDownDidNotStop(@TempDir Path tmp) throws Exception {
        PoolConfig stopped = fakePool(tmp.resolve("stopped"), "exit 0\n", SlotSetup.NONE);
        PoolConfig stillUp = fakePool(tmp.resolve("still-up"), "exit 0\n", SlotSetup.NONE);
        PoolBootstrap.up(stopped);
        PoolBootstrap.up(stillUp);
        PoolBootstrap.down(stopped);

        PoolBootstrap.stopStartedPools();

        assertThat(FakeGlassFish.calls(slotInstall(stopped)),
                contains("--terse start-domain domain1", "--terse stop-domain domain1"));
        assertThat(FakeGlassFish.calls(slotInstall(stillUp)),
                contains("--terse start-domain domain1", "--terse stop-domain domain1"));
    }

    /**
     * Maven can exit while {@code down} still waits for {@code stop-domain}.
     * The shutdown hook then has to stop the pool too, so {@code down} only
     * lets go of a pool once it stopped it.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void theShutdownHookStopsAPoolDownIsStillStopping(@TempDir Path tmp) throws Exception {
        Path release = tmp.resolve("release");
        // Only the first stop-domain gets through mkdir, and waits for the release.
        PoolConfig config = fakePool(tmp, "case \"$*\" in *stop-domain*)\n"
                + "  if mkdir '" + tmp.resolve("first-stop") + "' 2>/dev/null; then\n"
                + "    while [ ! -f '" + release + "' ]; do sleep 0.1; done\n"
                + "  fi ;;\n"
                + "esac\n", SlotSetup.NONE);
        PoolBootstrap.up(config);
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> PoolBootstrap.down(config));
        try {
            for (int i = 0; i < 200 && FakeGlassFish.calls(slotInstall(config)).size() < 2; i++) {
                Thread.sleep(50);
            }

            PoolBootstrap.stopStartedPools();

            assertThat(FakeGlassFish.calls(slotInstall(config)), contains("--terse start-domain domain1",
                    "--terse stop-domain domain1", "--terse stop-domain domain1"));
        } finally {
            Files.createFile(release);
            stopping.get(30, TimeUnit.SECONDS);
        }
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

    /** A one-slot pool cloned from a {@link FakeGlassFish}. */
    private static PoolConfig fakePool(Path tmp, String script, SlotSetup setup) throws IOException {
        return fakePool(tmp, script, setup, 1);
    }

    private static PoolConfig fakePool(Path tmp, String script, SlotSetup setup, int size) throws IOException {
        Path source = FakeGlassFish.install(tmp.resolve("source"), script);
        return new PoolConfig(tmp.resolve("pool"), source, size, FakeGlassFish.freePort(),
                PoolConfig.DEFAULT_PORT_STRIDE, setup, false, false);
    }

    private static Path slotInstall(PoolConfig config) {
        return slotInstall(config, 1);
    }

    private static Path slotInstall(PoolConfig config, int slot) {
        return PoolPaths.slotDir(config.poolDir(), slot).resolve(PoolProvisioner.SLOT_INSTALL_DIRNAME);
    }
}
