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
import java.nio.file.Paths;
import java.util.function.Consumer;

import ee.omnifish.glassfish.admin.ServerLogTailer;

/**
 * Active hold on one slot. Closed when the test JVM is done with the slot;
 * also closed automatically at JVM exit (via the lock file's {@link FileChannel}
 * being released).
 */
public final class SlotLease implements AutoCloseable {

    private final int slotIndex;
    private final SlotPorts ports;
    private final PoolLocks.Held lock;
    private volatile boolean released;
    private ServerLogTailer serverLog;

    SlotLease(int slotIndex, SlotPorts ports, PoolLocks.Held lock) {
        this.slotIndex = slotIndex;
        this.ports = ports;
        this.lock = lock;
    }

    public int slotIndex() {
        return slotIndex;
    }

    public SlotPorts ports() {
        return ports;
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
            lock.close();
        }
    }
}
