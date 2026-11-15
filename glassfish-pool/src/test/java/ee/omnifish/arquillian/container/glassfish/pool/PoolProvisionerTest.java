/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;

/**
 * Slot setup and restart against a {@link FakeGlassFish}, whose asadmin logs
 * its calls. A real server's admin port is played by
 * {@link FakeGlassFish#answerOn}.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
class PoolProvisionerTest {

    /** Fails {@code boom} only after a restart, and every stop. */
    private static final String BOOM_AFTER_RESTART_AND_STOP_FAILS = "case \"$*\" in\n"
            + "  *restart-domain*) touch \"$(dirname \"$0\")/restarted\" ;;\n"
            + "  *boom*) if [ -f \"$(dirname \"$0\")/restarted\" ]; then exit 1; fi ;;\n"
            + "  *stop-domain*) exit 1 ;;\n"
            + "  *_get-restart-required*) echo false ;;\n"
            + "esac\n";

    @Test
    void newSlotGetsSettingsAndCommandsInOrder(@TempDir Path tmp) throws Exception {
        SlotSetup setup = new SlotSetup(List.of("a=b"), "2g", ":org.example...",
                List.of("create-file-user --groups g u"), List.of("set-log-levels x=FINE"), true);
        PoolConfig config = config(tmp, FakeGlassFish.BOOM_FAILS, setup);
        String remote = "--terse --port " + config.adminPort(1) + " ";

        new PoolProvisioner(config).provisionSlot(1);

        assertThat(FakeGlassFish.calls(slotInstall(config)), contains(
                "--terse start-domain domain1",
                remote + "create-file-user --groups g u",
                remote + "_get-restart-required",
                remote + "set-log-levels x=FINE"));
        assertThat(DomainXmlEditor.jvmOptions(PoolProvisioner.domainXml(slotInstall(config))),
                hasItems("-Da=b", "-Xmx2g", "-ea:org.example..."));
        assertThat(Files.exists(PoolPaths.portsFile(config.poolDir(), 1)), equalTo(true));
    }

    @Test
    void restartsOnceWhenPostBootCommandsNeedIt(@TempDir Path tmp) throws Exception {
        SlotSetup setup = new SlotSetup(List.of(), null, null, List.of("set x=y"), List.of("after"), true);
        PoolConfig config = config(tmp, "case \"$*\" in *_get-restart-required*) echo true ;; esac\n", setup);
        String remote = "--terse --port " + config.adminPort(1) + " ";

        new PoolProvisioner(config).provisionSlot(1);

        assertThat(FakeGlassFish.calls(slotInstall(config)), contains(
                "--terse start-domain domain1",
                remote + "set x=y",
                remote + "_get-restart-required",
                "--terse restart-domain domain1",
                remote + "after"));
    }

    @Test
    void failingCommandFailsSetupAndLeavesNoPortsFile(@TempDir Path tmp) throws Exception {
        SlotSetup setup = new SlotSetup(List.of(), null, null, List.of("boom", "never"), List.of("after"), true);
        PoolConfig config = config(tmp, FakeGlassFish.BOOM_FAILS, setup);

        assertThrows(CommandFailedException.class, () -> new PoolProvisioner(config).provisionSlot(1));

        assertThat(Files.exists(PoolPaths.portsFile(config.poolDir(), 1)), equalTo(false));
        assertThat(String.join("\n", FakeGlassFish.calls(slotInstall(config))),
                not(containsString("never")));
    }

    @Test
    void withoutFailOnCommandErrorAFailingCommandIsLogged(@TempDir Path tmp) throws Exception {
        SlotSetup setup = new SlotSetup(List.of(), null, null, List.of("boom", "next"), List.of("after"), false);
        PoolConfig config = config(tmp, FakeGlassFish.BOOM_FAILS, setup);
        String remote = "--terse --port " + config.adminPort(1) + " ";

        new PoolProvisioner(config).provisionSlot(1);

        assertThat(FakeGlassFish.calls(slotInstall(config)), hasItems(remote + "next", remote + "after"));
        assertThat(Files.exists(PoolPaths.portsFile(config.poolDir(), 1)), equalTo(true));
    }

    @Test
    void restartRunsOnlyThePostStartCommands(@TempDir Path tmp) throws Exception {
        SlotSetup setup = new SlotSetup(List.of(), null, null, List.of("once"), List.of("every"), true);
        PoolConfig config = config(tmp, FakeGlassFish.BOOM_FAILS, setup);
        PoolProvisioner provisioner = new PoolProvisioner(config);
        SlotPorts ports = provisioner.provisionSlot(1);
        Files.delete(slotInstall(config).resolve("glassfish/bin/calls.log"));

        provisioner.restartSlot(1, ports);

        assertThat(FakeGlassFish.calls(slotInstall(config)), contains(
                "--terse restart-domain domain1",
                "--terse --port " + config.adminPort(1) + " every"));
        assertThat(Files.exists(PoolPaths.portsFile(config.poolDir(), 1)), equalTo(true));
    }

    /**
     * After a failed post-start command the slot must not be leased as it is,
     * even when it keeps running because stopping it fails too.
     */
    @Test
    void failedRestartLeavesTheSlotUnpublishedEvenWhenStoppingFails(@TempDir Path tmp) throws Exception {
        SlotSetup setup = new SlotSetup(List.of(), null, null, List.of(), List.of("boom"), true);
        PoolConfig config = config(tmp, BOOM_AFTER_RESTART_AND_STOP_FAILS, setup);
        PoolProvisioner provisioner = new PoolProvisioner(config);
        SlotPorts ports = provisioner.provisionSlot(1);

        try (ServerSocket running = FakeGlassFish.answerOn(config.adminPort(1))) {
            assertThrows(CommandFailedException.class, () -> provisioner.restartSlot(1, ports));
        }

        assertThat(Files.exists(PoolPaths.portsFile(config.poolDir(), 1)), equalTo(false));
        assertThat(FakeGlassFish.calls(slotInstall(config)),
                hasItems("--terse stop-domain domain1", "--terse stop-domain --kill domain1"));
    }

    /**
     * A slot left without ports.properties may still have its server running.
     * It is stopped before the slot is re-created, and if it keeps running,
     * the install isn't cloned over.
     */
    @Test
    void recreatingASlotStopsItsServerAndRefusesIfItKeepsRunning(@TempDir Path tmp) throws Exception {
        PoolConfig config = config(tmp, "exit 0\n", SlotSetup.NONE);
        PoolProvisioner provisioner = new PoolProvisioner(config);
        provisioner.provisionSlot(1);
        Files.delete(PoolPaths.portsFile(config.poolDir(), 1));

        try (ServerSocket running = FakeGlassFish.answerOn(config.adminPort(1))) {
            IOException e = assertThrows(IOException.class, () -> provisioner.provisionSlot(1));
            assertThat(e.getMessage(), containsString("still answers on admin port " + config.adminPort(1)));
        }

        // The old install, with its own log: started once, then stopped, and not replaced.
        assertThat(FakeGlassFish.calls(slotInstall(config)),
                contains("--terse start-domain domain1", "--terse stop-domain domain1"));
    }

    /**
     * {@code up} and the {@code provision} goal hold the slot lock while they
     * build, like the grow path, so a slot whose lock another JVM holds is
     * being built, or restarted, there, and is left alone.
     */
    @Test
    void provisionSlotLeavesALockedSlotAlone(@TempDir Path tmp) throws Exception {
        PoolConfig config = config(tmp, "exit 0\n", SlotSetup.NONE);
        Files.createDirectories(PoolPaths.slotDir(config.poolDir(), 1));

        try (AutoCloseable settingUp = ForeignJvm.hold(PoolPaths.lockFile(config.poolDir(), 1))) {
            assertThat(new PoolProvisioner(config).provisionSlot(1), equalTo(null));
        }

        assertThat(Files.exists(slotInstall(config)), equalTo(false));
    }

    private static PoolConfig config(Path tmp, String script, SlotSetup setup) throws IOException {
        Path source = FakeGlassFish.install(tmp.resolve("source"), script);
        return new PoolConfig(tmp.resolve("pool"), source, 1, FakeGlassFish.freePort(),
                PoolConfig.DEFAULT_PORT_STRIDE, setup, false, false);
    }

    private static Path slotInstall(PoolConfig config) {
        return PoolPaths.slotDir(config.poolDir(), 1).resolve(PoolProvisioner.SLOT_INSTALL_DIRNAME);
    }
}
