/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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

    /** As after a server was killed, and the system gave its number to another process since. */
    @Test
    void aPidFileThatNamesAnotherProcessDoesNotCount() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.STOP));
        Files.writeString(home.resolve(GlassFishStaging.DOMAIN_CONFIG).resolve("pid"),
                String.valueOf(ProcessHandle.current().pid()));

        assertThat(domain.isRunning(), is(false));
        assertThat(domain.stop(), is(false));
        assertThat(FakeDomain.calls(home), is(empty()));
    }

    /** Where the system doesn't say what a process runs, as on Windows: by when it started. */
    @Test
    void withoutItsCommandLineAProcessThatStartedAfterThePidFileWasWrittenIsAnothers() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), ""));
        Path pidFile = Files.writeString(home.resolve(GlassFishStaging.DOMAIN_CONFIG).resolve("pid"), "1");
        Instant written = Files.getLastModifiedTime(pidFile).toInstant();

        assertThat(domain.isThisDomains(startedAt(written.minusSeconds(5)), pidFile), is(true));
        assertThat(domain.isThisDomains(startedAt(written.plusSeconds(5)), pidFile), is(false));
    }

    /** A system property can set the admin port; GlassFish starts with it all the same. */
    @Test
    void theAdminPortMayComeFromASystemProperty() throws Exception {
        FakeDomain.install(home, 0, "");
        Path domainXml = home.resolve(GlassFishStaging.DOMAIN_CONFIG).resolve("domain.xml");
        Files.writeString(domainXml, Files.readString(domainXml)
                .replace("port=\"0\"", "port=\"${ADMIN_PORT}\"")
                .replace("<domain>", "<domain><servers><server name=\"server\" config-ref=\"server-config\">"
                        + "<system-property name=\"ADMIN_PORT\" value=\"24848\"/></server></servers>"));

        assertThat(new GlassFishDomain(home).adminPort(), equalTo(24848));
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
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
                "--terse stop-domain domain1")));
    }

    /** As after mvn clean deleted the install it runs from: config/pid and asadmin are gone with it. */
    @Test
    void aDomainWhoseInstallIsGoneIsStoppedByItsCommandLine() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START));
        domain.start(false, false, Duration.ofSeconds(30));
        ProcessHandle server = domain.process().orElseThrow();
        deleteInstall(home);

        assertThat(domain.process().map(ProcessHandle::pid), equalTo(Optional.of(server.pid())));
        assertThat(domain.stop(), is(true));
        assertThat(server.isAlive(), is(false));
    }

    /** As on GlassFish 7.0, which reads its timeout from AS_START_TIMEOUT instead, in milliseconds. */
    @Test
    void startDomainRunsWithoutTheTimeoutOptionWhereGlassFishDoesNotHaveIt() throws Exception {
        Path asadminTimeout = home.resolve("as-start-timeout.txt");
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(),
                "  *--timeout*) echo 'Invalid option: --timeout'; exit 1 ;;\n"
                + "  *start-domain*) echo \"$AS_START_TIMEOUT\" > '" + asadminTimeout + "' ;;\n"));

        domain.start(true, false, Duration.ofSeconds(30));

        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 --debug domain1",
                "--terse start-domain --debug domain1")));
        assertThat(Files.readString(asadminTimeout).trim(), equalTo("90000"));
    }

    /** Whichever distribution was unpacked there, also after mvn clean deleted it. */
    @Test
    void aServerOfAnyInstallInTheStageDirectoryIsFound() throws Exception {
        Path install = home.resolve("glassfish7");
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(install, freePort(), FakeDomain.START));
        domain.start(false, false, Duration.ofSeconds(30));
        try {
            deleteInstall(install);

            assertThat(GlassFishDomain.runningIn(home).map(GlassFishDomain::home), equalTo(Optional.of(install)));
            assertThat(GlassFishDomain.runningIn(home.resolve("glassfish7")), equalTo(Optional.empty()));
        } finally {
            domain.process().ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void aDomainThatKeepsRunningIsKilled() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, freePort(), FakeDomain.START
                + "  *--kill*) kill -9 $(cat \"$PID\") ;;\n"), Duration.ofSeconds(2));
        domain.start(false, false, Duration.ofSeconds(30));

        assertThat(domain.stop(), is(true));

        assertThat(domain.isRunning(), is(false));
        assertThat(FakeDomain.calls(home), equalTo(List.of("--terse start-domain --timeout 90 domain1",
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

    /** GlassFish moves these only on a restart, without reporting that; an HTTP listener moves at once. */
    @Test
    void aCommandThatMovesTheAdminJmxOrIiopListenerNeedsARestartGlassFishDoesNotReport() {
        String config = "set configs.config.server-config.";

        assertThat(GlassFishDomain.unreportedRestart(config + "network-config.network-listeners.network-listener"
                + ".admin-listener.port=4949", ""), containsString(" changes the admin listener"));
        assertThat(GlassFishDomain.unreportedRestart(config + "admin-service.jmx-connector.system.port=8787", ""),
                containsString(" changes the JMX connector"));
        assertThat(GlassFishDomain.unreportedRestart(config + "iiop-service.iiop-listener.orb-listener-1.port=3800",
                ""), containsString(" changes an IIOP listener"));
        assertThat(GlassFishDomain.unreportedRestart(config + "network-config.network-listeners.network-listener"
                + ".http-listener-1.port=8282", ""), is(nullValue()));
    }

    /** GlassFish sets the property at once, but a JVM option that refers to it keeps the old value until a restart. */
    @Test
    void aSystemPropertyThatDomainXmlRefersToNeedsARestartGlassFishDoesNotReport() {
        String domainXml = "<jvm-options>-Dderived=${NAME}</jvm-options>";

        assertThat(GlassFishDomain.unreportedRestart("create-system-properties --target server A=1:NAME=x\\:y",
                domainXml), containsString(" changes NAME, which domain.xml refers to"));
        assertThat(GlassFishDomain.unreportedRestart("delete-system-property NAME", domainXml),
                containsString(" changes NAME"));
        assertThat(GlassFishDomain.unreportedRestart("create-system-properties OTHER=1", domainXml), is(nullValue()));
        // An escaped colon is part of the value.
        assertThat(GlassFishDomain.unreportedRestart("create-system-properties OTHER=a\\:NAME=b", domainXml),
                is(nullValue()));
    }

    /** A process of which the system only says when it started. */
    private static ProcessHandle.Info startedAt(Instant started) {
        return new ProcessHandle.Info() {
            @Override
            public Optional<String> command() {
                return Optional.empty();
            }

            @Override
            public Optional<String> commandLine() {
                return Optional.empty();
            }

            @Override
            public Optional<String[]> arguments() {
                return Optional.empty();
            }

            @Override
            public Optional<Instant> startInstant() {
                return Optional.of(started);
            }

            @Override
            public Optional<Duration> totalCpuDuration() {
                return Optional.empty();
            }

            @Override
            public Optional<String> user() {
                return Optional.empty();
            }
        };
    }

    private static void deleteInstall(Path install) throws IOException {
        try (Stream<Path> files = Files.walk(install.resolve("glassfish"))) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
