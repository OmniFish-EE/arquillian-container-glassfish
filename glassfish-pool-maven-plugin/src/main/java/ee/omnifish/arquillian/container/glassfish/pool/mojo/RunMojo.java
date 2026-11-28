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
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import ee.omnifish.arquillian.container.glassfish.pool.EmbeddedPool;
import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;
import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;
import ee.omnifish.arquillian.container.glassfish.pool.SlotLease;
import ee.omnifish.arquillian.container.glassfish.pool.SlotLeaser;
import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;
import ee.omnifish.glassfish.admin.ServerLogTailer;
import ee.omnifish.glassfish.admin.embedded.EmbeddedCommand;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedPorts;
import ee.omnifish.glassfish.admin.rest.AdminConnection;
import ee.omnifish.glassfish.admin.rest.AdminRestClient;
import ee.omnifish.glassfish.admin.rest.GlassFishClientException;
import ee.omnifish.glassfish.maven.EmbeddedRun;
import ee.omnifish.glassfish.maven.LifecycleSupport;
import ee.omnifish.glassfish.maven.RunDeployer;
import ee.omnifish.glassfish.maven.RunningDomain;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;

/**
 * Package the project, lease a slot of the pool as a test does, deploy the
 * project's artifact to it, and keep running, with the slot's
 * {@code server.log} on the console, until Ctrl+C. The slot is the GlassFish
 * the tests run on: staged with the pool's overlays and certificates, and set
 * up with its system properties and commands.
 *
 * <p>Ctrl+C undeploys the application and hands the slot back. The pool then
 * stops after the build, as it does after tests, except for the slots that
 * runs in other terminals hold, so two modules can run at once.
 * {@code glassfish-pool:down} from another terminal stops every slot, and so
 * ends every run.
 *
 * <p>With {@code <extensions>true</extensions>}, the slot has a debugger, on
 * localhost and a port of its own, unless {@code glassfish.debug} is off:
 * the extension turns it on for a build that runs this goal, so that an
 * {@code up} in the {@code package} this goal runs starts slots with one.
 *
 * <p>The artifact replaces an application deployed with the same name. Only
 * {@code war}, {@code ear}, {@code ejb} and {@code rar} projects are
 * deployed; for others, this goal does nothing. {@code -DskipTests} skips the
 * tests of the {@code package} this goal runs, not this goal.
 *
 * <p>On an Embedded {@code <edition>}, it runs the artifact on GlassFish
 * Embedded, on the slot's ports: from the command line, in a JVM of its own,
 * as {@code glassfish:run} does on its own, or with
 * {@code <embeddedMode>in-process</embeddedMode>}, in Maven's own JVM, which
 * then needs GlassFish's module options, from {@code .mvn/jvm.config} or
 * {@code MAVEN_OPTS}. In Maven's JVM, Ctrl+C ends the run, as does
 * {@code glassfish-pool:down} from another terminal, after which this goal
 * returns, and Maven goes on with the next. mvnd's daemon, which would keep
 * what GlassFish changes in the JVM, can't host it.
 */
@Mojo(name = "run", threadSafe = true)
@Execute(phase = LifecyclePhase.PACKAGE)
public class RunMojo extends AbstractStagingPoolMojo {

    private static final String GOAL = "glassfish-pool:run";

    /** How long GlassFish Embedded gets to deploy the artifact, or a debugger to attach to it with {@code suspend}. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);

    /** The system property mvnd sets in its daemon, whose JVM a build doesn't have to itself. */
    static final String MVND_HOME = "mvnd.home";

    /**
     * The application's name. GlassFish takes it from an EAR's
     * {@code application.xml}, or else from the artifact's file name, without
     * its extension.
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
     * Whether the console shows only the messages of the slot's
     * {@code server.log} records, without the header before each, with its
     * time, level, logger and thread, and without the {@code ]]} after each.
     * With {@code false}, it shows the records whole, as the file has them.
     */
    @Parameter(property = "glassfish.run.logMessagesOnly", defaultValue = "true")
    private boolean logMessagesOnly;

