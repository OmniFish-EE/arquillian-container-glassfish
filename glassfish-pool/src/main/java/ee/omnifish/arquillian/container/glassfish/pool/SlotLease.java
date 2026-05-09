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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * Active hold on one slot. Closed when the test JVM is done with the slot;
 * also closed automatically at JVM exit (via the lock file's {@link FileChannel}
 * being released).
 */
public final class SlotLease implements AutoCloseable {

    private final int slotIndex;
    private final SlotPorts ports;
    private final FileChannel channel;
    private final FileLock lock;
    private volatile boolean released;

    SlotLease(int slotIndex, SlotPorts ports, FileChannel channel, FileLock lock) {
        this.slotIndex = slotIndex;
        this.ports = ports;
        this.channel = channel;
        this.lock = lock;
    }

    public int slotIndex() {
        return slotIndex;
    }

    public SlotPorts ports() {
        return ports;
    }

    @Override
    public synchronized void close() {
        if (released) {
            return;
        }
        released = true;
        try {
            lock.release();
        } catch (IOException ignored) {
            // Lock already released or channel closed — same end state.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // FD released either way.
        }
    }
}
