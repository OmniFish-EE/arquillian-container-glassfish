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

import java.util.logging.Logger;

import org.jboss.arquillian.config.descriptor.api.ArquillianDescriptor;
import org.jboss.arquillian.config.descriptor.api.ContainerDef;
import org.jboss.arquillian.config.descriptor.api.GroupDef;
import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.event.SetupContainer;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;

/**
 * Defaults a grouped pool container's {@code slotGroup} to its Arquillian
 * {@code <group>} qualifier, so the members of a group (e.g. {@code http} +
 * {@code https}) share one leased slot — one physical GlassFish — with no
 * per-container configuration.
 *
 * <p>Off by default: an Arquillian {@code <group>} generally means several
 * servers a test drives together (clustering/failover), which want distinct
 * instances. Enable with {@code -Dgf.pool.shareGroupSlot=true} when a group is
 * instead "many views of one server" (the http+https idiom, matching the
 * managed container's behavior); the members then share a slot, and a size-1
 * pool suffices. An explicit {@code slotGroup} property (or
 * {@code -Dgf.pool.slotGroup}) always wins over inference — set it directly to
 * share without the global switch, or give members distinct values to keep them
 * apart.
 *
 * <p>Mutates the {@link ContainerDef} on {@link SetupContainer} at a precedence
 * ahead of {@code ContainerLifecycleController}, which only then maps the
 * {@link ContainerDef} onto the typed config — so the inferred value reaches
 * {@link GlassFishPoolConfiguration#getSlotGroup()}. Only pool containers are
 * touched.
 */
public class GroupSlotInference {

    private static final Logger LOG = Logger.getLogger(GroupSlotInference.class.getName());

    static final String SHARE_GROUP_SLOT = "gf.pool.shareGroupSlot";
    static final String SLOT_GROUP_PROPERTY = "slotGroup";

    @Inject
    private Instance<ArquillianDescriptor> descriptor;

    public void inferSlotGroup(@Observes(precedence = 100) SetupContainer event) {
        Container<?> container = event.getContainer();
        if (!(container.getDeployableContainer() instanceof GlassFishPoolDeployableContainer)) {
            return;
        }
        ContainerDef config = container.getContainerConfiguration();
        String group = inferredSlotGroup(descriptor.get(), container.getName(),
                config.getContainerProperty(SLOT_GROUP_PROPERTY),
                System.getProperty(GlassFishPoolConfiguration.SYS_SLOT_GROUP),
                shareGroupSlotEnabled());
        if (group != null) {
            config.property(SLOT_GROUP_PROPERTY, group);
            LOG.info("Container '" + container.getName() + "' shares slot group '" + group
                    + "' with its <group> siblings (one GlassFish for the group)");
        }
    }

    /**
     * The {@code slotGroup} to infer for {@code containerName}, or {@code null}
     * to leave it untouched. Inference is skipped when disabled, or when sharing
     * is already pinned explicitly (a per-container {@code slotGroup} property or
     * the {@code -Dgf.pool.slotGroup} global both win), or when the container is
     * standalone.
     */
    static String inferredSlotGroup(ArquillianDescriptor descriptor, String containerName,
            String explicitProperty, String globalDefault, boolean enabled) {
        if (!enabled || hasText(explicitProperty) || hasText(globalDefault)) {
            return null;
        }
        return groupNameFor(descriptor, containerName);
    }

    /**
     * The qualifier of the group {@code containerName} belongs to, or
     * {@code null} if it is standalone. A container declared in more than one
     * group (which Arquillian permits) resolves to the first-declared one.
     */
    static String groupNameFor(ArquillianDescriptor descriptor, String containerName) {
        if (descriptor == null) {
            return null;
        }
        for (GroupDef group : descriptor.getGroups()) {
            for (ContainerDef member : group.getGroupContainers()) {
                if (containerName.equals(member.getContainerName())) {
                    return group.getGroupName();
                }
            }
        }
        return null;
    }

    private static boolean shareGroupSlotEnabled() {
        return Boolean.getBoolean(SHARE_GROUP_SLOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }
}
