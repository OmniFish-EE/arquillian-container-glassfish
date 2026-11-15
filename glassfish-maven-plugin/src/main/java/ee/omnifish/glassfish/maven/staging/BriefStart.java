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
package ee.omnifish.glassfish.maven.staging;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ee.omnifish.glassfish.admin.AsAdmin;
import ee.omnifish.glassfish.admin.AsAdmin.AsAdminException;
import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;

import static ee.omnifish.glassfish.admin.DomainXmlEditor.ADMIN_LISTENER;
import static ee.omnifish.glassfish.admin.DomainXmlEditor.HTTPS_LISTENER;
import static ee.omnifish.glassfish.admin.DomainXmlEditor.HTTP_LISTENER;
import static ee.omnifish.glassfish.maven.staging.GlassFishStaging.DOMAIN_CONFIG;

/**
 * Starts a staged install briefly, to run its {@link StagingCommands} on it.
 * Their effects are then in the install before anything else starts it.
 *
 * <p>The domain runs on three free ports, so it doesn't clash with servers
 * that already run, and gets its own ports back afterwards, except for a port
 * a command set. A shutdown hook stops it if the build is interrupted.
 */
final class BriefStart {

    private static final Logger LOG = LoggerFactory.getLogger(BriefStart.class);

    private static final String DOMAIN = "domain1";

    /** Deadline for {@code start-domain}. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);

    /** Deadline for each command. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    /** Deadline for {@code stop-domain}. */
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(2);

    private static final int PROBE_TIMEOUT_MILLIS = 1000;

    private BriefStart() {
    }

    /**
     * Start the install at {@code home} on free ports, run {@code commands}
     * on it, stop it, and put its own ports back.
     *
     * @throws IOException if the domain doesn't start or stop, or, with
     *         {@link StagingCommands#failOnError}, a command fails
     */
    static void runCommands(Path home, StagingCommands commands) throws IOException {
        Path domainXml = home.resolve(DOMAIN_CONFIG).resolve("domain.xml");
        Map<String, String> ownPorts = DomainXmlEditor.ports(domainXml);
        Map<String, String> briefPorts = freePorts();
        DomainXmlEditor.setPorts(domainXml, briefPorts);
        int adminPort = Integer.parseInt(briefPorts.get(ADMIN_LISTENER));

        Thread stopOnExit = new Thread(() -> stop(home), "glassfish-brief-start-stop");
        Runtime.getRuntime().addShutdownHook(stopOnExit);
        try {
            LOG.info("Starting " + home + " briefly, on admin port " + adminPort + ", for its post-boot commands");
            new AsAdmin(home).run(START_TIMEOUT, "start-domain", DOMAIN);
            AsAdmin remote = new AsAdmin(home, adminPort);
            PostBootCommands.run(commands.lines(),
                    (command, args) -> remote.run(COMMAND_TIMEOUT, command, args.toArray(String[]::new)),
                    commands.failOnError());
        } catch (AsAdminException | CommandFailedException e) {
            throw new IOException("Post-boot commands on " + home + " failed: " + e.getMessage(), e);
        } finally {
            stop(home);
            removeShutdownHook(stopOnExit);
        }

        if (isAnswering(adminPort)) {
            throw new IOException(home + " still answers on admin port " + adminPort + " after stop-domain");
        }
        restorePorts(domainXml, ownPorts, briefPorts);
    }

    /**
     * Stop a server that a staging which broke off may have left running
     * from {@code home}: a build killed during the brief start, before its
     * shutdown hook could run, or a stop that failed. GlassFish keeps
     * {@code config/pid} while it runs.
     *
     * @throws IOException if the admin port in the install's
     *         {@code domain.xml} still answers afterwards, or can't be read,
     *         so the caller keeps the install, rather than wipe it under a
     *         running server
     */
    static void stopLeftover(Path home) throws IOException {
        if (home == null || !Files.exists(home.resolve(DOMAIN_CONFIG).resolve("pid"))) {
            return;
        }

        LOG.info("Stopping the server that the last staging may have left running from " + home);
        try {
            stop(home);
        } catch (RuntimeException e) {
            LOG.warn("Could not stop the server running from " + home + ": " + e.getMessage());
        }
        Path domainXml = home.resolve(DOMAIN_CONFIG).resolve("domain.xml");
        String adminPort = DomainXmlEditor.ports(domainXml).get(ADMIN_LISTENER);
        if (adminPort == null || !adminPort.matches("\\d+") || isAnswering(Integer.parseInt(adminPort))) {
            throw new IOException("Could not confirm that the server the last staging started from " + home
                    + " has stopped (admin port " + adminPort + "). Stop it, then build again.");
        }
    }

    /** Three ports nothing listens on right now, keyed by the listener each is for. */
    private static Map<String, String> freePorts() throws IOException {
        try (ServerSocket admin = new ServerSocket(0);
             ServerSocket http = new ServerSocket(0);
             ServerSocket https = new ServerSocket(0)) {
            return Map.of(
                    ADMIN_LISTENER, String.valueOf(admin.getLocalPort()),
                    HTTP_LISTENER, String.valueOf(http.getLocalPort()),
                    HTTPS_LISTENER, String.valueOf(https.getLocalPort()));
        }
    }

    /**
     * Put the domain's own ports back: each port that still holds its
     * temporary value, so a port a command set keeps the command's value.
     */
    private static void restorePorts(Path domainXml, Map<String, String> ownPorts, Map<String, String> briefPorts)
            throws IOException {
        Map<String, String> current = DomainXmlEditor.ports(domainXml);
        Map<String, String> restore = new HashMap<>();
        for (Map.Entry<String, String> own : ownPorts.entrySet()) {
            if (briefPorts.get(own.getKey()).equals(current.get(own.getKey()))) {
                restore.put(own.getKey(), own.getValue());
            }
        }
        DomainXmlEditor.setPorts(domainXml, restore);
    }

    /**
     * Stop the domain with {@code stop-domain}, and with
     * {@code stop-domain --kill} if that fails. Logs failures; the caller
     * probes the admin port. Clears the thread's interrupt status meanwhile,
     * which a cancelled start leaves set, so the stop still runs.
     */
    private static void stop(Path home) {
        boolean interrupted = Thread.interrupted();
        try {
            AsAdmin asadmin = new AsAdmin(home);
            try {
                asadmin.run(STOP_TIMEOUT, "stop-domain", DOMAIN);
                return;
            } catch (AsAdminException e) {
                LOG.warn("stop-domain failed on " + home + ", killing it: " + e.getMessage());
            }

            try {
                asadmin.run(STOP_TIMEOUT, "stop-domain", "--kill", DOMAIN);
            } catch (AsAdminException e) {
                LOG.warn("stop-domain --kill failed on " + home + ": " + e.getMessage());
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is shutting down, and the hook runs anyway.
        }
    }

    private static boolean isAnswering(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
