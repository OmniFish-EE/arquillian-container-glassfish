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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The GlassFish Embedded children {@code glassfish:run} starts, found by their
 * command lines: {@code -jar} with the JAR staged in a stage directory, or
 * Embedded's main class with the static shell of an install there. Where the
 * system doesn't say what a process runs, as on Windows, none is found; there,
 * a running child keeps its JARs open, so they can't be deleted either.
 */
final class EmbeddedProcesses {

    /** GlassFish Embedded's own main class, which the static shell runs. */
    static final String MAIN_CLASS = "org.glassfish.runnablejar.UberMain";

    private EmbeddedProcesses() {
    }

    /** A GlassFish Embedded child that runs from {@code stageDir}, if there is one. */
    static Optional<ProcessHandle> runningFrom(Path stageDir) {
        List<Path> dirs = GlassFishDomain.names(stageDir);
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine()
                        .map(commandLine -> runsFrom(commandLine, dirs))
                        .orElse(false))
                .findFirst();
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
