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
import java.util.function.BooleanSupplier;

import org.apache.maven.plugin.logging.Log;

import ee.omnifish.glassfish.maven.RunningDomain.Ending;

/**
 * The one ending of a run, however it ends: from a shutdown hook on plain
 * Maven's Ctrl+C, after mvnd's Ctrl+C interrupts the wait, after a failed
 * start or deploy, once the server's process ended otherwise, or once
 * {@code glassfish-pool:down} asks a run that hosts GlassFish to stop.
 * Shared by {@link RunningDomain}, {@link EmbeddedRun} and the pool plugin's
 * run in Maven's JVM.
 */
public final class RunEnding {

    /** How often {@link #untilRequested} checks whether the run should stop. */
    private static final long POLL_MILLIS = 500;

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
    public RunEnding(String goal, Log log, Ending ending, Runnable afterwards) {
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
     * Wait until {@code stopRequested} says so, as it's asked now and then,
     * or this thread is interrupted, then end the run. On Ctrl+C, the
     * shutdown hook ends the run meanwhile, and the JVM exits once it has.
     */
    public void untilRequested(BooleanSupplier stopRequested) {
        boolean interrupted = false;
        try {
            while (!hasEnded() && !stopRequested.getAsBoolean()) {
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            close();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Whether the run has ended; while it ends, this waits until it has. */
    public synchronized boolean hasEnded() {
        return ended;
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
    public void close() {
        end();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is shutting down, and the hook ran the same ending.
        }
    }
}
