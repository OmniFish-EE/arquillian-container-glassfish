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
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ee.omnifish.glassfish.admin.AsAdmin;
import ee.omnifish.glassfish.admin.AsAdmin.AsAdminException;
import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;
import ee.omnifish.glassfish.maven.staging.StagingCommands;

/**
 * Start {@code domain1} of the GlassFish install, and return once it answers
 * and its {@code postStartCommands} have run, leaving it running, for
 * example for the remote Arquillian Container. The install is provisioned
 * first, as {@code provision} does, which costs nothing when it's staged
 * already. Its {@code postBootCommands}, when staging has them to run, run on
 * this start, not on a brief one of their own. Stop it with
 * {@code glassfish:stop}.
 *
 * <p>The extension doesn't bind this goal: a server that outlives the build
 * should be started explicitly, in an {@code <execution>}. Default phase
 * {@code pre-integration-test}.
 */
@Mojo(name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, requiresProject = false,
        threadSafe = true)
public class StartMojo extends AbstractStagingMojo {

    /** Deadline for each post-boot and post-start command. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    /**
     * {@code asadmin} commands run after every start, one per line, against
     * the domain's admin port. A failed one stops the domain again and fails
     * the goal, unless {@code failOnCommandError} is off. Quotes group words;
     * blank lines and lines starting with {@code #} are skipped.
     *
     * <p>No {@code -D} switch: {@code glassfish.postStartCommands} is what the
     * managed Arquillian Container runs.
     */
    @Parameter
    private String postStartCommands;

    /** Start with the debugger enabled, as {@code start-domain --debug}. */
    @Parameter(property = "glassfish.start.debug", defaultValue = "false")
    private boolean debug;

    /**
     * Start suspended, as {@code start-domain --suspend}: the server waits for
     * a debugger to attach before it starts, and this goal waits with it, up
     * to {@code startTimeout}.
     */
    @Parameter(property = "glassfish.start.suspend", defaultValue = "false")
    private boolean suspend;

    /**
     * Seconds {@code start-domain} may take, including the wait for a
     * debugger with {@code suspend}. After that, {@code asadmin} is killed
     * with what it started, and the goal fails.
     */
    @Parameter(property = "glassfish.start.timeout", defaultValue = "300")
    private int startTimeout;

    /** Skip this goal. */
    @Parameter(property = "glassfish.start.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (isSkipped("start", skip, "glassfish.start.skip")) {
            return;
        }

        StagingCommands commands = new StagingCommands(postStartCommands, failOnCommandError);
        requireReadable(commands);
        Duration timeout = Duration.ofSeconds(startTimeout);
        // The admin port of the start staging made for its post-boot commands; 0 when it made none.
        AtomicInteger startedOn = new AtomicInteger();
        Path home = provision((installRoot, postBootCommands) -> {
            try {
                startedOn.set(start(new GlassFishDomain(installRoot), postBootCommands, debug, suspend, timeout,
                        getLog()));
            } catch (MojoExecutionException e) {
                throw new IOException(e.getMessage(), e);
            }
        });
        if (home == null) {
            throw new MojoExecutionException("Can't start GlassFish: its install root isn't known. Set <home>.");
        }

        GlassFishDomain domain = new GlassFishDomain(home);
        int adminPort = startedOn.get();
        if (adminPort == 0) {
            adminPort = start(domain, StagingCommands.NONE, debug, suspend, timeout, getLog());
        }
        runPostStartCommands(domain, adminPort, commands, getLog());
    }

    /**
     * Fail on a command that can't be read, such as one with an unclosed
     * quote, before anything starts. Package-private for unit tests.
     */
    static void requireReadable(StagingCommands commands) throws MojoExecutionException {
        for (String command : commands.lines()) {
            try {
                PostBootCommands.words(command);
            } catch (IllegalArgumentException e) {
                throw new MojoExecutionException("Can't read a post-start command: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Start {@code domain}, and run {@code postBootCommands} on it: staging's,
     * when it has them to run. Fails before starting anything when its admin
     * port already answers, and stops the domain again when a command fails
     * with {@code failOnError}. Package-private for unit tests.
     *
     * @return the admin port it started on
     */
    static int start(GlassFishDomain domain, StagingCommands postBootCommands, boolean debug, boolean suspend,
                     Duration timeout, Log log) throws MojoExecutionException {
        int adminPort;
        try {
            // From domain.xml as it is now: post-boot commands that provision ran may have changed it.
            adminPort = domain.adminPort();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        if (GlassFishDomain.isAnswering(adminPort)) {
            throw new MojoExecutionException("Something already answers on admin port " + adminPort
                    + ": another server, or this domain, started before. Stop it first; glassfish:stop stops "
                    + "this domain.");
        }

        if (suspend) {
            log.info("Waiting up to " + timeout.toSeconds() + " seconds for a debugger to attach on "
                    + domain.debugAddress());
        }
        try {
            domain.start(debug, suspend, timeout);
        } catch (AsAdminException e) {
            throw new MojoExecutionException("Starting GlassFish from " + domain.home() + " failed: "
                    + e.getMessage(), e);
        }

        if (postBootCommands.hasWork()) {
            log.info("Running the post-boot commands of " + domain.home() + " on admin port " + adminPort);
        }
        runCommands(domain, adminPort, postBootCommands, "post-boot", log);

        return adminPort;
    }

    /**
     * Run {@code commands} on the running {@code domain}, and stop it again
     * when one fails with {@code failOnError}. Package-private for unit tests.
     */
    static void runPostStartCommands(GlassFishDomain domain, int adminPort, StagingCommands commands, Log log)
            throws MojoExecutionException {
        runCommands(domain, adminPort, commands, "post-start", log);
        log.info("GlassFish runs from " + domain.home() + ", on admin port " + adminPort);
    }

    private static void runCommands(GlassFishDomain domain, int adminPort, StagingCommands commands, String kind,
                                    Log log) throws MojoExecutionException {
        AsAdmin asadmin = new AsAdmin(domain.home(), adminPort);
        try {
            PostBootCommands.run(commands.lines(),
                    (command, args) -> asadmin.run(COMMAND_TIMEOUT, command, args.toArray(String[]::new)),
                    commands.failOnError());
        } catch (CommandFailedException e) {
            // Otherwise a failure in pre-integration-test leaves the server running: Maven never gets to stop.
            stopAfterFailure(domain, log);
            throw new MojoExecutionException("A " + kind + " command failed, so GlassFish was stopped again: "
                    + e.getMessage(), e);
        }
    }

    private static void stopAfterFailure(GlassFishDomain domain, Log log) {
        try {
            domain.stop();
        } catch (IOException e) {
            log.warn(e.getMessage());
        }
    }
}
