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
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provisions a single slot: clone the source install, patch the per-slot
 * ports into {@code domain.xml}, run {@code asadmin start-domain}, then
 * publish a {@code ports.properties} so the test JVM can pick up the slot.
 *
 * <p>Idempotent: a slot whose admin port already responds is left alone.
 * Lets {@link PoolBootstrap#up} be re-run safely after a partial failure.
 */
public final class PoolProvisioner {

    private static final Logger LOG = Logger.getLogger(PoolProvisioner.class.getName());

    /** Subdirectory under each slot dir holding the cloned GlassFish install. */
    public static final String SLOT_INSTALL_DIRNAME = "glassfish";

    private static final int HEALTH_PROBE_TIMEOUT_MILLIS = 1000;

    private final PoolConfig config;

    public PoolProvisioner(PoolConfig config) {
        this.config = config;
    }

    /**
     * Provision slot {@code idx}. Returns the slot's published
     * {@link SlotPorts} on success.
     */
    public SlotPorts provisionSlot(int idx) throws IOException {
        if (config.source() == null) {
            throw new IllegalStateException(
                    "PoolConfig.source is null — set " + PoolConfig.SYS_SOURCE
                    + " or pass it to the Maven plugin");
        }
        int adminPort = config.adminPort(idx);
        int httpPort = config.httpPort(idx);
        int httpsPort = config.httpsPort(idx);

        Path slotDir = PoolPaths.slotDir(config.poolDir(), idx);
        Path slotInstall = slotDir.resolve(SLOT_INSTALL_DIRNAME);
        Path portsFile = PoolPaths.portsFile(config.poolDir(), idx);

        // Idempotency: skip the heavy work if the slot is already alive and the
        // ports file is consistent.
        if (Files.exists(portsFile) && isPortHealthy(adminPort)) {
            LOG.fine(() -> "Slot " + idx + " already healthy on adminPort=" + adminPort);
            return SlotPorts.readFrom(portsFile);
        }

        Files.createDirectories(slotDir);

        // tryGrow may have pre-created an empty slot dir to claim the index;
        // wipe its contents (but not the dir itself) so a concurrent caller's
        // Files.exists check still sees the claim while we re-clone.
        if (Files.isDirectory(slotInstall)) {
            deleteRecursive(slotInstall);
        }

        LOG.info("Cloning source install for slot " + idx + " (adminPort=" + adminPort + ")");
        PoolSourceCloner.clone(config.source(), slotInstall);

        Path domainXml = slotInstall
                .resolve("glassfish").resolve("domains").resolve("domain1")
                .resolve("config").resolve("domain.xml");
        DomainXmlEditor.setPorts(domainXml, adminPort, httpPort, httpsPort);

        AsAdmin asadmin = new AsAdmin(slotInstall);
        try {
            asadmin.run("start-domain", "domain1");
        } catch (AsAdmin.AsAdminException e) {
            LOG.log(Level.SEVERE, "Slot " + idx + " start-domain failed", e);
            throw new IOException("Slot " + idx + " start-domain failed: " + e.getMessage(), e);
        }

        SlotPorts ports = new SlotPorts(adminPort, httpPort, httpsPort, slotInstall.toString());
        ports.writeTo(portsFile);
        LOG.info("Slot " + idx + " provisioned: adminPort=" + adminPort + ", http=" + httpPort);
        return ports;
    }

    /** Stop a slot's GlassFish if its admin port is responding. Best-effort; logs failures. */
    public void stopSlot(int idx) {
        Path slotInstall = PoolPaths.slotDir(config.poolDir(), idx).resolve(SLOT_INSTALL_DIRNAME);
        if (!Files.isDirectory(slotInstall)) {
            return;
        }
        try {
            new AsAdmin(slotInstall).run("stop-domain", "domain1");
            LOG.info("Slot " + idx + " stopped");
        } catch (AsAdmin.AsAdminException e) {
            LOG.log(Level.WARNING, "Slot " + idx + " stop-domain failed: " + e.getMessage());
        }
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

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort; provisioning will overwrite what it needs.
                }
            });
        }
    }
}
