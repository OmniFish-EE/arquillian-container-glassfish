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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import ee.omnifish.glassfish.admin.ServerLogTailer;
import ee.omnifish.glassfish.maven.staging.EarWebModules;
import ee.omnifish.glassfish.maven.staging.EmbeddedCommand;
import ee.omnifish.glassfish.maven.staging.EmbeddedGlassFish;
import ee.omnifish.glassfish.maven.staging.EmbeddedWorkDir;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;

/**
 * Package the project, start {@code domain1} of the GlassFish install as
 * {@code glassfish:start} does, deploy the project's artifact to it, and keep
 * running, with GlassFish's {@code server.log} on the console, until Ctrl+C
 * stops GlassFish. When GlassFish stops otherwise, as with
 * {@code glassfish:stop} from another terminal, this goal returns.
 *
 * <p>The artifact replaces an application deployed with the same name, so a
 * run after a change needs no {@code clean}. Only {@code war}, {@code ear},
 * {@code ejb} and {@code rar} projects are deployed; for others, this goal
 * does nothing. {@code -DskipTests} skips the tests of the {@code package}
 * this goal runs, not this goal.
 *
 * <p>On an Embedded {@code <edition>}, it runs the artifact on GlassFish
 * Embedded instead, in a JVM of its own, the way GlassFish Embedded runs an
 * application from the command line, until Ctrl+C stops it.
 */
@Mojo(name = "run", threadSafe = true)
@Execute(phase = LifecyclePhase.PACKAGE)
public class RunMojo extends AbstractStartMojo {

    private static final String GOAL = "glassfish:run";

    /** Where GlassFish Embedded children get their working directories, under the build directory. */
    static final String WORK_DIR = "glassfish-embedded";

    /**
     * The application's name. GlassFish takes it from an EAR's
     * {@code application.xml}, or else from the artifact's file name, without
     * its extension. Set it to keep one deployment when the version in that
     * file name changes.
     */
    @Parameter(property = "glassfish.run.name")
    private String name;

    /**
     * The context root of a WAR. GlassFish takes it from its
     * {@code glassfish-web.xml}, or else from its name.
     */
    @Parameter(property = "glassfish.run.contextRoot")
    private String contextRoot;

    /**
     * Whether the console shows only the messages of {@code server.log}'s
     * records, without the header before each, with its time, level, logger
     * and thread, and without the {@code ]]} after each. With {@code false},
     * it shows the records whole, as the file has them.
     */
    @Parameter(property = "glassfish.run.logMessagesOnly", defaultValue = "true")
    private boolean logMessagesOnly;

    /** The project as the {@code package} this goal runs left it, with its artifact. */
    @Parameter(defaultValue = "${executedProject}", readonly = true, required = true)
    private MavenProject executedProject;

    private RunningDomain running;

    /** The admin port the domain answers on, which a restart may change. */
    private volatile int adminPort;

    @Override
    public void execute() throws MojoExecutionException {
        if (!RunDeployer.isDeployable(executedProject)) {
            getLog().info(RunDeployer.nothingToDeploy(executedProject, GOAL));
            return;
        }

        Path archive = RunDeployer.artifact(executedProject, GOAL);
        if (edition().isEmbedded()) {
            runEmbedded(archive);
            return;
        }

        try {
            RunDeployer.deploy(startDomain(), archive, name, contextRoot, getLog());
        } catch (MojoExecutionException | RuntimeException e) {
            if (running != null) {
                running.close();
            }
            throw e;
        }

        running.logWhere(debug());
        getLog().info("Ctrl+C stops GlassFish, as glassfish:stop does");
        running.untilStopped();
    }

    /**
     * Run {@code archive} on GlassFish Embedded: provision it, start a child
     * JVM that runs it with the archive, in a working directory of its own,
     * and keep running, with the child's output on the console, until
     * Ctrl+C stops the child, or it ends otherwise.
     */
    private void runEmbedded(Path archive) throws MojoExecutionException {
        // Before provisioning, so a second run leaves what the first one runs from alone.
        for (int port : EmbeddedCommand.ports(runsEmbeddedJar())) {
            requireFree(port);
        }
        List<String> commands = embeddedCommands();
        EmbeddedGlassFish glassfish = provisionEmbedded();
        String packaging = executedProject.getPackaging();
        boolean war = "war".equals(packaging);
        EmbeddedCommand command = new EmbeddedCommand(glassfish, archive.getFileName().toString(), war)
                .debug(debug(), suspend())
                .messagesOnly(logMessagesOnly)
                .commands(commands)
                .name(name)
                .contextRoot(contextRoot);

        List<String> line;
        List<String> earContextRoots;
        EmbeddedWorkDir workDir;
        try {
            glassfish.requireMainClass();
            line = command.build();
            earContextRoots = "ear".equals(packaging) ? EarWebModules.contextRoots(archive) : List.of();
            workDir = EmbeddedWorkDir.create(Paths.get(executedProject.getBuild().getDirectory(), WORK_DIR), archive,
                    glassfish);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        if (suspend()) {
            getLog().info("Waiting up to " + startTimeout().toSeconds() + " seconds for a debugger to attach on "
                    + EmbeddedCommand.DEBUG_ADDRESS);
        }
        String prefix = ServerLogTailer.prefix("glassfish:embedded", 0, LifecycleSupport.isConsoleColored());
        EmbeddedRun run = EmbeddedRun.start(line, workDir, new EmbeddedStartup(war, commands), prefix, GOAL,
                getLog());
        run.awaitStarted(startTimeout());
        List<String> urls = new ArrayList<>();
        for (String root : war ? run.contextRoots() : earContextRoots) {
            urls.add(RunDeployer.url(EmbeddedCommand.HTTP_PORT, root));
        }
        getLog().info(RunDeployer.deployed(run.name(), urls));
        if (debug() || suspend()) {
            getLog().info("Debugger at " + EmbeddedCommand.DEBUG_ADDRESS);
        }
        getLog().info("Ctrl+C stops GlassFish Embedded");
        run.untilStopped();
    }

    /** Fail when something already answers on {@code port}, which GlassFish Embedded would open. */
    private static void requireFree(int port) throws MojoExecutionException {
        if (GlassFishDomain.isAnswering(port)) {
            throw new MojoExecutionException("Something already answers on port " + port + ": another server, or a "
                    + "glassfish:run that runs still. Stop it first.");
        }
    }

    /** A run opens the debugger, unless {@code debug} is off. */
    @Override
    protected boolean debugByDefault() {
        return true;
    }

    /**
     * Mirror the domain's log from its first start on, and from then on, stop
     * it when this goal ends: after the check that nothing answers on its
     * admin port, so a domain another run started is left alone.
     */
    @Override
    protected void starting(GlassFishDomain domain, int adminPort) {
        this.adminPort = adminPort;
        if (running == null) {
            String prefix = ServerLogTailer.prefix("glassfish:" + GlassFishDomain.DOMAIN, 0,
                    LifecycleSupport.isConsoleColored());
            running = new RunningDomain(domain, prefix, logMessagesOnly, GOAL, getLog(),
                    () -> stop(domain, this.adminPort, getLog()));
        }
    }

    /** Stop {@code domain}, through the admin port it answers on. Package-private for unit tests. */
    static void stop(GlassFishDomain domain, int adminPort, Log log) throws IOException {
        if (domain.stop(adminPort)) {
            log.info("GlassFish at " + domain.home() + " stopped");
        }
    }
}
