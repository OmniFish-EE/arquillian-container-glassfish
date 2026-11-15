/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import static ee.omnifish.glassfish.admin.DomainXmlEditor.ADMIN_LISTENER;
import static ee.omnifish.glassfish.admin.DomainXmlEditor.HTTPS_LISTENER;
import static ee.omnifish.glassfish.admin.DomainXmlEditor.HTTP_LISTENER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.admin.DomainXmlEditor;

/**
 * The brief start that runs {@link StagingCommands}, against a distribution
 * whose {@code asadmin} is a shell script that logs its calls.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
class StagingCommandsTest {

    /** Fails a command line containing {@code boom}; sets the http port for {@code set-http-port}. */
    private static final String SCRIPT = "case \"$*\" in\n"
            + "  *boom*) echo \"Remote failure: boom\"; exit 1 ;;\n"
            + "  *set-http-port*) sed -i.bak 's/name=\"http-listener-1\" port=\"[0-9]*\"/"
            + "name=\"http-listener-1\" port=\"18080\"/' \"$(dirname \"$0\")/../domains/domain1/config/domain.xml\" ;;\n"
            + "esac\n";

    @TempDir
    Path tmp;

    @Test
    void commandsRunOnABriefStartOnFreePorts() throws Exception {
        Path zip = fakeGlassFish();

        GlassFishStaging.stage(spec(commands("create-file-user --groups g u", "set a=b")), coord -> zip);

        assertThat(calls(), equalTo(List.of(
                "--terse start-domain domain1",
                "--terse --port N create-file-user --groups g u",
                "--terse --port N set a=b",
                "--terse stop-domain domain1")));
        assertThat(rawCalls().get(1), not(containsString("--port 4848")));
        assertThat(ports(), equalTo(Map.of(ADMIN_LISTENER, "4848", HTTP_LISTENER, "8080", HTTPS_LISTENER, "8181")));
    }

    @Test
    void unchangedCommandsLeaveTheStagedInstallAlone() throws Exception {
        Path zip = fakeGlassFish();
        GlassFishStaging.stage(spec(commands("set a=b")), coord -> zip);

        GlassFishStaging.stage(spec(commands("set a=b")), coord -> zip);

        assertThat(count("start-domain"), equalTo(1L));
    }

    @Test
    void changedCommandsStageAfresh() throws Exception {
        Path zip = fakeGlassFish();
        GlassFishStaging.stage(spec(commands("set a=b")), coord -> zip);
        Files.writeString(home().resolve("glassfish/left-over.txt"), "from the first staging");

        GlassFishStaging.stage(spec(commands("set a=c")), coord -> zip);

        assertThat(count("start-domain"), equalTo(2L));
        assertThat(Files.exists(home().resolve("glassfish/left-over.txt")), equalTo(false));
    }

    @Test
    void aPortACommandSetKeepsItsValue() throws Exception {
        Path zip = fakeGlassFish();

        GlassFishStaging.stage(spec(commands("set-http-port")), coord -> zip);

        assertThat(ports(), equalTo(Map.of(ADMIN_LISTENER, "4848", HTTP_LISTENER, "18080", HTTPS_LISTENER, "8181")));
    }

    @Test
    void aFailedCommandFailsTheStagingAndTheNextRunRedoesIt() throws Exception {
        Path zip = fakeGlassFish();
        StageSpec spec = spec(commands("set boom", "set after"));

        IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, coord -> zip));
        assertThat(e.getMessage(), containsString("set boom"));
        assertThat(calls(), equalTo(List.of(
                "--terse start-domain domain1",
                "--terse --port N set boom",
                "--terse stop-domain domain1")));

        assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, coord -> zip));
        assertThat(count("start-domain"), equalTo(2L));
    }

    @Test
    void withoutFailOnErrorAFailedCommandIsLoggedAndTheRestRun() throws Exception {
        Path zip = fakeGlassFish();
        StageSpec spec = spec(new StagingCommands("set boom\nset after", false));

        GlassFishStaging.stage(spec, coord -> zip);
        GlassFishStaging.stage(spec, coord -> zip);

        assertThat(calls(), equalTo(List.of(
                "--terse start-domain domain1",
                "--terse --port N set boom",
                "--terse --port N set after",
                "--terse stop-domain domain1")));
    }

    /** A build killed during the brief start leaves its server running, with its pid file. */
    @Test
    void aServerLeftRunningIsStoppedBeforeStagingAfresh() throws Exception {
        Path zip = fakeGlassFish();
        StageSpec spec = spec(commands("set boom"));
        assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, coord -> zip));
        Files.writeString(home().resolve("glassfish/domains/domain1/config/pid"), "12345");

        assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, coord -> zip));

        assertThat(calls(), equalTo(List.of(
                "--terse start-domain domain1",
                "--terse --port N set boom",
                "--terse stop-domain domain1",
                "--terse stop-domain domain1",
                "--terse start-domain domain1",
                "--terse --port N set boom",
                "--terse stop-domain domain1")));
    }

    /** Wiping the install of a server that still runs would pull its files from under it. */
    @Test
    void aLeftoverServerThatDoesntStopKeepsItsInstall() throws Exception {
        Path zip = fakeGlassFish();
        StageSpec spec = spec(commands("set boom"));
        assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, coord -> zip));
        Path pid = Files.writeString(home().resolve("glassfish/domains/domain1/config/pid"), "12345");
        int briefAdminPort = Integer.parseInt(ports().get(ADMIN_LISTENER));

        try (ServerSocket stillRunning = new ServerSocket(briefAdminPort)) {
            IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, coord -> zip));

            assertThat(e.getMessage(), containsString("Stop it, then build again"));
        }
        assertThat(Files.exists(pid), equalTo(true));
        assertThat(count("start-domain"), equalTo(1L));
    }

    /** A command that failed while failures were only logged must run again once they fail the build. */
    @Test
    void switchingOnFailOnErrorStagesAfresh() throws Exception {
        Path zip = fakeGlassFish();
        GlassFishStaging.stage(spec(new StagingCommands("set boom", false)), coord -> zip);

        IOException e = assertThrows(IOException.class,
                () -> GlassFishStaging.stage(spec(new StagingCommands("set boom", true)), coord -> zip));

        assertThat(e.getMessage(), containsString("set boom"));
    }

    private StageSpec spec(StagingCommands commands) {
        Path stage = tmp.resolve("stage");
        DistributionCoord distribution = new DistributionCoord();
        distribution.setVersion("9.0.0");
        return new StageSpec(stage, distribution, false, home(), null, List.of(), false, CertImport.NONE, commands);
    }

    private static StagingCommands commands(String... lines) {
        return new StagingCommands(String.join("\n", lines), true);
    }

    private Path home() {
        return tmp.resolve("stage/glassfish9");
    }

    private Map<String, String> ports() throws IOException {
        return DomainXmlEditor.ports(home().resolve("glassfish/domains/domain1/config/domain.xml"));
    }

    /**
     * A distribution whose {@code asadmin} appends its arguments to
     * {@code calls.log} outside the stage directory, so the log outlives a
     * fresh staging, then runs {@link #SCRIPT}. Its domain has the three
     * listeners, in a {@code server-config}.
     */
    private Path fakeGlassFish() throws IOException {
        Path zip = tmp.resolve("glassfish.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(Files.newOutputStream(zip))) {
            ZipArchiveEntry asadmin = new ZipArchiveEntry("glassfish9/glassfish/bin/asadmin");
            asadmin.setUnixMode(0755);
            out.putArchiveEntry(asadmin);
            out.write(("#!/bin/sh\necho \"$@\" >> '" + tmp.resolve("calls.log") + "'\n" + SCRIPT).getBytes(UTF_8));
            out.closeArchiveEntry();
            out.putArchiveEntry(new ZipArchiveEntry("glassfish9/glassfish/domains/domain1/config/domain.xml"));
            out.write(("<domain><configs><config name=\"server-config\"><network-config><network-listeners>"
                    + "<network-listener name=\"admin-listener\" port=\"4848\"/>"
                    + "<network-listener name=\"http-listener-1\" port=\"8080\"/>"
                    + "<network-listener name=\"http-listener-2\" port=\"8181\"/>"
                    + "</network-listeners></network-config></config></configs></domain>").getBytes(UTF_8));
            out.closeArchiveEntry();
        }
        return zip;
    }

    /** The asadmin calls so far, oldest first, as logged. */
    private List<String> rawCalls() throws IOException {
        Path log = tmp.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    /** The asadmin calls so far, with the brief start's admin port written as {@code N}. */
    private List<String> calls() throws IOException {
        return rawCalls().stream().map(call -> call.replaceAll("--port \\d+", "--port N")).collect(toList());
    }

    private long count(String command) throws IOException {
        return rawCalls().stream().filter(call -> call.contains(command)).count();
    }
}
