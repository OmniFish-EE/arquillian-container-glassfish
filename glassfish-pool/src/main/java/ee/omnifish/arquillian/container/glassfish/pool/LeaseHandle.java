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

/**
 * A held slot lease as seen by the container: either a private lease
 * ({@link Direct}) or one shared with sibling containers in the same JVM
 * ({@link Shared}, backed by {@link SharedSlotRegistry}). Lets the container's
 * {@code start()}/{@code stop()} treat both the same way.
 *
 * <p>A handle is confined to its owning container, whose lifecycle Arquillian
 * drives on a single thread — hence the plain (non-volatile) {@code released}
 * guards need no memory barrier. The shared <em>slot</em> state lives in
 * {@link SharedSlotRegistry}, which does synchronize.
 */
interface LeaseHandle {

    SlotPorts ports();

    int slotIndex();

    /**
     * Relinquish this holder's claim. Returns the underlying {@link SlotLease}
     * for the caller to finalize (restart-if-configured, then close) iff this
     * was the last holder of the slot; otherwise {@code null} (a shared slot
     * still in use by siblings). Idempotent — a second call returns
     * {@code null}.
     */
    SlotLease release();

    /** Private, single-holder lease — the default (non-shared) path. */
    final class Direct implements LeaseHandle {

        private final SlotLease lease;
        private boolean released;

        Direct(SlotLease lease) {
            this.lease = lease;
        }

        @Override
        public SlotPorts ports() {
            return lease.ports();
        }

        @Override
        public int slotIndex() {
            return lease.slotIndex();
        }

        @Override
        public SlotLease release() {
            if (released) {
                return null;
            }
            released = true;
            return lease;
        }
    }

    /** Shared lease: many holders, one slot. Backed by {@link SharedSlotRegistry}. */
    final class Shared implements LeaseHandle {

        private final SharedSlotRegistry.Key key;
        private final SlotLease lease;
        private boolean released;

        Shared(SharedSlotRegistry.Key key, SlotLease lease) {
            this.key = key;
            this.lease = lease;
        }

        @Override
        public SlotPorts ports() {
            return lease.ports();
        }

        @Override
        public int slotIndex() {
            return lease.slotIndex();
        }

        @Override
        public SlotLease release() {
            if (released) {
                return null;
            }
            released = true;
            return SharedSlotRegistry.release(key);
        }
    }
}
