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

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.apache.maven.plugin.logging.Log;

import ee.omnifish.glassfish.maven.RunningDomain.Ending;

/**
 * The one ending of a run, however it ends: from a shutdown hook on plain
 * Maven's Ctrl+C, after mvnd's Ctrl+C interrupts the wait, after a failed
 * start or deploy, or once the server's process ended otherwise. Shared by
 * {@link RunningDomain} and {@link EmbeddedRun}.
 */
final class RunEnding {

    private final Ending ending;
    private final Runnable afterwards;
    private final Log log;
    private final Thread shutdownHook;

    private boolean ended;

    /**
     * Ends the run with {@code ending}, then {@code afterwards}, when the JVM
     * exits.
     *
     * @param goal the goal that runs, which names the shutdown hook's thread
     */
    RunEnding(String goal, Log log, Ending ending, Runnable afterwards) {
        this.ending = ending;
        this.afterwards = afterwards;
        this.log = log;
        shutdownHook = new Thread(this::end, goal + " ending");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Wait until {@code process} exits, or this thread is interrupted, then
     * end the run. The interrupt is restored once the run has ended: an
     * ending that runs a process would give up at once otherwise.
     */
    void untilExit(Optional<ProcessHandle> process) {
        boolean interrupted = false;
        try {
            if (process.isPresent()) {
                process.get().onExit().get();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (ExecutionException e) {
            // onExit() doesn't fail.
            throw new IllegalStateException(e);
        } finally {
            close();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Run the ending, then {@code afterwards}. A second call waits until the
     * first has finished: during a shutdown, the JVM exits once its hooks
     * return.
     */
    synchronized void end() {
        if (ended) {
            return;
        }

        ended = true;
        try {
            ending.end();
        } catch (IOException e) {
            log.warn(e.getMessage());
        } finally {
            afterwards.run();
        }
    }

    /** {@link #end}, and drop the shutdown hook, which has nothing left to do. */
    void close() {
        end();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is shutting down, and the hook ran the same ending.
        }
    }
}
