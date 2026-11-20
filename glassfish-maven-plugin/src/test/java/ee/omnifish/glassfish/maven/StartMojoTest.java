/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
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

import ee.omnifish.glassfish.maven.staging.FakeDomain;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;
import ee.omnifish.glassfish.maven.staging.StagingCommands;

/** {@code start} and {@code stop} on an install whose {@code asadmin} is a shell script. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
class StartMojoTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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

        int startedOn = StartMojo.start(domain, StagingCommands.NONE, false, false, TIMEOUT, log);
        StartMojo.runPostStartCommands(domain, startedOn,
                new StagingCommands("create-file-user --groups g joe", true), log);

        assertThat(startedOn, equalTo(adminPort));
        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse --port " + adminPort + " create-file-user --groups g joe")));
    }

    /** Staging's post-boot commands, when it has them to run, run on the start itself, not on a brief one. */
    @Test
    void startRunsThePostBootCommandsItIsGiven() throws Exception {
        int adminPort = freePort();
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, adminPort, FakeDomain.START));

        StartMojo.start(domain, new StagingCommands("create-file-user --groups g joe", true), false, false, TIMEOUT,
                log);

        assertThat(domain.isRunning(), is(true));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse --port " + adminPort + " create-file-user --groups g joe")));
    }

    /** It might be another domain on the same port, so start leaves it alone. */
    @Test
    void startFailsBeforeStartingAnythingWhenTheAdminPortAnswers() throws Exception {
        try (ServerSocket otherServer = new ServerSocket(0)) {
            GlassFishDomain domain = new GlassFishDomain(
                    FakeDomain.install(home, otherServer.getLocalPort(), FakeDomain.START));

            MojoExecutionException e = assertThrows(MojoExecutionException.class,
                    () -> StartMojo.start(domain, StagingCommands.NONE, false, false, TIMEOUT, log));

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

    /** Otherwise a failure in pre-integration-test leaves the server running: Maven never gets to stop. */
    @Test
    void aFailedPostStartCommandStopsTheDomainAgain() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                FakeDomain.START + FakeDomain.STOP + "  *boom*) exit 1 ;;\n"));
        int startedOn = StartMojo.start(domain, StagingCommands.NONE, false, false, TIMEOUT, log);

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> StartMojo.runPostStartCommands(domain, startedOn, new StagingCommands("boom", true), log));

        assertThat(e.getMessage(), containsString("A post-start command failed, so GlassFish was stopped again"));
        assertThat(domain.isRunning(), is(false));
        assertThat(FakeDomain.calls(home), hasItem("--terse stop-domain domain1"));
    }

    /** Staging then fails, and the next build stages afresh, as after a failure on a brief start. */
    @Test
    void aFailedPostBootCommandStopsTheDomainAgain() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                FakeDomain.START + FakeDomain.STOP + "  *boom*) exit 1 ;;\n"));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> StartMojo.start(domain, new StagingCommands("boom", true), false, false, TIMEOUT, log));

        assertThat(e.getMessage(), containsString("A post-boot command failed, so GlassFish was stopped again"));
        assertThat(domain.isRunning(), is(false));
    }

    @Test
    void withoutFailOnCommandErrorAFailedCommandLeavesTheDomainRunning() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                FakeDomain.START + FakeDomain.STOP + "  *boom*) exit 1 ;;\n"));
        int startedOn = StartMojo.start(domain, StagingCommands.NONE, false, false, TIMEOUT, log);

        StartMojo.runPostStartCommands(domain, startedOn, new StagingCommands("boom", false), log);

        assertThat(domain.isRunning(), is(true));
    }

    @Test
    void debugAndSuspendReachStartDomain() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START));

        StartMojo.start(domain, StagingCommands.NONE, true, true, TIMEOUT, log);

        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 --debug --suspend domain1")));
        assertThat(log.infos, hasItem("Waiting up to 30 seconds for a debugger to attach on 9009"));
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
                () -> StartMojo.start(domain, StagingCommands.NONE, true, true, Duration.ofSeconds(2), log));

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
        StartMojo.start(domain, StagingCommands.NONE, false, false, TIMEOUT, log);

        StopMojo.stop(domain, log);

        assertThat(domain.isRunning(), is(false));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
