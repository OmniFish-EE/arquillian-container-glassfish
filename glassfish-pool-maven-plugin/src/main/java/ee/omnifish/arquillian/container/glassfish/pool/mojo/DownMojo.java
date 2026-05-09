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
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;

/**
 * Stop every slot's domain. Default phase {@code post-integration-test} so
 * it pairs with {@link UpMojo}.
 */
@Mojo(name = "down", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class DownMojo extends AbstractPoolMojo {

    @Override
    public void execute() {
        if (skip) {
            getLog().info("glassfish-pool:down skipped (gf.pool.skip=true)");
            return;
        }
        PoolBootstrap.down(poolConfig());
    }
}
