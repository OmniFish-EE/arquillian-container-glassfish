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

import java.nio.file.Path;

/**
 * Filesystem layout of a pool. All slot dirs and per-slot files live under
 * a single {@code poolDir}. Centralised here so callers don't sprinkle
 * "slot-N" string concatenation across the module.
 */
final class PoolPaths {

    static final String SLOT_PREFIX = "slot-";
    static final String LOCK_FILE = "lock";
    static final String PORTS_FILE = "ports.properties";
    static final String GROW_LOCK = "grow.lock";

    private PoolPaths() {
    }

    static Path slotDir(Path poolDir, int slot) {
        return poolDir.resolve(SLOT_PREFIX + slot);
    }

    static Path lockFile(Path poolDir, int slot) {
        return slotDir(poolDir, slot).resolve(LOCK_FILE);
    }

    static Path portsFile(Path poolDir, int slot) {
        return slotDir(poolDir, slot).resolve(PORTS_FILE);
    }

    static Path growLock(Path poolDir) {
        return poolDir.resolve(GROW_LOCK);
    }

    static Integer slotIndex(String dirName) {
        if (!dirName.startsWith(SLOT_PREFIX)) {
            return null;
        }
        try {
            return Integer.parseInt(dirName.substring(SLOT_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
