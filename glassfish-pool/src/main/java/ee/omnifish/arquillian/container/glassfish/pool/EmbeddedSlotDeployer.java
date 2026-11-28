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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;

import ee.omnifish.glassfish.admin.ServerLogTailer;
import ee.omnifish.glassfish.admin.embedded.EarWebModules;
import ee.omnifish.glassfish.admin.embedded.EmbeddedChild;
import ee.omnifish.glassfish.admin.embedded.EmbeddedChild.StartFailedException;
import ee.omnifish.glassfish.admin.embedded.EmbeddedCommand;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedPorts;
import ee.omnifish.glassfish.admin.embedded.EmbeddedWorkDir;

/**
 * Deploys archives to a leased slot for GlassFish Embedded: each deployment
 * starts a child of its own, on the slot's ports, with the archive, the way
 * GlassFish Embedded runs an application from the command line, and the
 * undeploy ends it. Nothing is deployed to a child that runs.
 *
 * <p>The slot runs one child at a time. The archive that claims it holds it,
 * so a second archive's deployment fails before it disturbs the first, and
 * the undeploy Arquillian calls after that failure ends nothing. A deployment
 * that fails after its claim ends the child it started, and releases the
 * claim, as no undeploy may follow.
 *
 * <p>The child's output goes to {@code slot-N/glassfish-embedded.log}, and
 * with {@code serverLogToConsole} to the console too. A start that fails
 * says the last lines in its exception.
 */
final class EmbeddedSlotDeployer {

    private static final Logger LOG = Logger.getLogger(EmbeddedSlotDeployer.class.getName());

    /** How long a child gets to deploy its archive. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);

    /** The output of the slot's last child, in the slot's directory. */
    static final String LOG_FILE = "glassfish-embedded.log";

    private final SlotLease lease;
    private final EmbeddedGlassFish glassfish;
    private final PoolConfig poolConfig;
    private final GlassFishPoolConfiguration configuration;

    /** The children of the archives this deployer deployed, by their ids. */
    private final Map<String, Deployed> deployed = new HashMap<>();

    EmbeddedSlotDeployer(SlotLease lease, EmbeddedGlassFish glassfish, PoolConfig poolConfig,
                         GlassFishPoolConfiguration configuration) {
        this.lease = lease;
        this.glassfish = glassfish;
        this.poolConfig = poolConfig;
        this.configuration = configuration;
    }

    ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        String id = archive.getId();
        int slot = lease.slotIndex();
        if (!lease.claimChild(id)) {
            throw new DeploymentException("Pool slot " + slot + " runs another archive on GlassFish Embedded, which "
                    + "runs one archive per slot at a time, so " + archive.getName() + " can't deploy there. Deploy it "
                    + "to a container that doesn't share the slot.");
        }

        EmbeddedChild child = null;
        ChildLog log = null;
        try {
            EmbeddedPorts ports = ports();
            requireFree(ports);
            glassfish.requireMainClass();
            Path exported = lease.slotDir().resolve(archive.getName());
            archive.as(ZipExporter.class).exportTo(exported.toFile(), true);
            String name;
            Map<String, String> webModules;
            EmbeddedWorkDir workDir;
            try {
                name = applicationName(archive.getName(), exported);
                webModules = archive.getName().endsWith(".ear") ? EarWebModules.webModules(exported) : Map.of();
                workDir = EmbeddedWorkDir.create(lease.slotDir(), exported, glassfish, ports);
            } finally {
                Files.deleteIfExists(exported);
            }
            boolean war = archive.getName().endsWith(".war");
            EmbeddedCommand command = new EmbeddedCommand(glassfish, archive.getName(), war)
                    .ports(ports)
                    .debug(debug(), configuration.isSuspend())
                    .jvmOptions(jvmOptions())
                    .commands(commands())
                    .name(name)
                    .deployOptions(deployOptions(configuration))
                    .listServlets(webModules);
            List<String> line;
            try {
                line = command.build();
            } catch (IOException | RuntimeException e) {
                workDir.close();
                throw e;
            }
            log = new ChildLog(lease.slotDir().resolve(LOG_FILE), consolePrefix());
            child = EmbeddedChild.start(line, workDir, command.startup(), lease.slotDir(), false, log);
            Thread hook = new Thread(child::close, "glassfish-pool slot " + slot + " child");
            Runtime.getRuntime().addShutdownHook(hook);
            deployed.put(id, new Deployed(child, log, hook));
            child.awaitStarted(START_TIMEOUT);
            LOG.info("Deployed " + child.name() + " on GlassFish Embedded, on pool slot " + slot);

            boolean secure = configuration.isHttpsPortAsDefault();
            return metaData(secure ? ports.https() : ports.http(), secure, child.servlets());
        } catch (StartFailedException e) {
            end(id);
            throw new DeploymentException(e.getMessage() + ", on pool slot " + slot + ". Its last lines, of "
                    + lease.slotDir().resolve(LOG_FILE) + ":\n" + String.join("\n", e.lastLines()), e);
        } catch (IOException | RuntimeException e) {
            if (!end(id)) {
                if (child != null) {
                    child.close();
                }
                if (log != null) {
                    log.close();
                }
                lease.releaseChild(id);
            }
            throw new DeploymentException("Could not deploy " + archive.getName() + " on GlassFish Embedded, on "
                    + "pool slot " + slot + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            end(id);
            Thread.currentThread().interrupt();
            throw new DeploymentException("Interrupted while " + archive.getName() + " deployed", e);
        }
    }

