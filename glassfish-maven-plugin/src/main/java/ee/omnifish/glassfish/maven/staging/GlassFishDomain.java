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
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ee.omnifish.glassfish.admin.AsAdmin;
import ee.omnifish.glassfish.admin.AsAdmin.AsAdminException;
import ee.omnifish.glassfish.admin.DomainXmlEditor;

import static ee.omnifish.glassfish.admin.DomainXmlEditor.ADMIN_LISTENER;
import static ee.omnifish.glassfish.maven.staging.GlassFishStaging.DOMAIN_CONFIG;

/**
 * {@code domain1} of an install, as {@code glassfish:start} and
 * {@code glassfish:stop} run it: started and stopped with {@code asadmin}, and
 * known to run by the {@code config/pid} file GlassFish keeps while it runs,
 * naming a process that's alive. A stale {@code config/pid}, left by a server
 * that was killed, doesn't count.
 */
public final class GlassFishDomain {

    public static final String DOMAIN = "domain1";

    /** Deadline for {@code stop-domain}. */
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(2);

    /** How long a stopped server's process may take to end. */
    private static final Duration DEFAULT_EXIT_WAIT = Duration.ofSeconds(10);

    private static final int PROBE_TIMEOUT_MILLIS = 1000;

    private static final Pattern DEBUG_ADDRESS = Pattern.compile("debug-options=\"[^\"]*address=([^,\" ]+)");

    private final Path home;
    private final Duration exitWait;

    /** @param home the install root, with {@code glassfish/bin/asadmin} under it */
    public GlassFishDomain(Path home) {
        this(home, DEFAULT_EXIT_WAIT);
    }

    /** Package-private for unit tests, which don't wait as long. */
    GlassFishDomain(Path home, Duration exitWait) {
        this.home = home;
        this.exitWait = exitWait;
    }

    public Path home() {
        return home;
    }

    /** The process of the running domain, by its {@code config/pid}, if that process is alive. */
    public Optional<ProcessHandle> process() {
        Path pidFile = home.resolve(DOMAIN_CONFIG).resolve("pid");
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    public boolean isRunning() {
        return process().isPresent();
    }

    /** The admin port in the domain's {@code domain.xml}. */
    public int adminPort() throws IOException {
        String port = DomainXmlEditor.ports(home.resolve(DOMAIN_CONFIG).resolve("domain.xml")).get(ADMIN_LISTENER);
        if (port == null || !port.matches("\\d+")) {
            throw new IOException("No admin port in the domain.xml of " + home + ": " + port);
        }

        return Integer.parseInt(port);
    }

    /** Where the debugger attaches with {@code start-domain --debug}, from the domain's debug options. */
    public String debugAddress() {
        try {
            Matcher address = DEBUG_ADDRESS.matcher(Files.readString(home.resolve(DOMAIN_CONFIG).resolve("domain.xml")));
            return address.find() ? address.group(1) : "9009";
        } catch (IOException e) {
            return "9009";
        }
    }

    /**
     * Run {@code start-domain}, which returns once the admin endpoint
     * answers. If it doesn't within {@code timeout}, {@code asadmin} is
     * killed with what it started, so a server JVM that waits for a debugger
     * doesn't stay behind.
     */
    public void start(boolean debug, boolean suspend, Duration timeout) {
        List<String> args = new ArrayList<>();
        if (debug) {
            args.add("--debug");
        }
        if (suspend) {
            args.add("--suspend");
        }
        args.add(DOMAIN);
        new AsAdmin(home).run(timeout, "start-domain", args.toArray(String[]::new));
    }

    /**
     * Stop the domain with a local {@code stop-domain}, which checks that it
     * reaches this domain, and with {@code stop-domain --kill} if this
     * domain's process still runs after that. A domain that doesn't run is
     * left alone, also when another server answers on its admin port.
     *
     * @return whether the domain ran
     * @throws IOException if the domain's process still runs afterwards
     */
    public boolean stop() throws IOException {
        Optional<ProcessHandle> process = process();
        if (process.isEmpty()) {
            return false;
        }

        AsAdmin asadmin = new AsAdmin(home);
        try {
            asadmin.run(STOP_TIMEOUT, "stop-domain", DOMAIN);
        } catch (AsAdminException e) {
            // Killed below, if it still runs.
        }
        if (hasEnded(process.get())) {
            return true;
        }

        try {
            asadmin.run(STOP_TIMEOUT, "stop-domain", "--kill", DOMAIN);
        } catch (AsAdminException e) {
            // Reported below, if it still runs.
        }
        if (!hasEnded(process.get())) {
            throw new IOException(DOMAIN + " of " + home + " still runs, as process " + process.get().pid()
                    + ", after stop-domain and stop-domain --kill");
        }

        return true;
    }

    /** Whether something answers on {@code port} on this machine. */
    public static boolean isAnswering(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasEnded(ProcessHandle process) {
        try {
            process.onExit().get(exitWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Still running: decided by isAlive below.
        }

        return !process.isAlive();
    }
}
