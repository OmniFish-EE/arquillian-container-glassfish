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
package ee.omnifish.arquillian.container.glassfish.pool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;

import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.admin.embedded.EarWebModules;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedPorts;
import ee.omnifish.glassfish.admin.embedded.EmbeddedProcesses;
import ee.omnifish.glassfish.admin.embedded.EmbeddedWorkDir;
import ee.omnifish.glassfish.admin.embedded.HostedGlassFish;

/**
 * The GlassFish Embedded that a test JVM hosts on a leased slot, with the
 * pool plugin's {@code <embeddedMode>in-process</embeddedMode>}: it boots once,
 * on the slot's ports, and deploys and undeploys archives while it runs, as
 * the {@code glassfish-embedded} Arquillian Container does. The containers
 * that share the slot share it, through the lease, which shuts it down once
 * the last of them releases the slot.
 *
 * <p>It runs from the test JVM's class path, on a copy of its instance root
 * in the slot's directory. The stage directory records the test JVM as its
 * host, so staging doesn't wipe what it runs from. The slot setup's system
 * properties become the test JVM's, and its commands, then the container's,
 * run after the start. A test JVM hosts one GlassFish at a time.
 */
final class HostedSlot implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HostedSlot.class.getName());

    private static final AtomicBoolean JVM_SETTINGS_LOGGED = new AtomicBoolean();

    /** The slot whose GlassFish this test JVM hosts, or 0. */
    private static int hostedSlot;

    private final int slot;
    private final EmbeddedPorts ports;
    private final EmbeddedWorkDir workDir;
    private final EmbeddedProcesses.Host host;
    private final HostedGlassFish glassfish;

    /** The archives deployed, by their ids, each with the name GlassFish gave it. */
    private final Map<String, String> deployed = new HashMap<>();

    private HostedSlot(int slot, EmbeddedPorts ports, EmbeddedWorkDir workDir, EmbeddedProcesses.Host host,
            HostedGlassFish glassfish) {
        this.slot = slot;
        this.ports = ports;
        this.workDir = workDir;
        this.host = host;
        this.glassfish = glassfish;
    }

    /**
     * Boot {@code embedded} on the leased slot, as {@code configuration}, the
     * configuration of the container that starts it, adds to the slot setup.
     *
     * @throws IOException if it doesn't start, a command fails with
     *         {@code failOnCommandError}, or this test JVM hosts a GlassFish
     *         on another slot already
     */
    static HostedSlot start(SlotLease lease, EmbeddedGlassFish embedded, PoolConfig poolConfig,
            GlassFishPoolConfiguration configuration) throws IOException {
        int slot = lease.slotIndex();
        synchronized (HostedSlot.class) {
            if (hostedSlot != 0) {
                throw new IOException("This test JVM hosts GlassFish Embedded on pool slot " + hostedSlot
                        + " already, and hosts one at a time, so it can't host another on slot " + slot + ". Give "
                        + "its containers one slotGroup, to share it, or set <embeddedMode>command-line"
                        + "</embeddedMode>, for a GlassFish per deployment.");
            }
            hostedSlot = slot;
        }

        // The test JVM's own debugger, as -Dmaven.failsafe.debug starts it, debugs what it hosts.
        EmbeddedPorts ports = lease.ports().embeddedPorts(0);
        EmbeddedWorkDir workDir = null;
        EmbeddedProcesses.Host host = null;
        HostedGlassFish glassfish = null;
        try {
            workDir = EmbeddedWorkDir.forHost(lease.slotDir(), embedded, ports, path(configuration.getInstanceRoot()),
                    path(configuration.getConfigurationXml()));
            host = EmbeddedProcesses.host(embedded.stageDir(), lease.slotDir(), false);
            logJvmSettings(poolConfig.setup());
            glassfish = HostedGlassFish.start(HostedSlot.class.getClassLoader(),
                    settings(embedded, ports, workDir, poolConfig, configuration));
            HostedSlot hosted = new HostedSlot(slot, ports, workDir, host, glassfish);
            List<String> commands = new ArrayList<>(poolConfig.setup().childCommands());
            commands.addAll(configuration.getPostBootCommandList());
            commands.addAll(configuration.getPostStartCommandList());
            HostedGlassFish runner = glassfish;
            PostBootCommands.run(commands, (command, args) -> runner.run(command, args.toArray(String[]::new)),
                    poolConfig.setup().failOnCommandError());
            LOG.info("Started GlassFish Embedded in this test JVM, on pool slot " + slot + " (http=" + ports.http()
                    + ")");
            return hosted;
        } catch (IOException | RuntimeException e) {
            if (glassfish != null) {
                glassfish.close();
            }
            if (host != null) {
                host.close();
            }
            if (workDir != null) {
                workDir.close();
            }
            synchronized (HostedSlot.class) {
                hostedSlot = 0;
            }
            if (e instanceof PostBootCommands.CommandFailedException) {
                throw new IOException(e.getMessage(), e);
            }
            throw e;
        }
    }

    /** What GlassFish boots with on the slot, with the slot setup's system properties, then the container's. */
    private static HostedGlassFish.Settings settings(EmbeddedGlassFish embedded, EmbeddedPorts ports,
            EmbeddedWorkDir workDir, PoolConfig poolConfig, GlassFishPoolConfiguration configuration) {
        return HostedGlassFish.Settings.of(embedded, workDir, ports)
                .systemProperties(poolConfig.setup().systemProperties())
                .systemProperties(configuration.getSystemProperyList());
    }

    /** Say once per JVM that the heap and assertion settings, which a JVM takes when it starts, don't apply. */
    private static void logJvmSettings(SlotSetup setup) {
        if ((setup.maxHeapSize() != null || setup.enableAssertions() != null)
                && JVM_SETTINGS_LOGGED.compareAndSet(false, true)) {
            LOG.info("The pool plugin's <maxHeapSize> and <enableAssertions> don't apply to GlassFish Embedded in "
                    + "the test JVM, which has failsafe's settings. Give failsafe's <argLine> -Xmx or -ea instead.");
        }
    }

    private static Path path(String path) {
        return (path == null || path.isBlank()) ? null : Paths.get(path);
    }


    /**
     * Deploy {@code archive}, as {@code configuration}, the configuration of
     * the container that deploys it, says, and give its servlets, each at its
     * module's context root, on the slot's HTTP port, or with
     * {@code httpsPortAsDefault}, its HTTPS port.
     */
    synchronized ProtocolMetaData deploy(Archive<?> archive, GlassFishPoolConfiguration configuration)
            throws DeploymentException {
        Path exported = workDir.dir().resolve(archive.getName());
        try {
            archive.as(ZipExporter.class).exportTo(exported.toFile(), true);
            String name = EmbeddedSlotDeployer.applicationName(archive.getName(), exported);
            boolean war = archive.getName().endsWith(".war");
            Map<String, String> webModules = archive.getName().endsWith(".ear")
                    ? EarWebModules.webModules(exported) : Map.of();
            String deployedName = glassfish.deploy(exported, name, EmbeddedSlotDeployer.deployOptions(configuration));
            deployed.put(archive.getId(), deployedName);
            Map<String, List<String>> servlets = glassfish.servlets(deployedName, war, webModules);
            LOG.info("Deployed " + deployedName + " on GlassFish Embedded in this test JVM, on pool slot " + slot);

            boolean secure = configuration.isHttpsPortAsDefault();
            return EmbeddedSlotDeployer.metaData(secure ? ports.https() : ports.http(), secure, servlets);
        } catch (IOException | RuntimeException e) {
            throw new DeploymentException("Could not deploy " + archive.getName() + " on GlassFish Embedded in this "
                    + "test JVM, on pool slot " + slot + ": " + e.getMessage(), e);
        }
    }

    /** Undeploy {@code archive}, if it was deployed here. */
    synchronized void undeploy(Archive<?> archive) throws DeploymentException {
        String name = deployed.remove(archive.getId());
        if (name == null) {
            return;
        }

        try {
            glassfish.undeploy(name);
        } catch (IOException e) {
            throw new DeploymentException("Could not undeploy " + name + ": " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(workDir.dir().resolve(archive.getName()));
            } catch (IOException e) {
                // GlassFish may hold it a little longer, as on Windows; the working directory goes at the close.
                LOG.log(Level.FINE, "Could not remove the copy of " + archive.getName(), e);
            }
        }
    }

    /**
     * Shut GlassFish down, remove the test JVM's record and the working
     * directory, and let this test JVM host another.
     */
    @Override
    public void close() {
        try {
            glassfish.close();
        } finally {
            host.close();
            workDir.close();
            synchronized (HostedSlot.class) {
                hostedSlot = 0;
            }
            LOG.info("Stopped GlassFish Embedded in this test JVM, on pool slot " + slot);
        }
    }
}
