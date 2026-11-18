/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
class GlassFishDomainTest {

    @TempDir
    Path home;

    @AfterEach
    void killServer() {
        FakeDomain.killServer(home);
    }

    @Test
    void aDomainRunsWhileItsPidFileNamesALiveProcess() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START));
        assertThat(domain.isRunning(), is(false));

        domain.start(false, false, Duration.ofSeconds(30));

        assertThat(domain.isRunning(), is(true));
    }

    /** As after a server was killed: GlassFish only removes its pid file when it stops cleanly. */
    @Test
    void aStalePidFileDoesNotCount() throws Exception {
        FakeDomain.install(home, freePort(), "");
        Process ended = new ProcessBuilder("true").start();
        ended.waitFor();
        Files.writeString(home.resolve(GlassFishStaging.DOMAIN_CONFIG).resolve("pid"), String.valueOf(ended.pid()));

        assertThat(new GlassFishDomain(home).isRunning(), is(false));
    }

    /** Also when another server answers on its admin port: that's not this domain. */
    @Test
    void aDomainThatDoesNotRunIsLeftAlone() throws Exception {
        try (ServerSocket otherServer = new ServerSocket(0)) {
            GlassFishDomain domain = new GlassFishDomain(
                    FakeDomain.install(home, otherServer.getLocalPort(), FakeDomain.STOP));

            assertThat(domain.stop(), is(false));
            assertThat(FakeDomain.calls(home), is(empty()));
        }
    }

    @Test
    void stopDomainStopsIt() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(
                FakeDomain.install(home, freePort(), FakeDomain.START + FakeDomain.STOP));
        domain.start(false, false, Duration.ofSeconds(30));

        assertThat(domain.stop(), is(true));

        assertThat(domain.isRunning(), is(false));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain domain1",
                "--terse stop-domain domain1")));
    }

    @Test
    void aDomainThatKeepsRunningIsKilled() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START
                + "  *--kill*) kill -9 $(cat \"$PID\") ;;\n"), Duration.ofSeconds(2));
        domain.start(false, false, Duration.ofSeconds(30));

        assertThat(domain.stop(), is(true));

        assertThat(domain.isRunning(), is(false));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain domain1",
                "--terse stop-domain domain1", "--terse stop-domain --kill domain1")));
    }

    @Test
    void aDomainThatSurvivesTheKillFailsTheStop() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START),
                Duration.ofSeconds(1));
        domain.start(false, false, Duration.ofSeconds(30));

        IOException e = assertThrows(IOException.class, domain::stop);

        assertThat(e.getMessage(), containsString("still runs"));
    }

    @Test
    void theDebugAddressComesFromTheDomain() throws Exception {
        assertThat(new GlassFishDomain(FakeDomain.install(home, freePort(), "")).debugAddress(), equalTo("9009"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
