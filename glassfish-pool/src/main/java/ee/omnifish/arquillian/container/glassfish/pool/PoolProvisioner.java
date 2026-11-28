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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import ee.omnifish.glassfish.admin.AsAdmin;
import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedProcesses;

/**
 * Provisions a single slot: clone the source install, patch the per-slot
 * ports and the {@link SlotSetup} settings into {@code domain.xml}, run
 * {@code asadmin start-domain} and the setup's commands, then publish a
 * {@code ports.properties} so the test JVM can pick up the slot.
 *
 * <p>The slot lock says who owns a slot; {@code ports.properties} says its
 * setup is complete. It is removed before every start or restart and written
 * after the last step, so a half-configured slot is never leased. A slot with
 * a free lock and no {@code ports.properties} was abandoned, for example by a
 * JVM killed during its setup, and is re-created.
 *
 * <p>Idempotent: a slot whose admin port already responds is left alone.
 * Lets {@link PoolBootstrap#up} be re-run safely after a partial failure.
 *
 * <p>For GlassFish Embedded ({@link PoolConfig#embedded}), a slot is a block
 * of ports, with nothing to clone or start: provisioning it writes its
 * {@code ports.properties}. Each deployment starts a child on those ports,
 * which the stage directory records with the slot it serves, so a child its
 * test JVM left behind is found and ended.
 */
public final class PoolProvisioner {

    private static final Logger LOG = Logger.getLogger(PoolProvisioner.class.getName());

    /** Subdirectory under each slot dir holding the cloned GlassFish install. */
    public static final String SLOT_INSTALL_DIRNAME = "glassfish";

    /** Deadline for {@code start-domain} and {@code restart-domain}. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);

    /** Deadline for each command of a list, and for the restart check. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    /** Deadline for {@code stop-domain}. */
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(2);

    private static final int HEALTH_PROBE_TIMEOUT_MILLIS = 1000;

    /** How long a GlassFish Embedded child gets to stop before it's killed. */
    private static final Duration CHILD_STOP_TIMEOUT = Duration.ofSeconds(60);

    private final PoolConfig config;

    public PoolProvisioner(PoolConfig config) {
        this.config = config;
    }

    /**
     * Provision slot {@code idx}. Returns the slot's published
     * {@link SlotPorts} on success, or {@code null} when another JVM owns a
     * slot that hasn't published any yet.
     *
     * <p>Holds the slot lock while it checks and builds the slot, as the grow
     * path does, so a free lock always means nobody is building the slot.
     */
    public SlotPorts provisionSlot(int idx) throws IOException {
        if (!config.embedded()) {
            requireSource();
        }
        Path portsFile = PoolPaths.portsFile(config.poolDir(), idx);
        Files.createDirectories(PoolPaths.slotDir(config.poolDir(), idx));

        PoolLocks.Held lock = PoolLocks.tryLock(PoolPaths.lockFile(config.poolDir(), idx));
        if (lock == null) {
            // Another JVM owns the slot: it has leased it, or is growing or
            // restarting it, and publishes ports.properties when it's done.
            LOG.fine(() -> "Slot " + idx + " is owned by another JVM");
            return Files.exists(portsFile) ? SlotPorts.readFrom(portsFile) : null;
        }

        try (lock) {
            if (config.embedded()) {
                if (Files.exists(portsFile) && SlotPorts.readFrom(portsFile).isEmbedded()) {
                    LOG.fine(() -> "Slot " + idx + " already provisioned for GlassFish Embedded");
                    return SlotPorts.readFrom(portsFile);
                }
                return createEmbeddedSlot(idx);
            }
            if (Files.exists(portsFile) && isPortHealthy(config.adminPort(idx))) {
                LOG.fine(() -> "Slot " + idx + " already provisioned (adminPort=" + config.adminPort(idx) + ")");
                return SlotPorts.readFrom(portsFile);
            }
            return createSlot(idx);
        }
    }

    /**
     * Provision slot {@code idx} for a caller that holds its lock: a slot
     * {@link SlotLeaser} claimed to grow the pool or to recycle a dead or
     * abandoned slot. Unlike {@link #provisionSlot}, this skips the reuse
     * check and always re-creates the slot.
     */
    SlotPorts provisionClaimedSlot(int idx) throws IOException {
        if (config.embedded()) {
            return createEmbeddedSlot(idx);
        }
        requireSource();
        return createSlot(idx);
    }

