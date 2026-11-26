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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The GlassFish Embedded children {@code glassfish:run} starts, found by the
 * file that names the one that runs from a stage directory, as
 * {@code config/pid} names {@code domain1}'s server, or by their command
 * lines: {@code -jar} with the JAR staged in a stage directory, or Embedded's
 * main class with the static shell of an install there. Where the system
 * doesn't say what a process runs, as on Windows, only the file finds one.
 */
final class EmbeddedProcesses {

    /** GlassFish Embedded's own main class, which the static shell runs. */
    static final String MAIN_CLASS = "org.glassfish.runnablejar.UberMain";

    /** The file in a stage directory that names the child that runs from it, by its pid and start. */
    static final String PID_FILE = "glassfish-embedded.pid";

    private EmbeddedProcesses() {
    }

    /**
     * Name {@code child} as the one that runs from {@code stageDir}.
     *
     * @throws IOException if the name of another child that still runs is
     *         there, which staging would no longer find without it, as on
     *         Windows
     */
    static void record(Path stageDir, ProcessHandle child) throws IOException {
        Optional<ProcessHandle> running = recorded(stageDir);
        if (running.isPresent() && !running.get().equals(child)) {
            throw new IOException("GlassFish Embedded already runs from " + stageDir + ", for another glassfish:run. "
                    + "End that one first.");
        }

        Files.writeString(stageDir.resolve(PID_FILE),
                child.pid() + child.info().startInstant().map(start -> " " + start.toEpochMilli()).orElse(""));
    }

    /** Remove the name of {@code child}, unless another child's replaced it. */
    static void forget(Path stageDir, ProcessHandle child) throws IOException {
        Path file = stageDir.resolve(PID_FILE);
        if (Files.exists(file) && Files.readString(file).split(" ")[0].equals(String.valueOf(child.pid()))) {
            Files.delete(file);
        }
    }

    /** A GlassFish Embedded child that runs from {@code stageDir}, if there is one. */
    static Optional<ProcessHandle> runningFrom(Path stageDir) {
        Optional<ProcessHandle> recorded = recorded(stageDir);
        if (recorded.isPresent()) {
            return recorded;
        }

        List<Path> dirs = GlassFishDomain.names(stageDir);
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine()
                        .map(commandLine -> runsFrom(commandLine, dirs))
                        .orElse(false))
                .findFirst();
    }

    /**
     * The child the {@link #PID_FILE} of {@code stageDir} names, if it runs:
     * a process with its pid that started at another time isn't it.
     */
    private static Optional<ProcessHandle> recorded(Path stageDir) {
        Path file = stageDir.resolve(PID_FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            String[] fields = Files.readString(file).trim().split(" ");
            Optional<ProcessHandle> child = ProcessHandle.of(Long.parseLong(fields[0])).filter(ProcessHandle::isAlive);
            if (fields.length > 1) {
                long start = Long.parseLong(fields[1]);
                child = child.filter(process -> process.info().startInstant().map(Instant::toEpochMilli)
                        .map(started -> started == start).orElse(true));
            }
            return child;
        } catch (IOException | NumberFormatException e) {
            // Gone meanwhile, or not written by glassfish:run.
            return Optional.empty();
        }
    }

    /** Whether {@code commandLine} runs GlassFish Embedded from one of {@code dirs}. Package-private for unit tests. */
    static boolean runsFrom(String commandLine, List<Path> dirs) {
        for (Path dir : dirs) {
            String within = dir + File.separator;
            if (commandLine.contains("-jar " + within)
                    || (commandLine.contains(MAIN_CLASS) && commandLine.contains(within))) {
                return true;
            }
        }

        return false;
    }
}
