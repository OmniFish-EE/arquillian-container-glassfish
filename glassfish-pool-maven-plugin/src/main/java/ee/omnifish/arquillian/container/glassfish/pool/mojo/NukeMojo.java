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

import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;

/**
 * Stop every slot AND remove the pool dir. Forceful reset; the next
 * {@code up} starts from a clean slate.
 */
@Mojo(name = "nuke", threadSafe = true, requiresProject = false)
public class NukeMojo extends AbstractPoolMojo {

    @Override
    public void execute() throws MojoExecutionException {
        try {
            PoolBootstrap.nuke(poolConfig());
        } catch (IOException e) {
            throw new MojoExecutionException("Pool nuke failed: " + e.getMessage(), e);
        }
    }
}
