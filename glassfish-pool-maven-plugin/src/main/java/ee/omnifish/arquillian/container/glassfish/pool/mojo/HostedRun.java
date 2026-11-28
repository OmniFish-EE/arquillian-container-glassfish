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

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;
import ee.omnifish.arquillian.container.glassfish.pool.SlotLease;
import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;
import ee.omnifish.glassfish.admin.embedded.EarWebModules;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedPorts;
import ee.omnifish.glassfish.admin.embedded.EmbeddedProcesses;
import ee.omnifish.glassfish.admin.embedded.EmbeddedWorkDir;
import ee.omnifish.glassfish.admin.embedded.HostedGlassFish;
import ee.omnifish.glassfish.admin.embedded.ModuleOptions;
import ee.omnifish.glassfish.maven.RunDeployer;
import ee.omnifish.glassfish.maven.RunEnding;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;

/**
 * GlassFish Embedded in Maven's own JVM, for {@code glassfish-pool:run} with
 * {@code <embeddedMode>in-process</embeddedMode>}, on a leased slot's ports.
 * It loads from a class loader of its own over the staged JAR or static
 * shell, whose parent is the platform class loader, except for GlassFish's
 * embeddable API, which it shares with this plugin, so GlassFish and the
 * application see neither Maven's libraries nor the plugin's.
 *
 * <p>The run ends once: on Ctrl+C, from Maven's shutdown hook, or when
 * {@code glassfish-pool:down} asks it to stop, after which the goal returns,
 * and Maven goes on with the next. The ending shuts GlassFish down, which
 * gives Maven's JVM its system properties and TLS defaults back, closes the
 * class loader, removes the JVM's record from the stage directory, and hands
 * the slot back.
 */
final class HostedRun implements AutoCloseable {

    private final EmbeddedPorts ports;
    private final EmbeddedWorkDir workDir;
    private final HostedGlassFish hosted;
    private final RunEnding ending;

    private String name;

    private HostedRun(EmbeddedPorts ports, EmbeddedWorkDir workDir, HostedGlassFish hosted, RunEnding ending) {
        this.ports = ports;
        this.workDir = workDir;
        this.hosted = hosted;
        this.ending = ending;
    }