    /**
     * End the child of {@code archive}, if this deployer deployed it. An
     * archive whose deployment was refused or failed has none, so nothing
     * ends.
     */
    void undeploy(Archive<?> archive) {
        if (end(archive.getId())) {
            LOG.info("Ended " + archive.getName() + "'s GlassFish Embedded, on pool slot " + lease.slotIndex());
        }
    }

    /** End the children still running, as when the container stops without undeploying. */
    void close() {
        for (String id : new ArrayList<>(deployed.keySet())) {
            end(id);
        }
    }

    /** End the child of {@code id}, and release its claim; false when there's none. */
    private boolean end(String id) {
        Deployed child = deployed.remove(id);
        if (child == null) {
            return false;
        }

        child.close();
        lease.releaseChild(id);
        return true;
    }

    /** The slot's ports, with the debugger's on the slot's debug port. */
    private EmbeddedPorts ports() {
        SlotPorts slot = lease.ports();
        int debugPort = (slot.debugPort() > 0) ? slot.debugPort() : poolConfig.debugPort(lease.slotIndex());
        return slot.embeddedPorts(debugPort);
    }

    /** With a debugger when {@code up} gave the slot one, or the test JVM has {@code glassfish.debug}. */
    private boolean debug() {
        return lease.ports().debugPort() > 0 || configuration.isDebug();
    }

    /**
     * Fail when something already answers on a port the child would open, as
     * a child its test JVM left running before {@code down} ended it.
     */
    private void requireFree(EmbeddedPorts ports) throws IOException {
        for (int port : ports.listening(glassfish.isJar())) {
            if (PoolProvisioner.isPortHealthy(port)) {
                throw new IOException("Something already answers on port " + port + " of pool slot "
                        + lease.slotIndex() + ", where GlassFish Embedded would start");
            }
        }
    }

    /**
     * The JVM options of the slot setup, as {@code domain.xml} would have
     * them for a domain, then those the test JVM sets.
     */
    private List<String> jvmOptions() {
        List<String> options = new ArrayList<>(poolConfig.setup().childJvmOptions());
        for (String property : configuration.getSystemProperyList()) {
            options.add("-D" + property);
        }
        return options;
    }

    /**
     * The slot setup's commands, then the test JVM's: each child is a slot's
     * first start, and a start, so all of them run, before the deploy.
     */
    private List<String> commands() {
        List<String> commands = new ArrayList<>(poolConfig.setup().childCommands());
        commands.addAll(configuration.getPostBootCommandList());
        commands.addAll(configuration.getPostStartCommandList());
        return commands;
    }

    /**
     * The deploy options {@code configuration} sets, as the REST deployment
     * passes them. Package-private for the GlassFish a test JVM hosts.
     */
    static List<String> deployOptions(GlassFishPoolConfiguration configuration) {
        List<String> options = new ArrayList<>();
        if (configuration.getLibraries() != null) {
            options.add("--libraries=" + configuration.getLibraries());
        }
        if (configuration.getProperties() != null) {
            options.add("--properties=" + configuration.getProperties());
        }
        if ("osgi".equals(configuration.getType())) {
            options.add("--type=osgi");
        }
        if (configuration.isAvailabilityEnabled()) {
            options.add("--availabilityenabled=true");
        }
        return options;
    }

    private String consolePrefix() {
        return configuration.isServerLogToConsole()
                ? ServerLogTailer.prefix("glassfish:slot-" + lease.slotIndex(), lease.slotIndex() - 1,
                        configuration.isServerLogColor())
                : null;
    }

    /**
     * The name GlassFish gives the archive {@code archiveName}, exported to
     * {@code archive}, when it isn't given one: an EAR's
     * {@code <application-name>}, or else the file name without its
     * extension. Package-private for unit tests.
     */
    static String applicationName(String archiveName, Path archive) throws IOException {
        String name = archiveName.substring(archiveName.lastIndexOf('/') + 1);
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        return name.endsWith(".ear") ? EarWebModules.applicationName(archive).orElse(base) : base;
    }

    /**
     * One HTTP context on {@code port}, with each servlet at its module's
     * context root, as Arquillian's Servlet protocol and the injected URLs
     * need them. Package-private for unit tests.
     */
    static ProtocolMetaData metaData(int port, boolean secure, Map<String, List<String>> servlets) {
        HTTPContext context = new HTTPContext("localhost", port, secure);
        servlets.forEach((contextRoot, names) -> names.forEach(name -> context.add(new Servlet(name, contextRoot))));
        ProtocolMetaData metaData = new ProtocolMetaData();
        metaData.addContext(context);
        return metaData;
    }

    /** A deployed archive's child, with its log and the shutdown hook that ends it with the JVM. */
    private static final class Deployed {

        final EmbeddedChild child;
        final ChildLog log;
        final Thread hook;

        Deployed(EmbeddedChild child, ChildLog log, Thread hook) {
            this.child = child;
            this.log = log;
            this.hook = hook;
        }

        void close() {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // The JVM is shutting down, and the hook ends the child.
            }
            child.close();
            log.close();
        }
    }

    /**
     * Writes a child's output to the slot's log file, and with a console
     * prefix, to the console too.
     */
    private static final class ChildLog implements Consumer<String> {

        private final BufferedWriter writer;
        private final String consolePrefix;

        ChildLog(Path file, String consolePrefix) throws IOException {
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            this.consolePrefix = consolePrefix;
        }

        @Override
        public synchronized void accept(String line) {
            if (consolePrefix != null) {
                System.out.println(consolePrefix + line);
            }
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // The child's output is still followed, to see it start, also when the log can't take it.
                LOG.log(Level.FINE, "Could not write a GlassFish Embedded child's output to its log", e);
            }
        }

        synchronized void close() {
            try {
                writer.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not close a GlassFish Embedded child's log", e);
            }
        }
    }
}
