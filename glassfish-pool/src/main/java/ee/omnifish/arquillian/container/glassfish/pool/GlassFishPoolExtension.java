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

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/**
 * Registers {@link GlassFishPoolDeployableContainer} as the Arquillian
 * {@link DeployableContainer} implementation, plus {@link GroupSlotInference}
 * which lets the members of a {@code <group>} share one slot. Picked up via the
 * {@code META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension}
 * descriptor on the classpath.
 */
public class GlassFishPoolExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, GlassFishPoolDeployableContainer.class);
        builder.observer(GroupSlotInference.class);
    }
}
