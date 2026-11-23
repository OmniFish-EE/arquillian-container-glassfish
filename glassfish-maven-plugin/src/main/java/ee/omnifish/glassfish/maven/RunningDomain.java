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
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.apache.maven.plugin.logging.Log;

import ee.omnifish.glassfish.admin.ServerLogTailer;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;

/**
 * The domain {@code glassfish:run} started, with its {@code server.log}
 * mirrored to the console, and the one stop that ends it, however the run
 * ends: from a shutdown hook on plain Maven's Ctrl+C, after mvnd's Ctrl+C
 * interrupts the wait, after a failed deploy, or once the domain stopped
 * otherwise, as with {@code glassfish:stop} from another terminal.
 */
final class RunningDomain implements AutoCloseable {

    private final GlassFishDomain domain;
    private final ServerLogTailer serverLog;
    private final Log log;
    private final Thread shutdownHook = new Thread(this::stop, "glassfish:run stop");

    private volatile int adminPort;
    private boolean stopped;

    /**
     * Follows the domain's {@code server.log} from now on, printing each line
     * after {@code prefix}, and stops the domain when the JVM exits.
     */
    RunningDomain(GlassFishDomain domain, int adminPort, String prefix, Log log) {
        this.domain = domain;
        this.adminPort = adminPort;
        this.log = log;
        serverLog = ServerLogTailer.follow(domain.serverLog(), line -> System.out.println(prefix + line));
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /** The admin port it answers on, which a restart may change. */
    void adminPort(int adminPort) {
        this.adminPort = adminPort;
    }

    /**
     * Wait until the domain's process exits, or this thread is interrupted,
     * then stop it. The interrupt is restored once the domain has stopped:
     * {@code asadmin} would give up at once otherwise.
     */
    void untilStopped() {
        boolean interrupted = false;
        try {
            Optional<ProcessHandle> process = domain.process();
            if (process.isPresent()) {
                process.get().onExit().get();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (ExecutionException e) {
            // onExit() doesn't fail.
            throw new IllegalStateException(e);
        } finally {
            close();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Stop the domain, then the mirror, so its shutdown still shows. A second
     * call waits until the first has finished: during a shutdown, the JVM
     * exits once its hooks return.
     */
    synchronized void stop() {
        if (stopped) {
            return;
        }

        stopped = true;
        try {
            if (domain.stop(adminPort)) {
                log.info("GlassFish at " + domain.home() + " stopped");
            }
        } catch (IOException e) {
            log.warn(e.getMessage());
        } finally {
            serverLog.close();
        }
    }

    /** {@link #stop}, and drop the shutdown hook, which has nothing left to do. */
    @Override
    public void close() {
        stop();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is shutting down, and the hook ran the same stop.
        }
    }
}