    private void requireSource() {
        if (config.source() == null) {
            throw new IllegalStateException(
                    "PoolConfig.source is null — set " + PoolConfig.SYS_SOURCE
                    + " or pass it to the Maven plugin");
        }
    }

    private SlotPorts createSlot(int idx) throws IOException {
        int adminPort = config.adminPort(idx);
        int httpPort = config.httpPort(idx);
        int httpsPort = config.httpsPort(idx);

        Path slotDir = PoolPaths.slotDir(config.poolDir(), idx);
        Path slotInstall = slotDir.resolve(SLOT_INSTALL_DIRNAME);
        Path portsFile = PoolPaths.portsFile(config.poolDir(), idx);

        Files.createDirectories(slotDir);
        // A ports file left from the slot's previous life would advertise the
        // slot while it is being re-created.
        Files.deleteIfExists(portsFile);

        // A setup abandoned after start-domain, or a failed stop, can leave a
        // server running from the old install, on the slot's ports, and an
        // Embedded slot of an earlier build a child.
        endChildren(idx);
        stopIfStarted(idx, adminPort);
        if (isPortHealthy(adminPort)) {
            throw new IOException("Slot " + idx + ": a server still answers on admin port " + adminPort
                    + " and didn't stop");
        }

        // tryGrow may have pre-created an empty slot dir to claim the index;
        // wipe its contents (but not the dir itself) so a concurrent caller's
        // Files.exists check still sees the claim while we re-clone.
        if (Files.isDirectory(slotInstall)) {
            deleteRecursive(slotInstall);
        }

        LOG.info("Cloning source install for slot " + idx + " (adminPort=" + adminPort + ")");
        PoolSourceCloner.clone(config.source(), slotInstall);

        Path domainXml = domainXml(slotInstall);
        DomainXmlEditor.setPorts(domainXml, adminPort, httpPort, httpsPort);
        // A port of its own, so slots with a debugger don't clash, and on localhost only: a debugger runs any code
        // it likes in the server.
        DomainXmlEditor.setDebugAddress(domainXml, "localhost:" + config.debugPort(idx));
        DomainXmlEditor.setSystemProperties(domainXml, config.setup().systemProperties());
        DomainXmlEditor.setJvmOptions(domainXml, config.setup().jvmOptions());

        try {
            new AsAdmin(slotInstall).run(START_TIMEOUT, "start-domain", startDomainArgs());
        } catch (AsAdmin.AsAdminException e) {
            LOG.log(Level.SEVERE, "Slot " + idx + " start-domain failed", e);
            stopIfStarted(idx, adminPort);
            throw new IOException("Slot " + idx + " start-domain failed: " + e.getMessage(), e);
        }

        // --suspend starts a debugger too.
        int debugPort = (config.debug() || config.suspend()) ? config.debugPort(idx) : 0;
        SlotPorts ports = new SlotPorts(adminPort, httpPort, httpsPort, slotInstall.toString(), debugPort);
        try {
            runCommands(idx, ports, true);
            ports.writeTo(portsFile);
        } catch (IOException | RuntimeException e) {
            stopIfStarted(idx, adminPort);
            throw e;
        }
        LOG.info("Slot " + idx + " provisioned: adminPort=" + adminPort + ", http=" + httpPort);
        return ports;
    }

    /**
     * Create Embedded slot {@code idx}: end a child the slot still serves,
     * stop a {@code domain1} left from a pool of domains and remove its
     * install, then publish the slot's ports. Its children's debugger, on the
     * slot's debug port, is there when this pool starts with one.
     */
    private SlotPorts createEmbeddedSlot(int idx) throws IOException {
        Path slotDir = PoolPaths.slotDir(config.poolDir(), idx);
        Path portsFile = PoolPaths.portsFile(config.poolDir(), idx);
        Files.createDirectories(slotDir);
        Files.deleteIfExists(portsFile);

        endChildren(idx);
        Path slotInstall = slotDir.resolve(SLOT_INSTALL_DIRNAME);
        if (Files.isDirectory(slotInstall)) {
            int adminPort = config.adminPort(idx);
            stopIfStarted(idx, adminPort);
            if (isPortHealthy(adminPort)) {
                throw new IOException("Slot " + idx + ": a server still answers on admin port " + adminPort
                        + " and didn't stop");
            }
            deleteRecursive(slotInstall);
        }

        int debugPort = (config.debug() || config.suspend()) ? config.debugPort(idx) : 0;
        SlotPorts ports = SlotPorts.embedded(config.httpPort(idx), config.httpsPort(idx), debugPort,
                config.iiopPort(idx), config.iiopSslPort(idx), config.iiopMutualAuthPort(idx), config.jmsPort(idx),
                config.jmsRmiPort(idx));
        ports.writeTo(portsFile);
        LOG.info("Slot " + idx + " provisioned for GlassFish Embedded: http=" + ports.httpPort());
        return ports;
    }

