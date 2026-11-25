/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static ee.omnifish.glassfish.maven.AbstractStartMojo.PostBootRestart.ALWAYS;
import static ee.omnifish.glassfish.maven.AbstractStartMojo.PostBootRestart.AUTO;
import static ee.omnifish.glassfish.maven.AbstractStartMojo.PostBootRestart.NEVER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.maven.AbstractStartMojo.PostBootRestart;
import ee.omnifish.glassfish.maven.staging.FakeDomain;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;
import ee.omnifish.glassfish.maven.staging.StagingCommands;

/** {@code start} and {@code stop} on an install whose {@code asadmin} is a shell script. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
class StartMojoTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String NO_RESTART_REQUIRED = "  *_get-restart-required*) echo false ;;\n";

    @TempDir
    Path home;

    private final RecordingLog log = new RecordingLog();

    @AfterEach
    void killServer() {
        FakeDomain.killServer(home);
    }

    @Test
    void startRunsThePostStartCommandsOnTheAdminPort() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, adminPort, FakeDomain.START));

        int startedOn = StartMojo.start(domain, StagingCommands.NONE, AUTO, false, false, TIMEOUT, log);
        StartMojo.runPostStartCommands(domain, startedOn,
                new StagingCommands("create-file-user --groups g joe", true), log);

        assertThat(startedOn, equalTo(adminPort));
        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse --port " + adminPort + " create-file-user --groups g joe")));
    }

    /**
     * Staging's post-boot commands, when it has them to run, run on the start itself, not on a brief one.
     * GlassFish needs no restart for them.
     */
    @Test
    void startRunsThePostBootCommandsItIsGiven() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(
                FakeDomain.install(home, adminPort, FakeDomain.START + NO_RESTART_REQUIRED));

        StartMojo.start(domain, new StagingCommands("create-file-user --groups g joe", true), AUTO, false, false,
                TIMEOUT, log);

        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse --port " + adminPort + " create-file-user --groups g joe",
                "--terse --port " + adminPort + " _get-restart-required")));
    }

    /** GlassFish applies an option such as -Xmx only on a restart, and says so, after any notice Java prints. */
    @Test
    void autoRestartsWhenGlassFishReportsThatItNeedsTo() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, adminPort, FakeDomain.START
                + FakeDomain.STOP + "  *_get-restart-required*) echo 'NOTE: Picked up JDK_JAVA_OPTIONS: -Dx=y';"
                + " echo true ;;\n"));

        StartMojo.start(domain, new StagingCommands("create-jvm-options -Xmx1g", true), AUTO, false, false, TIMEOUT,
                log);

        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse --port " + adminPort + " create-jvm-options -Xmx1g",
                "--terse --port " + adminPort + " _get-restart-required",
                "--terse --port " + adminPort + " stop-domain domain1",
                "--terse start-domain --timeout 90 domain1")));
        assertThat(log.infos, hasItem("Restarting GlassFish for its post-boot commands: GlassFish reports that a"
                + " change needs a restart"));
    }

    /** GlassFish applies a change to the admin listener only on a restart, without reporting that. */
    @Test
    void autoRestartsAfterACommandOnTheAdminListener() throws Exception {
        int adminPort = freePort();
        String command = "set configs.config.server-config.network-config.network-listeners.network-listener"
                + ".admin-listener.port=" + adminPort;
        GlassFishDomain domain = new GlassFishDomain(
                FakeDomain.install(home, adminPort, FakeDomain.START + FakeDomain.STOP + NO_RESTART_REQUIRED));

        StartMojo.start(domain, new StagingCommands(command, true), AUTO, false, false, TIMEOUT, log);

        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), hasItem("--terse --port " + adminPort + " stop-domain domain1"));
        assertThat(log.infos, hasItem("Restarting GlassFish for its post-boot commands: a set command changes the"
                + " admin listener"));
    }

    /** A restart too many costs less than a configuration that isn't active. */
    @Test
    void autoRestartsWhenItCanNotReadGlassFishsAnswer() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, adminPort, FakeDomain.START
                + FakeDomain.STOP + "  *_get-restart-required*) echo 'NOTE: Picked up JDK_JAVA_OPTIONS:"
                + " -Djavax.net.ssl.keyStorePassword=secret' ;;\n"));

        StartMojo.start(domain, new StagingCommands("set a=b", true), AUTO, false, false, TIMEOUT, log);

        assertThat(FakeDomain.calls(home), hasItem("--terse --port " + adminPort + " stop-domain domain1"));
        assertThat(log.warnings, equalTo(List.of("Can't tell whether GlassFish needs a restart, so it's restarted")));
    }

    /** A command, or what GlassFish says about it, may hold a secret, such as a -D option's value. */
    @Test
    void theReasonForARestartLeavesOutCommandsAndWhatGlassFishSays() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START
                + FakeDomain.STOP + "  *_get-restart-required*) echo true; echo \"Addition of:"
                + " '-Djavax.example.password=it's secret' cannot take effect without server restart\" ;;\n"));

        StartMojo.start(domain, new StagingCommands("create-jvm-options \"-Djavax.example.password=it's secret\"",
                true), AUTO, false, false, TIMEOUT, log);

        assertThat(log.infos, hasItem("Restarting GlassFish for its post-boot commands: GlassFish reports that a"
                + " change needs a restart"));
        assertThat(log.infos, everyItem(not(containsString("secret"))));
    }

    @Test
    void alwaysRestartsWithoutAsking() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(
                FakeDomain.install(home, adminPort, FakeDomain.START + FakeDomain.STOP));

        StartMojo.start(domain, new StagingCommands("set a=b", true), ALWAYS, false, false, TIMEOUT, log);

        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse --port " + adminPort + " set a=b",
                "--terse --port " + adminPort + " stop-domain domain1",
                "--terse start-domain --timeout 90 domain1")));
    }

    /** The debugger attaches to the start that stays, which runs the post-boot commands. */
    @Test
    void neverRunsThePostBootCommandsOnTheStartAsAsked() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, adminPort, FakeDomain.START
                + FakeDomain.STOP + "  *_get-restart-required*) echo true ;;\n"));

        StartMojo.start(domain, new StagingCommands("create-jvm-options -Xmx1g", true), NEVER, false, true, TIMEOUT,
                log);

        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 --suspend domain1",
                "--terse --port " + adminPort + " create-jvm-options -Xmx1g")));
    }

    /** The debugger attaches to the start that stays, so only that one waits for it. */
    @Test
    void withSuspendTheStartBeforeARestartIsNotSuspended() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(
                FakeDomain.install(home, adminPort, FakeDomain.START + FakeDomain.STOP + NO_RESTART_REQUIRED));

        StartMojo.start(domain, new StagingCommands("set a=b", true), AUTO, false, true, TIMEOUT, log);

        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse --port " + adminPort + " set a=b",
                "--terse --port " + adminPort + " _get-restart-required",
                "--terse --port " + adminPort + " stop-domain domain1",
                "--terse start-domain --timeout 90 --suspend domain1")));
        assertThat(log.infos, hasItem("Restarting GlassFish suspended, now that its post-boot commands have run"));
    }

    @Test
    void postBootRestartIsReadInAnyCase() throws Exception {
        assertThat(PostBootRestart.of("Always"), is(ALWAYS));
        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> PostBootRestart.of("sometimes"));

        assertThat(e.getMessage(), containsString("auto, always or never"));
    }

    /** It might be another domain on the same port, so start leaves it alone. */
    @Test
    void startFailsBeforeStartingAnythingWhenTheAdminPortAnswers() throws Exception {
        try (ServerSocket otherServer = new ServerSocket(0)) {
            GlassFishDomain domain = new GlassFishDomain(
                    FakeDomain.install(home, otherServer.getLocalPort(), FakeDomain.START));

            MojoExecutionException e = assertThrows(MojoExecutionException.class,
                    () -> StartMojo.start(domain, StagingCommands.NONE, AUTO, false, false, TIMEOUT, log));

            assertThat(e.getMessage(), containsString("already answers on admin port " + otherServer.getLocalPort()));
            assertThat(FakeDomain.calls(home), is(empty()));
        }
    }

    /** start checks them before it provisions or starts anything, so there's no server to stop again. */
    @Test
    void aPostStartCommandThatCanNotBeReadIsRejected() {
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> StartMojo.requireReadable(new StagingCommands("set \"a=b", true)));

        assertThat(e.getMessage(), containsString("Can't read a post-start command"));
    }

    /** Without failOnCommandError, it's logged when it's due, as any failed command, and the rest run. */
    @Test
    void withoutFailOnCommandErrorAPostStartCommandThatCanNotBeReadDoesNotStopTheStart() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, adminPort, FakeDomain.START));
        StagingCommands commands = new StagingCommands("set \"a=b\nset c=d", false);

        StartMojo.requireReadable(commands);
        StartMojo.runPostStartCommands(domain,
                StartMojo.start(domain, StagingCommands.NONE, AUTO, false, false, TIMEOUT, log), commands, log);

        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), hasItem("--terse --port " + adminPort + " set c=d"));
    }

    /** Otherwise a failure in pre-integration-test leaves the server running: Maven never gets to stop. */
    @Test
    void aFailedPostStartCommandStopsTheDomainAgain() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                FakeDomain.START + FakeDomain.STOP + "  *boom*) exit 1 ;;\n"));
        int startedOn = StartMojo.start(domain, StagingCommands.NONE, AUTO, false, false, TIMEOUT, log);

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> StartMojo.runPostStartCommands(domain, startedOn, new StagingCommands("boom", true), log));

        assertThat(e.getMessage(), containsString("A post-start command failed, so GlassFish was stopped again"));
        assertThat(domain.isRunning(), is(false));
        assertThat(FakeDomain.calls(home), hasItem("--terse --port " + startedOn + " stop-domain domain1"));
    }

    /** Staging then fails, and the next build stages afresh, as after a failure on a brief start. */
    @Test
    void aFailedPostBootCommandStopsTheDomainAgain() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                FakeDomain.START + FakeDomain.STOP + "  *boom*) exit 1 ;;\n"));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> StartMojo.start(domain, new StagingCommands("boom", true), AUTO, false, false, TIMEOUT, log));

        assertThat(e.getMessage(), containsString("A post-boot command failed, so GlassFish was stopped again"));
        assertThat(domain.isRunning(), is(false));
    }

    @Test
    void withoutFailOnCommandErrorAFailedCommandLeavesTheDomainRunning() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                FakeDomain.START + FakeDomain.STOP + "  *boom*) exit 1 ;;\n"));
        int startedOn = StartMojo.start(domain, StagingCommands.NONE, AUTO, false, false, TIMEOUT, log);

        StartMojo.runPostStartCommands(domain, startedOn, new StagingCommands("boom", false), log);

        assertThat(domain.isRunning(), is(true));
    }

    @Test
    void debugAndSuspendReachStartDomain() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START));

        StartMojo.start(domain, StagingCommands.NONE, AUTO, true, true, TIMEOUT, log);

        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 --debug --suspend domain1")));
        assertThat(log.infos, hasItem("Waiting up to 30 seconds for a debugger to attach on 9009"));
    }

    /** GlassFish's own *:9009 would let a debugger in from any machine that reaches this one. */
    @Test
    void aDebuggerListensOnLocalhost() throws Exception {
        Path domainXml = FakeDomain.install(home, freePort(), FakeDomain.START)
                .resolve("glassfish/domains/domain1/config/domain.xml");
        Files.writeString(domainXml, Files.readString(domainXml).replace("address=9009", "address=*:9009"));
        GlassFishDomain domain = new GlassFishDomain(home);

        StartMojo.start(domain, StagingCommands.NONE, AUTO, true, false, TIMEOUT, log);

        assertThat(domain.debugAddress(), equalTo("localhost:9009"));
    }

    @Test
    void aDebuggerKeepsTheHostItWasGiven() throws Exception {
        Path domainXml = FakeDomain.install(home, freePort(), FakeDomain.START)
                .resolve("glassfish/domains/domain1/config/domain.xml");
        Files.writeString(domainXml, Files.readString(domainXml).replace("address=9009", "address=10.0.0.5:9009"));
        GlassFishDomain domain = new GlassFishDomain(home);

        StartMojo.start(domain, StagingCommands.NONE, AUTO, true, false, TIMEOUT, log);

        assertThat(domain.debugAddress(), equalTo("10.0.0.5:9009"));
    }

    @Test
    void startHasNoDebuggerByDefault() {
        assertThat(new StartMojo().debug(), is(false));
    }

    /**
     * Like a server suspended until a debugger attaches, which nobody does:
     * start-domain waits, and the server it started has no pid file yet, so
     * stop-domain couldn't find it. Start kills what it started.
     */
    @Test
    void aStartThatTimesOutLeavesNoServerBehind() throws Exception {
        Path server = home.resolve("suspended-server.pid");
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                "  *start-domain*) sleep 120 >/dev/null 2>&1 & echo $! > '" + server + "'; wait ;;\n"));

        assertThrows(MojoExecutionException.class,
                () -> StartMojo.start(domain, StagingCommands.NONE, AUTO, true, true, Duration.ofSeconds(2), log));

        long pid = Long.parseLong(Files.readString(server).trim());
        Optional<ProcessHandle> left = ProcessHandle.of(pid);
        if (left.isPresent()) {
            // Killed, it ends at once, though the system may list it a moment longer.
            left.get().onExit().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopStopsTheDomainThatStartStarted() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(
                FakeDomain.install(home, freePort(), FakeDomain.START + FakeDomain.STOP));
        StartMojo.start(domain, StagingCommands.NONE, AUTO, false, false, TIMEOUT, log);

        StopMojo.stop(domain, log);

        assertThat(domain.isRunning(), is(false));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
