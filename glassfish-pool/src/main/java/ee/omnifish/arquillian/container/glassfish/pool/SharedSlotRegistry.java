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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JVM-wide registry of shared slot leases, keyed by {@code (slotGroup, poolDir)}.
 * Lets several Arquillian containers in one test JVM — e.g. the {@code http}
 * and {@code https} members of an arquillian.xml {@code <group>} — share ONE
 * leased slot (one physical GlassFish) instead of each leasing its own.
 *
 * <p>Reference-counted: the first {@link #acquire} leases a real slot, later
 * ones attach to it, and the last {@link #release} hands the lease back for
 * finalization. This restores the managed container's "one instance, many
 * views" semantics — a single slot already exposes both http and https ports
 * (see {@link SlotPorts}), so each sharing container just publishes its own
 * default.
 *
 * <p>Without sharing, an N-container group on a size-1 pool cannot run: each
 * container leases independently, so the group needs N slots (or
 * {@code gf.pool.source} forwarded to grow into them).
 */
final class SharedSlotRegistry {

    /** Leases a slot; may block and may throw, hence not a plain {@link java.util.function.Supplier}. */
    @FunctionalInterface
    interface LeaseSupplier {
        SlotLease get() throws IOException, InterruptedException;
    }

    private static final Object LOCK = new Object();
    private static final Map<Key, Entry> ENTRIES = new HashMap<>();

    private SharedSlotRegistry() {
    }

    /**
     * Attach to the slot shared under {@code key}, leasing one via
     * {@code supplier} if this is the first caller.
     *
     * <p>The lease is taken outside the registry lock so a slow {@code lease()}
     * (polling/growing) can't stall acquirers of <em>other</em> groups. If two
     * threads race the <em>same</em> key, the loser closes its redundant lease
     * and shares the winner's. Note that race needs a pool large enough for the
     * loser to lease a second slot at all; in Arquillian's normal flow
     * containers in one JVM start serially on one thread, so it doesn't arise.
     */
    static LeaseHandle acquire(Key key, LeaseSupplier supplier) throws IOException, InterruptedException {
        synchronized (LOCK) {
            Entry existing = ENTRIES.get(key);
            if (existing != null) {
                existing.refCount++;
                return new LeaseHandle.Shared(key, existing.lease);
            }
        }
        SlotLease leased = supplier.get();
        synchronized (LOCK) {
            Entry existing = ENTRIES.get(key);
            if (existing != null) {
                existing.refCount++;
                leased.close(); // lost the race — release the redundant slot
                return new LeaseHandle.Shared(key, existing.lease);
            }
            ENTRIES.put(key, new Entry(leased));
            return new LeaseHandle.Shared(key, leased);
        }
    }

    /**
     * Drop one holder of {@code key}. Returns the underlying {@link SlotLease}
     * for the caller to finalize (restart-if-configured, then close) iff this
     * was the last holder; otherwise {@code null}.
     */
    static SlotLease release(Key key) {
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(key);
            if (entry == null) {
                return null; // already gone — defensive against double release
            }
            if (--entry.refCount > 0) {
                return null;
            }
            ENTRIES.remove(key);
            return entry.lease;
        }
    }

    /** Whether no slot is currently shared — used by tests to assert no leaked refcounts. */
    static boolean isIdle() {
        synchronized (LOCK) {
            return ENTRIES.isEmpty();
        }
    }

    /** Composite key: same group AND same pool dir share a slot. */
    static final class Key {

        private final String group;
        private final Path poolDir;

        Key(String group, Path poolDir) {
            this.group = Objects.requireNonNull(group, "group");
            this.poolDir = Objects.requireNonNull(poolDir, "poolDir");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return group.equals(other.group) && poolDir.equals(other.poolDir);
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, poolDir);
        }

        @Override
        public String toString() {
            return "slotGroup=" + group + " @ " + poolDir;
        }
    }

    private static final class Entry {

        final SlotLease lease;
        int refCount = 1;

        Entry(SlotLease lease) {
            this.lease = lease;
        }
    }
}
