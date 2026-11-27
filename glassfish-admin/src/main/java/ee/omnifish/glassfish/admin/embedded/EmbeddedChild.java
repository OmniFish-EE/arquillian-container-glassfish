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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * A GlassFish Embedded child that runs an application: started in its
 * working directory, and recorded in the stage directory it runs from, with
 * its output passed on line by line, and followed until it has started.
 * {@link #close} ends it, then removes its record and its working directory.
 */
public final class EmbeddedChild implements AutoCloseable {

    private static final Logger LOG = System.getLogger(EmbeddedChild.class.getName());

    /** How long a child gets to stop when it's closed, before it's killed. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    /** How many of its last lines a failed start reports. */
    private static final int TAIL_LINES = 30;

    private final Process process;
    private final EmbeddedStartup startup;
    private final EmbeddedWorkDir workDir;
    private final Thread output;
    private final Deque<String> tail = new ArrayDeque<>();

    /** Whether the child's output ended, with the child. Guarded by {@link #startup}. */
    private boolean outputEnded;
    private boolean closed;

    private EmbeddedChild(Process process, EmbeddedStartup startup, EmbeddedWorkDir workDir, Consumer<String> lines) {
        this.process = process;
        this.startup = startup;
        this.workDir = workDir;
        output = new Thread(() -> pump(lines), "GlassFish Embedded " + process.pid() + " output");
        output.setDaemon(true);
        output.start();
    }

    /**
     * Start {@code command} in {@code workDir}, recorded in the stage
     * directory it runs from, and pass each line of its output to
     * {@code lines}. When the child can't start, its working directory is
     * removed.
     *
     * @param slotDir the pool slot the child serves, or {@code null}
     * @param exclusive whether to refuse while another child runs from the
     *        same stage directory; see {@link EmbeddedProcesses#start}
     */
    public static EmbeddedChild start(List<String> command, EmbeddedWorkDir workDir, EmbeddedStartup startup,
            Path slotDir, boolean exclusive, Consumer<String> lines) throws IOException {
        Process process;
        try {
            process = EmbeddedProcesses.start(workDir.stageDir(), slotDir, exclusive,
                    () -> new ProcessBuilder(command)
                            .directory(workDir.dir().toFile())
                            .redirectErrorStream(true)
                            .start());
            // GlassFish Embedded reads nothing without --prompt.
            process.getOutputStream().close();
        } catch (IOException | RuntimeException e) {
            workDir.close();
            throw e;
        }

        return new EmbeddedChild(process, startup, workDir, lines);
    }

    private void pump(Consumer<String> lines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.accept(line);
                synchronized (startup) {
                    if (tail.size() == TAIL_LINES) {
                        tail.removeFirst();
                    }
                    tail.addLast(line);
                    startup.accept(line);
                    startup.notifyAll();
                }
            }
        } catch (IOException e) {
            // The child's output closed with the child.
        } finally {
            synchronized (startup) {
                // A flag, rather than the thread's end: this thread is still alive as it notifies.
                outputEnded = true;
                startup.notifyAll();
            }
        }
    }

    /**
     * Wait until the child has deployed the application, for up to
     * {@code timeout}. When it ends first, fails, or doesn't start in time,
     * it's closed, and this fails.
     */
    public void awaitStarted(Duration timeout) throws StartFailedException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String failure;
        List<String> lastLines;
        try {
            boolean ended;
            synchronized (startup) {
                while (!startup.isStarted() && startup.failure() == null && !outputEnded) {
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
                ended = outputEnded;
            }
            if (failure == null && ended) {
                failure = "GlassFish Embedded ended with exit code " + process.waitFor() + " before it started";
            }
        } catch (InterruptedException e) {
            close();
            throw e;
        }

        close();
        synchronized (startup) {
            lastLines = new ArrayList<>(tail);
        }
        throw new StartFailedException((failure != null) ? failure
                : "GlassFish Embedded didn't start within " + timeout.toSeconds() + " seconds", lastLines);
    }

    /** The name GlassFish deployed the application under. */
    public String name() {
        return startup.name();
    }

    /** A WAR's context root; empty for other archives. */
    public List<String> contextRoots() {
        return startup.contextRoots();
    }

    /** The servlets of each web module, by context root, when the command listed them. */
    public Map<String, List<String>> servlets() {
        return startup.servlets();
    }

    public ProcessHandle handle() {
        return process.toHandle();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** The exit code of a child that ended. */
    public int exitValue() {
        return process.exitValue();
    }

    /**
     * Stop the child: a SIGTERM, then, if it hasn't ended within
     * {@code timeout}, a kill.
     *
     * @return whether it was still running
     */
    public boolean stop(Duration timeout) throws IOException {
        if (!process.isAlive()) {
            return false;
        }

        EmbeddedProcesses.end(process.toHandle(), timeout);
        try {
            // Its last lines, before what the caller logs next.
            output.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /** Stop the child, if it runs, then remove its record and its working directory. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        try {
            stop(STOP_TIMEOUT);
        } catch (IOException e) {
            LOG.log(DEBUG, "Stopping GlassFish Embedded's child " + process.pid() + " failed: " + e.getMessage());
        }
        try {
            EmbeddedProcesses.forget(workDir.stageDir(), process.toHandle());
        } catch (IOException e) {
            // The record of a child that ended doesn't hold staging up.
            LOG.log(DEBUG, "Could not remove the record of GlassFish Embedded's child from " + workDir.stageDir()
                    + ": " + e.getMessage());
        }
        workDir.close();
    }

    /** A start that failed, with the last lines the child wrote. */
    public static final class StartFailedException extends IOException {

        private static final long serialVersionUID = 1L;

        private final List<String> lastLines;

        StartFailedException(String message, List<String> lastLines) {
            super(message);
            this.lastLines = List.copyOf(lastLines);
        }

        /** Up to the last 30 lines of the child's output. */
        public List<String> lastLines() {
            return lastLines;
        }
    }
}
