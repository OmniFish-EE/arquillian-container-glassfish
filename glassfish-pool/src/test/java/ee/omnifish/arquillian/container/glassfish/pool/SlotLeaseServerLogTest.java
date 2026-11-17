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

import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A lease follows its slot's {@code server.log} while it holds the slot. */
class SlotLeaseServerLogTest {

    @TempDir
    Path tmp;

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    @Test
    void aLeaseFollowsItsSlotsLogUntilItIsReleased() throws Exception {
        Path log = serverLog("written before the lease\n");
        SlotLease lease = lease();
        lease.followServerLog(lines::add);

        Files.writeString(log, "written during the lease\n", APPEND);
        lease.close();
        Files.writeString(log, "written after the lease\n", APPEND);

        assertEquals(List.of("written during the lease"), lines);
    }

    /** Only the first container leases the slot, so the others see each line once too. */
    @Test
    void containersThatShareASlotFollowItsLogOnce() throws Exception {
        Path log = serverLog("");
        AtomicInteger leased = new AtomicInteger();
        SharedSlotRegistry.LeaseSupplier supplier = () -> {
            leased.incrementAndGet();
            SlotLease lease = lease();
            lease.followServerLog(lines::add);
            return lease;
        };
        SharedSlotRegistry.Key key = new SharedSlotRegistry.Key("group", tmp);
        LeaseHandle http = SharedSlotRegistry.acquire(key, supplier);
        LeaseHandle https = SharedSlotRegistry.acquire(key, supplier);

        Files.writeString(log, "written while shared\n", APPEND);
        assertNull(http.release());
        https.release().close();

        assertEquals(1, leased.get());
        assertEquals(List.of("written while shared"), lines);
    }

    private Path serverLog(String content) throws IOException {
        Path logs = Files.createDirectories(tmp.resolve("slot-1/glassfish/glassfish/domains/domain1/logs"));
        return Files.writeString(logs.resolve("server.log"), content);
    }

    private SlotLease lease() throws IOException {
        SlotPorts ports = new SlotPorts(14848, 14849, 14850, tmp.resolve("slot-1/glassfish").toString());
        return new SlotLease(1, ports, PoolLocks.tryLock(tmp.resolve("slot-1/lock")));
    }
}
