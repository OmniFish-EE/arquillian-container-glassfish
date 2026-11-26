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
package ee.omnifish.glassfish.admin.embedded;

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
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.admin.embedded.EmbeddedChild.StartFailedException;

class EmbeddedChildTest {

    private static final Duration START = Duration.ofSeconds(60);

    @TempDir
    Path tmp;

    private final List<String> lines = new CopyOnWriteArrayList<>();

    /** While it runs, the stage directory records it for its slot; closing ends it, and removes both. */
    @Test
    void aStartedChildIsRecordedUntilItsClosed() throws Exception {
        EmbeddedWorkDir workDir = workDir();
        EmbeddedChild child = start(workDir, recording("war-messages.log"), "idle", tmp.resolve("pool/slot-1"));

        child.awaitStarted(START);
        assertThat(child.name(), equalTo("shop"));
        assertThat(child.contextRoots(), contains("/descriptor-shop"));
        assertThat(EmbeddedProcesses.serving(tmp, tmp.resolve("pool/slot-1")), contains(child.handle()));
        assertThat(lines, hasItem("    [name=shop"));

        child.close();

        assertThat(child.isAlive(), is(false));
        assertThat(Files.exists(workDir.dir()), is(false));
        assertThat(Files.exists(EmbeddedProcesses.recordFile(tmp, child.handle().pid())), is(false));
    }

    /** The last lines it wrote come with the failure, for a caller that doesn't show them as they come. */
    @Test
    void aChildThatEndsBeforeItStartedFailsWithItsExitCodeAndItsLastLines() throws Exception {
        EmbeddedWorkDir workDir = workDir();
        Path output = Files.write(tmp.resolve("output.txt"), List.of("SEVERE: port 14849 taken", "exiting"));
        EmbeddedChild child = start(workDir, output.toString(), "1", null);
        long started = System.nanoTime();

        StartFailedException e = assertThrows(StartFailedException.class, () -> child.awaitStarted(START));

        // As it ends, not once the start's time is up.
        assertThat(Duration.ofNanos(System.nanoTime() - started).compareTo(START.dividedBy(2)) < 0, is(true));
        assertThat(e.getMessage(), equalTo("GlassFish Embedded ended with exit code 1 before it started"));
        assertThat(e.lastLines(), contains("SEVERE: port 14849 taken", "exiting"));
        assertThat(Files.exists(workDir.dir()), is(false));
    }

    @Test
    void aChildThatDoesntStartInTimeIsStopped() throws Exception {
        EmbeddedChild child = start(workDir(), "", "idle", null);

        StartFailedException e = assertThrows(StartFailedException.class,
                () -> child.awaitStarted(Duration.ofSeconds(2)));

        assertThat(e.getMessage(), equalTo("GlassFish Embedded didn't start within 2 seconds"));
        assertThat(child.isAlive(), is(false));
    }

    /** A child that can't start leaves no working directory behind. */
    @Test
    void aChildThatCantStartRemovesItsWorkingDirectory() throws Exception {
        EmbeddedWorkDir workDir = workDir();

        assertThrows(IOException.class, () -> EmbeddedChild.start(List.of(tmp.resolve("no-such-java").toString()),
                workDir, new EmbeddedStartup(true, List.of()), null, false, lines::add));

        assertThat(Files.exists(workDir.dir()), is(false));
    }

    private EmbeddedWorkDir workDir() throws IOException {
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");
        return EmbeddedWorkDir.create(tmp.resolve("work"), archive,
                EmbeddedGlassFish.jar(tmp, tmp.resolve("embedded.jar"), null, null));
    }

    private static String recording(String name) throws Exception {
        return Path.of(EmbeddedChildTest.class.getResource("/embedded/" + name).toURI()).toString();
    }

    /**
     * A stand-in child, which prints the lines of the file {@code output}, if
     * given, then idles with {@code "idle"}, or exits with the code
     * {@code then} gives.
     */
    private EmbeddedChild start(EmbeddedWorkDir workDir, String output, String then, Path slotDir) throws Exception {
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
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                child.toString(), output, then);

        return EmbeddedChild.start(command, workDir, new EmbeddedStartup(true, List.of()), slotDir, false, lines::add);
    }
}
