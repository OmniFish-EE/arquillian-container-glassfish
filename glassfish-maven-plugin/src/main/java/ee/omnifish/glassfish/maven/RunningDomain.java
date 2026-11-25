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
import java.util.function.Consumer;

import org.apache.maven.plugin.logging.Log;

import ee.omnifish.glassfish.admin.ServerLogMessages;
import ee.omnifish.glassfish.admin.ServerLogTailer;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;

/**
 * The domain a run deploys to, with its {@code server.log} mirrored to the
 * console, and the one ending of the run, however it ends: from a shutdown
 * hook on plain Maven's Ctrl+C, after mvnd's Ctrl+C interrupts the wait,
 * after a failed deploy, or once the domain stopped otherwise, as with
 * {@code glassfish:stop} from another terminal. For {@code glassfish:run},
 * the ending stops the domain; for {@code glassfish-pool:run}, it hands the
 * domain back to the pool.
 */
public final class RunningDomain implements AutoCloseable {

    /** What ends the run, before the mirror stops. */
    @FunctionalInterface
    public interface Ending {
        void end() throws IOException;
    }

    private final GlassFishDomain domain;
    private final ServerLogTailer serverLog;
    private final Ending ending;
    private final Log log;
    private final Thread shutdownHook;

    private boolean ended;

    /**
     * Follows the domain's {@code server.log} from now on, printing each line
     * after {@code prefix}, and ends the run when the JVM exits.
     *
     * @param messagesOnly whether to print only the messages of the log's
     *        records, without their headers, as {@link ServerLogMessages} does
     * @param goal the goal that runs, which names the shutdown hook's thread
     */
    public RunningDomain(GlassFishDomain domain, String prefix, boolean messagesOnly, String goal, Log log,
            Ending ending) {
        this.domain = domain;
        this.ending = ending;
        this.log = log;
        Consumer<String> console = line -> System.out.println(prefix + line);
        serverLog = ServerLogTailer.follow(domain.serverLog(), messagesOnly ? new ServerLogMessages(console) : console);
        shutdownHook = new Thread(this::end, goal + " ending");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Log where a debugger attaches, with {@code debugger}, and where the
     * domain's whole {@code server.log} is, as the console may show only its
     * messages.
     */
    public void logWhere(boolean debugger) {
        if (debugger) {
            log.info("Debugger at " + domain.debugAddress());
        }
        log.info("Server log at " + domain.serverLog());
    }

    /**
     * Wait until the domain's process exits, or this thread is interrupted,
     * then end the run. The interrupt is restored once the run has ended:
     * {@code asadmin} would give up at once otherwise.
     */
    public void untilStopped() {
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
     * Run the ending, then stop the mirror, so what the ending makes the
     * domain log still shows. A second call waits until the first has
     * finished: during a shutdown, the JVM exits once its hooks return.
     */
    synchronized void end() {
        if (ended) {
            return;
        }

        ended = true;
        try {
            ending.end();
        } catch (IOException e) {
            log.warn(e.getMessage());
        } finally {
            serverLog.close();
        }
    }

    /** {@link #end}, and drop the shutdown hook, which has nothing left to do. */
    @Override
    public void close() {
        end();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is shutting down, and the hook ran the same ending.
        }
    }
}