    /**
     * The GlassFish Embedded children that a stage directory records as
     * serving slot {@code idx}, and that still run.
     */
    public List<ProcessHandle> children(int idx) {
        Path slotDir = PoolPaths.slotDir(config.poolDir(), idx);
        List<ProcessHandle> children = new ArrayList<>();
        for (Path stageDir : stageDirs(slotDir)) {
            children.addAll(EmbeddedProcesses.serving(stageDir, slotDir));
        }
        return children;
    }

    /**
     * The JVMs that a stage directory records as hosting GlassFish Embedded
     * for slot {@code idx}, and that still run: test JVMs, or a
     * {@code glassfish-pool:run}'s Maven. The pool never ends them.
     */
    public List<ProcessHandle> hosts(int idx) {
        Path slotDir = PoolPaths.slotDir(config.poolDir(), idx);
        List<ProcessHandle> hosts = new ArrayList<>();
        for (Path stageDir : stageDirs(slotDir)) {
            hosts.addAll(EmbeddedProcesses.hosts(stageDir, slotDir));
        }
        return hosts;
    }

    /**
     * End the GlassFish Embedded children that serve slot {@code idx}, and
     * remove their records. The caller holds the slot, so they're children a
     * JVM that held it before left behind, as a test fork that died.
     */
    public void endChildren(int idx) throws IOException {
        Path slotDir = PoolPaths.slotDir(config.poolDir(), idx);
        for (Path stageDir : stageDirs(slotDir)) {
            for (ProcessHandle child : EmbeddedProcesses.serving(stageDir, slotDir)) {
                LOG.info("Ending GlassFish Embedded's child " + child.pid() + ", which slot " + idx
                        + "'s previous holder left running");
                EmbeddedProcesses.end(child, CHILD_STOP_TIMEOUT);
                EmbeddedProcesses.forget(stageDir, child);
            }
        }
    }

    /**
     * Where the records of the children of slot {@code slotDir} are: in the
     * stage directory the pool's description names, and in the one the
     * slot's last child started from, which differs once {@code up} moved
     * the pool to another one, or back to domains.
     */
    private Set<Path> stageDirs(Path slotDir) {
        Set<Path> stageDirs = new LinkedHashSet<>();
        stageDir().ifPresent(stageDirs::add);
        EmbeddedProcesses.stageDirOf(slotDir).ifPresent(stageDirs::add);
        return stageDirs;
    }

    /**
     * Stop Embedded slot {@code idx}: end its children, and remove its
     * {@code ports.properties}, so the next build sets the slot up afresh,
     * with its own settings, as it does a domain slot that stopped. A domain
     * slot is left to {@link #stopSlot}.
     */
    public void stopEmbeddedSlot(int idx) throws IOException {
        endChildren(idx);
        Path portsFile = PoolPaths.portsFile(config.poolDir(), idx);
        try {
            if (Files.exists(portsFile) && SlotPorts.readFrom(portsFile).isEmbedded()) {
                Files.deleteIfExists(portsFile);
            }
        } catch (RuntimeException e) {
            // Half-written, or not written by this class: there's nothing to set up afresh.
            LOG.log(Level.FINE, "Could not read " + portsFile, e);
        }
    }

    /** The stage directory GlassFish Embedded runs from, as the pool's description names it, if there is one. */
    private Optional<Path> stageDir() {
        try {
            return EmbeddedPool.read(config.poolDir()).map(EmbeddedGlassFish::stageDir);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not read the pool's description of GlassFish Embedded", e);
            return Optional.empty();
        }
    }

