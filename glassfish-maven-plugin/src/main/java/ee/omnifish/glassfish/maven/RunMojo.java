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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import ee.omnifish.glassfish.admin.ServerLogTailer;
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
 */
@Mojo(name = "run", threadSafe = true)
@Execute(phase = LifecyclePhase.PACKAGE)
public class RunMojo extends AbstractStartMojo {

    private static final String GOAL = "glassfish:run";

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
        try {
            RunDeployer.deploy(startDomain(), archive, name, contextRoot, getLog());
        } catch (MojoExecutionException | RuntimeException e) {
            if (running != null) {
                running.close();
            }
            throw e;
        }

        getLog().info("Ctrl+C stops GlassFish, as glassfish:stop does");
        running.untilStopped();
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