    /**
     * Boot {@code glassfish} in this JVM, on the ports of the slot
     * {@code lease} holds, with the slot setup of {@code poolConfig}, and end
     * the run when the JVM exits.
     *
     * @param handBack what follows the ending: the hand-back of the slot
     * @throws MojoExecutionException if something answers on the slot's ports
     *         already, the JVM lacks GlassFish's module options, GlassFish
     *         doesn't start, or a command fails with {@code failOnCommandError};
     *         what started is shut down then, but the slot isn't handed back
     */
    static HostedRun start(SlotLease lease, EmbeddedGlassFish glassfish, PoolConfig poolConfig, String goal,
            Log log, Runnable handBack) throws MojoExecutionException {
        EmbeddedPorts ports = lease.ports().embeddedPorts(0);
        for (int port : ports.listening(glassfish.isJar())) {
            if (GlassFishDomain.isAnswering(port)) {
                throw new MojoExecutionException("Something already answers on port " + port + " of slot "
                        + lease.slotIndex() + ", where GlassFish Embedded would start");
            }
        }

        URLClassLoader loader = null;
        EmbeddedWorkDir workDir = null;
        EmbeddedProcesses.Host host = null;
        HostedGlassFish hosted = null;
        try {
            loader = HostedGlassFish.classLoader(glassfish.classPath());
            requireModuleOptions(glassfish, loader);
            workDir = EmbeddedWorkDir.forHost(lease.slotDir(), glassfish, ports, null, null);
            host = EmbeddedProcesses.host(glassfish.stageDir(), lease.slotDir(), true);
            hosted = HostedGlassFish.start(loader, HostedGlassFish.Settings.of(glassfish, workDir, ports)
                    .systemProperties(poolConfig.setup().systemProperties()));
            HostedGlassFish commands = hosted;
            PostBootCommands.run(poolConfig.setup().childCommands(),
                    (command, args) -> commands.run(command, args.toArray(String[]::new)),
                    poolConfig.setup().failOnCommandError());

            HostedGlassFish started = hosted;
            URLClassLoader classLoader = loader;
            EmbeddedProcesses.Host record = host;
            EmbeddedWorkDir dir = workDir;
            RunEnding ending = new RunEnding(goal, log, () -> {
                started.close();
                log.info("GlassFish Embedded stopped");
            }, () -> {
                try {
                    classLoader.close();
                } catch (IOException e) {
                    log.debug("Could not close GlassFish Embedded's class loader: " + e.getMessage());
                }
                record.close();
                dir.close();
                handBack.run();
            });
            return new HostedRun(ports, workDir, hosted, ending);
        } catch (IOException | CommandFailedException | MojoExecutionException e) {
            if (hosted != null) {
                hosted.close();
            }
            if (host != null) {
                host.close();
            }
            if (workDir != null) {
                workDir.close();
            }
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException closing) {
                    e.addSuppressed(closing);
                }
            }
            if (e instanceof MojoExecutionException) {
                throw (MojoExecutionException) e;
            }
            throw new MojoExecutionException("GlassFish Embedded didn't start in Maven's JVM: " + e.getMessage(), e);
        }
    }

    /**
     * Fail unless Maven's JVM has the module options GlassFish needs: only its
     * command line gives them, so this names the lines {@code .mvn/jvm.config}
     * needs. Without them, GlassFish logs reflective-access errors, and
     * can't boot a second time in the JVM.
     */
    static void requireModuleOptions(EmbeddedGlassFish glassfish, ClassLoader loader)
            throws IOException, MojoExecutionException {
        List<String> missing = ModuleOptions.missing(glassfish.moduleOptions(), loader.getUnnamedModule());
        if (!missing.isEmpty()) {
            throw new MojoExecutionException("Maven's JVM lacks module options that GlassFish Embedded needs, which "
                    + "only its command line gives. Add these lines to .mvn/jvm.config, in the project's root, or "
                    + "give them in MAVEN_OPTS:\n" + String.join("\n", missing));
        }
    }

    /**
     * Deploy {@code archive}, as a copy in the working directory, so a build
     * in another terminal may replace the artifact.
     *
     * @param name the application's name, or {@code null} for GlassFish's
     * @param contextRoot a WAR's context root, or {@code null} for GlassFish's
     * @return where it answers: a WAR at the context root GlassFish gave it,
     *         an EAR at those of its descriptor
     */
    List<String> deploy(Path archive, String name, String contextRoot, String packaging)
            throws MojoExecutionException {
        boolean war = "war".equals(packaging);
        try {
            Path copy = workDir.dir().resolve(archive.getFileName().toString());
            Files.copy(archive, copy, StandardCopyOption.REPLACE_EXISTING);
            List<String> options = (war && contextRoot != null) ? List.of("--contextroot=" + contextRoot)
                    : List.of();
            this.name = hosted.deploy(copy, name, options);

            List<String> contextRoots = war ? hosted.contextRoots(this.name)
                    : "ear".equals(packaging) ? EarWebModules.contextRoots(archive) : List.of();
            List<String> urls = new ArrayList<>();
            for (String root : contextRoots) {
                urls.add(RunDeployer.url(ports.http(), root));
            }
            return urls;
        } catch (IOException e) {
            close();
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /** The name GlassFish deployed the application under. */
    String name() {
        return name;
    }

    /**
     * Wait until {@code stopRequested} says so, or this thread is
     * interrupted, then end the run. On Ctrl+C, Maven's shutdown hook ends it
     * meanwhile, and the JVM exits once it has.
     */
    void untilStopped(BooleanSupplier stopRequested) {
        ending.untilRequested(stopRequested);
    }

    /** End the run, if it hasn't ended, and drop the shutdown hook. */
    @Override
    public void close() {
        ending.close();
    }
}