    /** The project as the {@code package} this goal runs left it, with its artifact. */
    @Parameter(defaultValue = "${executedProject}", readonly = true, required = true)
    private MavenProject executedProject;

    /** The name the application was deployed under, once it is. */
    private volatile String deployed;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("glassfish-pool:run skipped (gf.pool.skip=true)");
            return;
        }
        if (!RunDeployer.isDeployable(executedProject)) {
            getLog().info(RunDeployer.nothingToDeploy(executedProject, GOAL));
            return;
        }

        Path archive = RunDeployer.artifact(executedProject, GOAL);
        // An up in the package this goal forks has staged the pool, as the build configured it by then: the
        // Jakarta Security TCK, for one, sets which overlays to skip during that build. That up recorded the
        // source it staged, also where the pom sets gf.pool.source.
        String stagedSource = stagedByUp(executedProject);
        Runnable preStage = null;
        if (stagedSource != null) {
            poolSource = new File(stagedSource);
        } else {
            preStage = buildPreStage();
        }
        PoolConfig poolConfig = poolConfig();
        boolean hosted = poolConfig.embedded() && IN_PROCESS.equals(embeddedMode());
        if (hosted) {
            requireOwnJvm(System.getProperty(MVND_HOME));
        }
        try {
            if (hosted) {
                runHosted(poolConfig, preStage, archive);
            } else if (poolConfig.embedded()) {
                runEmbedded(poolConfig, preStage, archive);
            } else {
                run(poolConfig, preStage, archive);
            }
        } finally {
            stopPool(poolConfig);
        }
    }

    /**
     * Fail in mvnd's daemon, with {@code mvndHome} set: Maven and GlassFish
     * share the JVM's system properties, its logging configuration and its
     * TLS defaults, and what GlassFish leaves there would reach the daemon's
     * next build. Package-private for unit tests.
     */
    static void requireOwnJvm(String mvndHome) throws MojoExecutionException {
        if (mvndHome != null) {
            throw new MojoExecutionException("glassfish-pool:run can't host GlassFish Embedded in mvnd's daemon, which "
                    + "would keep what GlassFish changes in the JVM for its next build. Set <embeddedMode>command-line"
                    + "</embeddedMode> to run it in a JVM of its own, or use mvn.");
        }
    }

    private void run(PoolConfig poolConfig, Runnable preStage, Path archive) throws MojoExecutionException {
        SlotLease lease = lease(poolConfig, preStage);
        int slot = lease.slotIndex();
        int adminPort = lease.ports().adminPort();
        String prefix = ServerLogTailer.prefix("glassfish:slot-" + slot, slot - 1, LifecycleSupport.isConsoleColored());
        RunningDomain running = new RunningDomain(new GlassFishDomain(Paths.get(lease.ports().glassFishHome())),
                prefix, logMessagesOnly, GOAL, getLog(), () -> handBack(lease));
        try {
            deployed = RunDeployer.deploy(adminPort, archive, name, contextRoot, getLog());
        } catch (MojoExecutionException | RuntimeException e) {
            running.close();
            throw e;
        }

        int debugPort = lease.ports().debugPort();
        if (debug && debugPort == 0) {
            getLog().info("Slot " + slot + " runs without a debugger: a build without glassfish.debug started it");
        }
        running.logWhere(debugPort > 0);
        getLog().info("Ctrl+C ends the run, and hands slot " + slot + " back to the pool");
        running.untilStopped();
    }

    /**
     * Run {@code archive} on GlassFish Embedded, on a leased slot's ports:
     * start a child with the archive, in a working directory under the
     * slot's, with the slot's setup, and keep running, with its output on the
     * console, until Ctrl+C stops it, or it ends otherwise, as when
     * {@code glassfish-pool:down} from another terminal ends it. Then hand
     * the slot back: as the run's ending, once the child started, which on
     * Ctrl+C runs in a shutdown hook, as the JVM exits once its hooks return.
     */
    private void runEmbedded(PoolConfig poolConfig, Runnable preStage, Path archive) throws MojoExecutionException {
        SlotLease lease = lease(poolConfig, preStage);
        int slot = lease.slotIndex();
        Runnable handBack = () -> {
            lease.close();
            getLog().info("Handed slot " + slot + " back to the pool");
        };
        EmbeddedRun run = null;
        try {
            EmbeddedGlassFish glassfish = embeddedGlassFish(poolConfig);
            EmbeddedPorts ports = lease.ports().embeddedPorts(poolConfig.debugPort(slot));
            for (int port : ports.listening(glassfish.isJar())) {
                if (GlassFishDomain.isAnswering(port)) {
                    throw new MojoExecutionException("Something already answers on port " + port + " of slot " + slot
                            + ", where GlassFish Embedded would start");
                }
            }
            String packaging = executedProject.getPackaging();
            EmbeddedCommand command = new EmbeddedCommand(glassfish, archive.getFileName().toString(),
                    "war".equals(packaging))
                    .debug(debug, suspend)
                    .messagesOnly(logMessagesOnly)
                    .jvmOptions(poolConfig.setup().childJvmOptions())
                    .commands(poolConfig.setup().childCommands())
                    .name(name)
                    .contextRoot(contextRoot);

            if (suspend) {
                getLog().info("Waiting up to " + START_TIMEOUT.toSeconds() + " seconds for a debugger to attach on "
                        + ports.debugAddress());
            }
            String prefix = ServerLogTailer.prefix("glassfish:slot-" + slot, slot - 1,
                    LifecycleSupport.isConsoleColored());
            run = EmbeddedRun.start(command, archive, glassfish, ports, lease.slotDir(), lease.slotDir(), false,
                    prefix, GOAL, getLog(), handBack);
            run.awaitStarted(START_TIMEOUT);
            getLog().info(RunDeployer.deployed(run.name(), run.urls(archive, packaging, ports.http())));
            if (debug || suspend) {
                getLog().info("Debugger at " + ports.debugAddress());
            }
            getLog().info("Ctrl+C ends the run, and hands slot " + slot + " back to the pool");
            run.untilStopped();
        } finally {
            if (run != null) {
                // Ends the run, if it hasn't ended, which hands the slot back.
                run.close();
            } else {
                handBack.run();
            }
        }
    }

    /**
     * Run {@code archive} on GlassFish Embedded in Maven's own JVM, on a
     * leased slot's ports, until Ctrl+C, or until {@code glassfish-pool:down}
     * from another terminal asks the run to stop. Then hand the slot back: as
     * the run's ending, which on Ctrl+C runs in a shutdown hook.
     */
    private void runHosted(PoolConfig poolConfig, Runnable preStage, Path archive) throws MojoExecutionException {
        SlotLease lease = lease(poolConfig, preStage);
        int slot = lease.slotIndex();
        Runnable handBack = () -> {
            lease.close();
            getLog().info("Handed slot " + slot + " back to the pool");
        };
        HostedRun run = null;
        try {
            logSettingsThatDontApply(poolConfig);
            EmbeddedGlassFish glassfish = embeddedGlassFish(poolConfig);
            // One that a down left after its run had ended would end this one at once.
            lease.clearStopRequest();
            run = HostedRun.start(lease, glassfish, poolConfig, GOAL, getLog(), handBack);
            List<String> urls = run.deploy(archive, name, contextRoot, executedProject.getPackaging());
            getLog().info(RunDeployer.deployed(run.name(), urls));
            getLog().info("Ctrl+C, or glassfish-pool:down from another terminal, ends the run, and hands slot " + slot
                    + " back to the pool");
            run.untilStopped(lease::isStopRequested);
        } catch (IOException e) {
            throw new MojoExecutionException("Running GlassFish Embedded in Maven's JVM failed: " + e.getMessage(), e);
        } finally {
            if (run != null) {
                // Ends the run, if it hasn't ended, which hands the slot back.
                run.close();
            } else {
                handBack.run();
            }
        }
    }

    /**
     * Say which settings don't apply when Maven's own JVM hosts GlassFish:
     * the JVM was started before this goal ran.
     */
    private void logSettingsThatDontApply(PoolConfig poolConfig) {
        if (debug || suspend) {
            getLog().info("Maven's own JVM hosts GlassFish Embedded, so glassfish.debug and glassfish.suspend don't "
                    + "apply: debug Maven itself, as mvnDebug does.");
        }
        if (poolConfig.setup().maxHeapSize() != null || poolConfig.setup().enableAssertions() != null) {
            getLog().info("<maxHeapSize> and <enableAssertions> don't apply to GlassFish Embedded in Maven's JVM: give "
                    + "Maven -Xmx or -ea in .mvn/jvm.config or MAVEN_OPTS.");
        }
    }

    /** The GlassFish Embedded the pool's slots run, as its staging described it. */
    private static EmbeddedGlassFish embeddedGlassFish(PoolConfig poolConfig) throws MojoExecutionException {
        try {
            return EmbeddedPool.read(poolConfig.poolDir()).orElseThrow(() -> new IOException("the pool at "
                    + poolConfig.poolDir() + " doesn't describe one"));
        } catch (IOException e) {
            throw new MojoExecutionException("Can't run GlassFish Embedded: " + e.getMessage(), e);
        }
    }

    /**
     * The source an {@code up} of this build staged, as {@code executedProject}
     * has it after the {@code package} this goal forks, or null. Package-private
     * for unit tests.
     */
    static String stagedByUp(MavenProject executedProject) {
        return executedProject.getProperties().getProperty(UpMojo.STAGED_SOURCE);
    }

    /**
     * Prepare the pool as {@code up} does, but start no slot, and lease one
     * for this run: a free one, or else one the pool grows by. In a build
     * that binds {@code up} before {@code package}, the pool runs already.
     */
    private SlotLease lease(PoolConfig poolConfig, Runnable preStage) throws MojoExecutionException {
        try {
            PoolBootstrap.prepare(poolConfig, preStage);
            SlotLease lease = new SlotLeaser(poolConfig).leaseForRun();
            getLog().info("Leased slot " + lease.slotIndex() + " of the pool at " + poolConfig.poolDir());
            return lease;
        } catch (IOException | IllegalStateException | CommandFailedException e) {
            throw new MojoExecutionException("Leasing a pool slot failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Leasing a pool slot was interrupted", e);
        }
    }

    /**
     * Stop the pool once the run has handed its slot back, except for the
     * slots that runs in other terminals hold, as the stop after the build
     * does. This goal stops it itself: run from the command line, it runs in
     * a class realm apart from the extension's, whose stop after the build
     * doesn't see this goal's pools, and whose realm Maven closes before the
     * JVM's shutdown hooks run.
     */
    private void stopPool(PoolConfig poolConfig) {
        try {
            PoolBootstrap.downAfterBuild(poolConfig);
        } catch (IllegalStateException e) {
            getLog().error(e.getMessage());
        }
    }

    /**
     * Undeploy the application while the lease still holds the slot, so the
     * next lease finds the slot as the pool set it up, then hand it back.
     */
    private void handBack(SlotLease lease) {
        int adminPort = lease.ports().adminPort();
        try {
            if (deployed != null) {
                new AdminRestClient(AdminConnection.local(adminPort)).undeploy(deployed);
                getLog().info("Undeployed " + deployed);
            }
        } catch (GlassFishClientException e) {
            // A slot that's stopping is set up afresh before its next lease, so that needs no undeploy.
            if (GlassFishDomain.isAnswering(adminPort)) {
                getLog().warn("Undeploying " + deployed + " failed: " + e.getMessage());
            }
        } finally {
            lease.close();
            getLog().info("Handed slot " + lease.slotIndex() + " back to the pool");
        }
    }
}
