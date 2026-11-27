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

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

import ee.omnifish.glassfish.admin.ServerLogTailer;

/**
 * Active hold on one slot. Closed when the test JVM is done with the slot;
 * also closed automatically at JVM exit (via the lock file's {@link FileChannel}
 * being released).
 *
 * <p>On GlassFish Embedded, the slot runs one child at a time, which the
 * archive that claims it holds, also for containers that share the slot.
 */
public final class SlotLease implements AutoCloseable {

    private final int slotIndex;
    private final SlotPorts ports;
    private final PoolLocks.Held lock;
    private final PoolLocks.Held runLock;
    private final Path slotDir;
    private volatile boolean released;
    private ServerLogTailer serverLog;
    private String child;

    SlotLease(int slotIndex, SlotPorts ports, PoolLocks.Held lock) {
        this(slotIndex, ports, lock, null, null);
    }

    /**
     * With {@code runLock}, the slot's {@code run.lock}, for a
     * {@code glassfish-pool:run}, and the slot's directory.
     */
    SlotLease(int slotIndex, SlotPorts ports, PoolLocks.Held lock, PoolLocks.Held runLock, Path slotDir) {
        this.slotIndex = slotIndex;
        this.ports = ports;
        this.lock = lock;
        this.runLock = runLock;
        this.slotDir = slotDir;
    }

    public int slotIndex() {
        return slotIndex;
    }

    public SlotPorts ports() {
        return ports;
    }

    /** The slot's directory, where its GlassFish Embedded children get their working directories. */
    public Path slotDir() {
        return slotDir;
    }

    /**
     * Claim the slot's one GlassFish Embedded child for the archive
     * {@code archiveId}: false while another archive holds it.
     */
    public synchronized boolean claimChild(String archiveId) {
        if (child != null) {
            return false;
        }

        child = archiveId;
        return true;
    }

    /** Release the claim of {@code archiveId}, and only its. */
    public synchronized void releaseChild(String archiveId) {
        if (archiveId.equals(child)) {
            child = null;
        }
    }

    /** The archive whose child the slot runs, or {@code null}. */
    synchronized String child() {
        return child;
    }

    /**
     * Pass on what the slot's server writes to its log from now on, until
     * this lease is released, so for all containers that share the slot.
     */
    synchronized void followServerLog(Consumer<String> lines) {
        serverLog = ServerLogTailer.follow(
                Paths.get(ports.glassFishHome(), "glassfish", "domains", "domain1", "logs", "server.log"), lines);
    }

    @Override
    public synchronized void close() {
        if (released) {
            return;
        }
        released = true;
        try {
            if (serverLog != null) {
                serverLog.close();
            }
        } finally {
            // The run's mark goes first: whoever leases the slot next may mark it again.
            if (runLock != null) {
                runLock.close();
            }
            lock.close();
        }
    }
}
