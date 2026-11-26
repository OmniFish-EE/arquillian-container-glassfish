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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import ee.omnifish.glassfish.maven.staging.EmbeddedWorkDir;

/**
 * A GlassFish Embedded child that runs the application, with its output on
 * the console, and the one ending of the run, however it ends: from a
 * shutdown hook on plain Maven's Ctrl+C, after mvnd's Ctrl+C interrupts the
 * wait, after a failed start, or once the child ended otherwise. The ending
 * stops the child, then removes its working directory.
 */
final class EmbeddedRun implements AutoCloseable {

    /** How long the child gets to stop before it's killed. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    /** Exit codes of a child that was stopped, by Ctrl+C's SIGINT or a SIGTERM, rather than failed. */
    private static final List<Integer> STOPPED = List.of(0, 130, 143);

    private final Process process;
    private final EmbeddedStartup startup;
    private final Log log;
    private final Thread output;
    private final RunEnding ending;

    private volatile boolean stopping;

    private EmbeddedRun(Process process, EmbeddedStartup startup, String prefix, EmbeddedWorkDir workDir, String goal,
            Log log) {
        this.process = process;
        this.startup = startup;
        this.log = log;
        output = new Thread(() -> pump(prefix), goal + " output");
        output.setDaemon(true);
        output.start();
        ending = new RunEnding(goal, log, this::stop, workDir::close);
    }

    /**
     * Start {@code command} in {@code workDir}, printing each line of its
     * output after {@code prefix}, and end the run when the JVM exits.
     *
     * @param goal the goal that runs, which names the shutdown hook's thread
     */
    static EmbeddedRun start(List<String> command, EmbeddedWorkDir workDir, EmbeddedStartup startup, String prefix,
            String goal, Log log) throws MojoExecutionException {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(workDir.dir().toFile())
                    .redirectErrorStream(true)
                    .start();
            // So staging leaves what it runs from alone.
            workDir.runs(process.toHandle());
            // GlassFish Embedded reads nothing without --prompt.
            process.getOutputStream().close();
        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            workDir.close();
            throw new MojoExecutionException("Starting GlassFish Embedded failed: " + e.getMessage(), e);
        }

        return new EmbeddedRun(process, startup, prefix, workDir, goal, log);
    }

    private void pump(String prefix) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = lines.readLine()) != null) {
                System.out.println(prefix + line);
                synchronized (startup) {
                    startup.accept(line);
                    startup.notifyAll();
                }
            }
        } catch (IOException e) {
            // The child's output closed with the child.
        } finally {
            synchronized (startup) {
                startup.notifyAll();
            }
        }
    }

    /**
     * Wait until the child has deployed the application, for up to
     * {@code timeout}. When it ends first, fails, or doesn't start in time,
     * the run ends, and this fails.
     */
    void awaitStarted(Duration timeout) throws MojoExecutionException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String failure;
        try {
            synchronized (startup) {
                while (!startup.isStarted() && startup.failure() == null && output.isAlive()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(startup, left);
                }
                if (startup.isStarted()) {
                    return;
                }
                failure = startup.failure();
            }
            if (failure == null && !output.isAlive()) {
                failure = "GlassFish Embedded ended with exit code " + process.waitFor() + " before it started";
            }
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while GlassFish Embedded started", e);
        }

        close();
        throw new MojoExecutionException((failure != null) ? failure
                : "GlassFish Embedded didn't start within " + timeout.toSeconds() + " seconds");
    }

    /** The name GlassFish deployed the application under. */
    String name() {
        return startup.name();
    }

    /** A WAR's context root; empty for other archives. */
    List<String> contextRoots() {
        return startup.contextRoots();
    }

    /**
     * Wait until the child ends, or this thread is interrupted, then end the
     * run. A child that ended by itself is reported, with its exit code
     * unless it's a stopped one's.
     */
    void untilStopped() {
        ending.untilExit(Optional.of(process.toHandle()));
        if (stopping || process.isAlive()) {
            return;
        }

        int exitCode = process.exitValue();
        if (STOPPED.contains(exitCode)) {
            log.info("GlassFish Embedded ended");
        } else {
            log.warn("GlassFish Embedded ended with exit code " + exitCode);
        }
    }

    /** Stop the child: a SIGTERM, then, if it hasn't ended within {@link #STOP_TIMEOUT}, a kill. */
    private void stop() throws IOException {
        if (!process.isAlive()) {
            return;
        }

        stopping = true;
        process.destroy();
        try {
            if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
            // Its last lines, before what the run logs next.
            output.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while GlassFish Embedded stopped", e);
        }
        log.info("GlassFish Embedded stopped");
    }

    /** End the run, if it hasn't ended, and drop the shutdown hook. */
    @Override
    public void close() {
        ending.close();
    }

    /** Package-private for unit tests. */
    Process process() {
        return process;
    }
}
