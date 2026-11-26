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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import ee.omnifish.glassfish.admin.embedded.EarWebModules;
import ee.omnifish.glassfish.admin.embedded.EmbeddedChild;
import ee.omnifish.glassfish.admin.embedded.EmbeddedChild.StartFailedException;
import ee.omnifish.glassfish.admin.embedded.EmbeddedCommand;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedPorts;
import ee.omnifish.glassfish.admin.embedded.EmbeddedStartup;
import ee.omnifish.glassfish.admin.embedded.EmbeddedWorkDir;

/**
 * A GlassFish Embedded child that runs the application, with its output on
 * the console, and the one ending of the run, however it ends: from a
 * shutdown hook on plain Maven's Ctrl+C, after mvnd's Ctrl+C interrupts the
 * wait, after a failed start, or once the child ended otherwise. The ending
 * stops the child, then removes its record and its working directory. Shared
 * by {@code glassfish:run} and the pool plugin's {@code glassfish-pool:run}.
 */
public final class EmbeddedRun implements AutoCloseable {

    /** How long the child gets to stop before it's killed. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    /** Exit codes of a child that was stopped, by Ctrl+C's SIGINT or a SIGTERM, rather than failed. */
    private static final List<Integer> STOPPED = List.of(0, 130, 143);

    private final EmbeddedChild child;
    private final Log log;
    private final RunEnding ending;

    private volatile boolean stopping;

    private EmbeddedRun(EmbeddedChild child, String goal, Log log) {
        this.child = child;
        this.log = log;
        ending = new RunEnding(goal, log, this::stop, child::close);
    }

    /**
     * Start the child {@code command} describes, on {@code ports}, in a
     * working directory under {@code workParent} with a copy of
     * {@code archive}, printing each line of its output after
     * {@code prefix}, and end the run when the JVM exits.
     *
     * @param slotDir the pool slot the child serves, or {@code null}
     * @param exclusive whether to refuse while another child runs from the
     *        same stage directory, as for {@code glassfish:run}
     * @param goal the goal that runs, which names the shutdown hook's thread
     */
    public static EmbeddedRun start(EmbeddedCommand command, Path archive, EmbeddedGlassFish glassfish,
            EmbeddedPorts ports, Path workParent, Path slotDir, boolean exclusive, String prefix, String goal, Log log)
            throws MojoExecutionException {
        List<String> line;
        EmbeddedWorkDir workDir;
        try {
            glassfish.requireMainClass();
            line = command.ports(ports).build();
            workDir = EmbeddedWorkDir.create(workParent, archive, glassfish, ports);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        return start(line, workDir, command.startup(), slotDir, exclusive, prefix, goal, log);
    }

    /** Start {@code line} in {@code workDir}. Package-private for unit tests, which start a stand-in child. */
    static EmbeddedRun start(List<String> line, EmbeddedWorkDir workDir, EmbeddedStartup startup, Path slotDir,
            boolean exclusive, String prefix, String goal, Log log) throws MojoExecutionException {
        try {
            return new EmbeddedRun(EmbeddedChild.start(line, workDir, startup, slotDir, exclusive,
                    output -> System.out.println(prefix + output)), goal, log);
        } catch (IOException e) {
            throw new MojoExecutionException("Starting GlassFish Embedded failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wait until the child has deployed the application, for up to
     * {@code timeout}. When it ends first, fails, or doesn't start in time,
     * the run ends, and this fails.
     */
    public void awaitStarted(Duration timeout) throws MojoExecutionException {
        try {
            child.awaitStarted(timeout);
        } catch (StartFailedException e) {
            close();
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while GlassFish Embedded started", e);
        }
    }

    /** The name GlassFish deployed the application under. */
    public String name() {
        return child.name();
    }

    /** A WAR's context root; empty for other archives. */
    List<String> contextRoots() {
        return child.contextRoots();
    }

    /**
     * Where the application answers, on {@code httpPort}: a WAR at the
     * context root GlassFish gave it, an EAR at those of its descriptor. If
     * the EAR can't be read, the run ends, and this fails.
     */
    public List<String> urls(Path archive, String packaging, int httpPort) throws MojoExecutionException {
        List<String> contextRoots;
        try {
            contextRoots = "ear".equals(packaging) ? EarWebModules.contextRoots(archive) : child.contextRoots();
        } catch (IOException e) {
            close();
            throw new MojoExecutionException(e.getMessage(), e);
        }

        List<String> urls = new ArrayList<>();
        for (String root : contextRoots) {
            urls.add(RunDeployer.url(httpPort, root));
        }
        return urls;
    }

    /**
     * Wait until the child ends, or this thread is interrupted, then end the
     * run. A child that ended by itself is reported, with its exit code
     * unless it's a stopped one's.
     */
    public void untilStopped() {
        ending.untilExit(Optional.of(child.handle()));
        if (stopping || child.isAlive()) {
            return;
        }

        int exitCode = child.exitValue();
        if (STOPPED.contains(exitCode)) {
            log.info("GlassFish Embedded ended");
        } else {
            log.warn("GlassFish Embedded ended with exit code " + exitCode);
        }
    }

    /** Stop the child: a SIGTERM, then, if it hasn't ended within {@link #STOP_TIMEOUT}, a kill. */
    private void stop() throws IOException {
        if (!child.isAlive()) {
            return;
        }

        stopping = true;
        if (child.stop(STOP_TIMEOUT)) {
            log.info("GlassFish Embedded stopped");
        }
    }

    /** End the run, if it hasn't ended, and drop the shutdown hook. */
    @Override
    public void close() {
        ending.close();
    }

    /** The child's process. Package-private for unit tests. */
    ProcessHandle handle() {
        return child.handle();
    }
}
