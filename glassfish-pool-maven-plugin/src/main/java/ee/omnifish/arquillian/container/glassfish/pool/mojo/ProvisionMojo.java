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

import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;

/**
 * Provision a single extra slot at index {@code -Dgf.pool.slot=N}.
 *
 * <p>Useful for ad-hoc additions to an already-running pool. Day-to-day,
 * {@code SlotLeaser} grows the pool itself when test JVMs need it, so this
 * goal is mostly a debugging aid.
 */
@Mojo(name = "provision", threadSafe = true)
public class ProvisionMojo extends AbstractStagingPoolMojo {

    @Parameter(property = "gf.pool.slot", required = true)
    private int slot;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("glassfish-pool:provision skipped (gf.pool.skip=true)");
            return;
        }
        try {
            PoolBootstrap.provisionSlot(poolConfig(), slot, buildPreStage());
        } catch (IOException e) {
            throw new MojoExecutionException("Slot " + slot + " provisioning failed: " + e.getMessage(), e);
        }
    }
}
