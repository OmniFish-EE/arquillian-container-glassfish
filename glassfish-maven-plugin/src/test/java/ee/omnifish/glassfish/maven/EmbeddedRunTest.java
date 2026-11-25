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
package ee.omnifish.glassfish.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.maven.staging.EmbeddedGlassFish;
import ee.omnifish.glassfish.maven.staging.EmbeddedWorkDir;

/**
 * {@link EmbeddedRun} with a stand-in child, which prints the recorded output
 * of GlassFish Embedded 8.0.5, then idles or exits.
 */
class EmbeddedRunTest {

    private static final Duration START = Duration.ofSeconds(60);

    @TempDir
    Path tmp;

    private final RecordingLog log = new RecordingLog();

    @Test
    void aStartedChildIsStoppedWhenTheRunEnds() throws Exception {
        EmbeddedWorkDir workDir = workDir();
        EmbeddedRun run = start(workDir, "war-messages.log", "idle", true);

        run.awaitStarted(START);
        assertThat(run.name(), equalTo("shop"));
        assertThat(run.contextRoots(), contains("/descriptor-shop"));
        run.close();

        assertThat(run.process().isAlive(), is(false));
        assertThat(Files.exists(workDir.dir()), is(false));
        assertThat(log.infos, hasItem("GlassFish Embedded stopped"));
    }

    @Test
    void aChildThatEndsBeforeItStartedFailsWithItsExitCode() throws Exception {
        EmbeddedWorkDir workDir = workDir();
        EmbeddedRun run = start(workDir, "", "1", true);

        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> run.awaitStarted(START));

        assertThat(e.getMessage(), equalTo("GlassFish Embedded ended with exit code 1 before it started"));
        assertThat(Files.exists(workDir.dir()), is(false));
    }

    @Test
    void aChildThatDoesntStartInTimeIsStopped() throws Exception {
        EmbeddedRun run = start(workDir(), "", "idle", true);

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> run.awaitStarted(Duration.ofSeconds(2)));

        assertThat(e.getMessage(), equalTo("GlassFish Embedded didn't start within 2 seconds"));
        assertThat(run.process().isAlive(), is(false));
    }

    @Test
    void aChildThatEndsByItselfEndsTheRun() throws Exception {
        EmbeddedWorkDir workDir = workDir();
        EmbeddedRun run = start(workDir, "ejb-default.log", "5", false);
        run.awaitStarted(START);

        run.untilStopped();

        assertThat(log.warnings, contains("GlassFish Embedded ended with exit code 5"));
        assertThat(Files.exists(workDir.dir()), is(false));
    }

    /** As mvnd's Ctrl+C does. */
    @Test
    void anInterruptEndsTheRunAndStaysSet() throws Exception {
        EmbeddedRun run = start(workDir(), "ear-messages.log", "idle", false);
        run.awaitStarted(START);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waiting = new Thread(() -> {
            run.untilStopped();
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        waiting.start();
        waiting.interrupt();
        waiting.join(START.toMillis());

        assertThat(run.process().isAlive(), is(false));
        assertThat(interrupted.get(), is(true));
        assertThat(log.infos, hasItem("GlassFish Embedded stopped"));
    }

    private EmbeddedWorkDir workDir() throws IOException {
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");
        return EmbeddedWorkDir.create(tmp.resolve("work"), archive,
                EmbeddedGlassFish.jar(tmp.resolve("embedded.jar"), null, null));
    }

    /**
     * A stand-in child, which prints {@code recording}, if not empty, then
     * idles with {@code "idle"}, or exits with the code {@code then} gives.
     */
    private EmbeddedRun start(EmbeddedWorkDir workDir, String recording, String then, boolean war) throws Exception {
        Path child = Files.writeString(tmp.resolve("Child.java"), "import java.nio.file.*;\n"
                + "class Child {\n"
                + "    public static void main(String[] args) throws Exception {\n"
                + "        if (!args[0].isEmpty()) {\n"
                + "            Files.readAllLines(Path.of(args[0])).forEach(System.out::println);\n"
                + "        }\n"
                + "        if (args[1].equals(\"idle\")) {\n"
                + "            Thread.sleep(120_000);\n"
                + "        }\n"
                + "        System.exit(Integer.parseInt(args[1]));\n"
                + "    }\n"
                + "}\n");
        String recorded = recording.isEmpty() ? ""
                : Path.of(EmbeddedRunTest.class.getResource("/embedded/" + recording).toURI()).toString();
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                child.toString(), recorded, then);

        return EmbeddedRun.start(command, workDir, new EmbeddedStartup(war), "[child] ", "test", log);
    }
}
