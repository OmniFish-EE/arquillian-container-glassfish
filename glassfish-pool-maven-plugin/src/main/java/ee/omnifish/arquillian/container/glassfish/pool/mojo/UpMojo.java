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
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;

/**
 * Provision and start every slot. Default phase {@code pre-integration-test}
 * so it composes naturally with maven-failsafe-plugin.
 *
 * <p>Skips when {@code -DskipTests} or {@code -Dmaven.test.skip} is set: if
 * the build isn't running tests, there's no reason to pay for a pool.
 */
@Mojo(name = "up", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class UpMojo extends AbstractStagingPoolMojo {

    @Parameter(property = "skipTests", defaultValue = "false")
    protected boolean skipTests;

    @Parameter(property = "maven.test.skip", defaultValue = "false")
    protected boolean mavenTestSkip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("glassfish-pool:up skipped (gf.pool.skip=true)");
            return;
        }
        if (skipTests) {
            getLog().info("glassfish-pool:up skipped (-DskipTests)");
            return;
        }
        if (mavenTestSkip) {
            getLog().info("glassfish-pool:up skipped (-Dmaven.test.skip)");
            return;
        }
        try {
            PoolBootstrap.up(poolConfig(), buildPreStage());
        } catch (IOException e) {
            throw new MojoExecutionException("Pool up failed: " + e.getMessage(), e);
        }
    }
}
