/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddedProcessesTest {

    private static final Path STAGE = Path.of(File.separator + "build", "target", "dist").toAbsolutePath();
    private static final List<Path> DIRS = List.of(STAGE);

    @TempDir
    Path tmp;

    /** Also where the system doesn't say what a process runs: this test's JVM runs nothing from tmp. */
    @Test
    void theChildTheStageDirectoryNamesRunsFromIt() throws Exception {
        EmbeddedProcesses.record(tmp, ProcessHandle.current());

        assertThat(EmbeddedProcesses.runningFrom(tmp), equalTo(Optional.of(ProcessHandle.current())));
    }

    @Test
    void aNamedChildThatEndedDoesntRun() throws Exception {
        Process ended = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-version")
                .start();
        ended.waitFor();
        Files.writeString(tmp.resolve(EmbeddedProcesses.PID_FILE), String.valueOf(ended.pid()));

        assertThat(EmbeddedProcesses.runningFrom(tmp), equalTo(Optional.empty()));
    }

    /** A process that got the pid of the child later isn't it. */
    @Test
    void aProcessThatStartedAtAnotherTimeIsntTheNamedChild() throws Exception {
        Files.writeString(tmp.resolve(EmbeddedProcesses.PID_FILE), ProcessHandle.current().pid() + " 0");

        assertThat(EmbeddedProcesses.runningFrom(tmp), equalTo(Optional.empty()));
    }

    @Test
    void forgettingAChildLeavesAnothersName() throws Exception {
        EmbeddedProcesses.record(tmp, ProcessHandle.current());

        EmbeddedProcesses.forget(tmp, ProcessHandle.current().parent().orElseThrow());
        assertThat(Files.exists(tmp.resolve(EmbeddedProcesses.PID_FILE)), is(true));

        EmbeddedProcesses.forget(tmp, ProcessHandle.current());
        assertThat(Files.exists(tmp.resolve(EmbeddedProcesses.PID_FILE)), is(false));
    }

    @Test
    void theJarsChildRunsFromTheStageDirectory() {
        assertThat(EmbeddedProcesses.runsFrom("java -agentlib:jdwp=… -jar " + STAGE.resolve(
                "glassfish-embedded-all-8.0.5.jar") + " --httpPort=8080 --noInfo=true deploy shop.war", DIRS), is(true));
    }

    @Test
    void theStaticShellsChildRunsFromTheStageDirectory() {
        assertThat(EmbeddedProcesses.runsFrom("java --add-opens=java.base/java.lang=ALL-UNNAMED -cp "
                + STAGE.resolve("glassfish8/glassfish/lib/embedded/glassfish-embedded-static-shell.jar")
                + " org.glassfish.runnablejar.UberMain --domainDir=domain", DIRS), is(true));
    }

    @Test
    void othersDontRunGlassFishEmbeddedFromIt() {
        Path sibling = Path.of(STAGE + "2");

        assertThat(EmbeddedProcesses.runsFrom("java -jar " + sibling.resolve("embedded.jar"), DIRS), is(false));
        assertThat(EmbeddedProcesses.runsFrom("java -Dcom.sun.aas.instanceRoot="
                + STAGE.resolve("glassfish8/glassfish/domains/domain1")
                + " com.sun.enterprise.glassfish.bootstrap.ASMain", DIRS), is(false));
    }
}