    /**
     * Restart slot {@code idx} for its next lease, then run the post-start
     * list. The caller holds the slot lock. {@code ports.properties} is
     * removed first and written again at the end, so after a failed restart
     * or command, or a JVM killed in between, the slot is re-created rather
     * than leased as it is. A failure also stops the slot.
     */
    void restartSlot(int idx, SlotPorts ports) throws IOException {
        Path portsFile = PoolPaths.portsFile(config.poolDir(), idx);
        Files.deleteIfExists(portsFile);
        try {
            new AsAdmin(Paths.get(ports.glassFishHome())).run(START_TIMEOUT, "restart-domain", "domain1");
            runCommands(idx, ports, false);
            ports.writeTo(portsFile);
        } catch (IOException | RuntimeException e) {
            stopIfStarted(idx, ports.adminPort());
            throw e;
        }
    }

    /**
     * Run the setup's commands on slot {@code idx}, which has just started:
     * for a new domain the post-boot list and, if GlassFish asks for one, a
     * restart; then the post-start list. With {@code failOnCommandError}, a
     * failed command throws {@link PostBootCommands.CommandFailedException}.
     */
    private void runCommands(int idx, SlotPorts ports, boolean newDomain) {
        SlotSetup setup = config.setup();
        Path home = Paths.get(ports.glassFishHome());
        AsAdmin remote = new AsAdmin(home, ports.adminPort());
        if (newDomain && !setup.postBootCommands().isEmpty()) {
            runList(remote, setup.postBootCommands());
            if (isRestartRequired(remote)) {
                LOG.info("Restarting slot " + idx + ", as its post-boot commands require");
                new AsAdmin(home).run(START_TIMEOUT, "restart-domain", "domain1");
            }
        }
        runList(remote, setup.postStartCommands());
    }

    private void runList(AsAdmin remote, List<String> commandLines) {
        PostBootCommands.run(commandLines,
                (command, args) -> remote.run(COMMAND_TIMEOUT, command, args.toArray(String[]::new)),
                config.setup().failOnCommandError());
    }

    /** Whether the server holds changes it applies only after a restart. */
    private static boolean isRestartRequired(AsAdmin remote) {
        return remote.run(COMMAND_TIMEOUT, "_get-restart-required").lines()
                .map(String::trim)
                .anyMatch("true"::equals);
    }

    /**
     * Build the {@code start-domain} argument list: optional {@code --debug} /
     * {@code --suspend} (mirroring the managed container) followed by the domain
     * operand. {@code --suspend} blocks the server JVM until a debugger attaches.
     */
    private String[] startDomainArgs() {
        List<String> args = new ArrayList<>();
        if (config.debug()) {
            args.add("--debug");
        }
        if (config.suspend()) {
            args.add("--suspend");
        }
        args.add("domain1");
        return args.toArray(String[]::new);
    }

    /**
     * Stop slot {@code idx} if its admin port answers, so a failed setup
     * doesn't leave a GlassFish running behind it.
     */
    private void stopIfStarted(int idx, int adminPort) {
        if (isPortHealthy(adminPort)) {
            stopSlot(idx);
        }
    }

    /**
     * Stop a slot's GlassFish with {@code stop-domain}, and with
     * {@code stop-domain --kill} if that fails. Logs failures; a caller that
     * must know whether the server is gone probes its admin port afterwards.
     */
    public void stopSlot(int idx) {
        Path slotInstall = PoolPaths.slotDir(config.poolDir(), idx).resolve(SLOT_INSTALL_DIRNAME);
        if (!Files.isDirectory(slotInstall)) {
            return;
        }

        AsAdmin asadmin = new AsAdmin(slotInstall);
        try {
            asadmin.run(STOP_TIMEOUT, "stop-domain", "domain1");
            LOG.info("Slot " + idx + " stopped");
            return;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Slot " + idx + " stop-domain failed, killing it: " + e.getMessage());
        }

        try {
            asadmin.run(STOP_TIMEOUT, "stop-domain", "--kill", "domain1");
            LOG.info("Slot " + idx + " killed");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Slot " + idx + " stop-domain --kill failed: " + e.getMessage());
        }
    }

    /** A slot install's {@code domain.xml}. */
    static Path domainXml(Path slotInstall) {
        return slotInstall.resolve("glassfish").resolve("domains").resolve("domain1")
                .resolve("config").resolve("domain.xml");
    }

    /** TCP probe — same heuristic SlotLeaser uses to decide a slot is alive. */
    static boolean isPortHealthy(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HEALTH_PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Delete {@code root} and everything under it. Best-effort: what can't be deleted stays. */
    static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort.
                }
            });
        }
    }
}
