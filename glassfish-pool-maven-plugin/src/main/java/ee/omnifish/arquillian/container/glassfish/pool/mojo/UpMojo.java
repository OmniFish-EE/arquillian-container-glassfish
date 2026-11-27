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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;
import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;

import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_ADMIN_BASE;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_ENABLE_ASSERTIONS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_FAIL_ON_COMMAND_ERROR;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_MAX_HEAP_SIZE;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_POOL_DIR;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_PORT_STRIDE;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_SIZE;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_POST_BOOT_COMMANDS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_POST_START_COMMANDS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_SOURCE;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_SYSTEM_PROPERTIES;

/**
 * Provision and start every slot. Default phase {@code pre-integration-test}
 * so it composes naturally with maven-failsafe-plugin.
 *
 * <p>Skips when {@code -DskipTests} or {@code -Dmaven.test.skip} is set: if
 * the build isn't running tests, there's no reason to pay for a pool.
 *
 * <p>After the pool is up, publishes its layout and slot setup
 * ({@link #FORWARDED_PROPERTIES}) as project properties so failsafe can
 * forward them to the test JVMs as {@code ${gf.pool.dir}}-style references,
 * which Maven resolves when failsafe runs, after this goal. The test JVMs
 * create slots of their own when the pool grows or a slot dies, and set them
 * up the same way. {@link PoolLifecycleParticipant} adds those references to
 * failsafe's {@code systemPropertyVariables} when the plugin is declared with
 * {@code <extensions>true</extensions>}.
 *
 * <p>It also publishes {@code gf.pool.size}, the size of the pool it started,
 * for the pom itself: with {@code <forkCount>${gf.pool.size}</forkCount>},
 * failsafe follows the pool size, which may come from {@code -T}. The test
 * JVMs don't read it, so it isn't forwarded.
 *
 * <p>And it records the source it staged as {@value #STAGED_SOURCE}, for a
 * {@code glassfish-pool:run} whose {@code package} it ran in.
 */
@Mojo(name = "up", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class UpMojo extends AbstractStagingPoolMojo {

    /**
     * The source an {@code up} of this build staged and started the pool
     * from. Unlike {@code gf.pool.source}, which a pom may set itself, only
     * {@code up} sets it.
     */
    static final String STAGED_SOURCE = "gf.pool.stagedSource";

    /**
     * Pool layout and slot setup the test JVMs read: published as project
     * properties after {@code up}, and forwarded to failsafe by the extension.
     */
    static final List<String> FORWARDED_PROPERTIES = List.of(
            SYS_POOL_DIR,
            SYS_SOURCE,
            SYS_ADMIN_BASE,
            SYS_PORT_STRIDE,
            SYS_SYSTEM_PROPERTIES,
            SYS_MAX_HEAP_SIZE,
            SYS_ENABLE_ASSERTIONS,
            SYS_POST_BOOT_COMMANDS,
            SYS_POST_START_COMMANDS,
            SYS_FAIL_ON_COMMAND_ERROR);

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

        requireCommandLineMode();
        // Before poolConfig(): may derive poolSource from <distribution>.
        Runnable preStage = buildPreStage();
        PoolConfig poolConfig = poolConfig();
        if (multiModule) {
            // Before the start, so the slots of a pool that starts only partly are stopped too.
            PoolLifecycleParticipant.stopAfterBuild(poolConfig);
        }
        try {
            startPool(poolConfig, preStage);
        } catch (IOException e) {
            throw new MojoExecutionException("Pool up failed: " + e.getMessage(), e);
        }

        publish(project.getProperties(), poolConfig, getLog());
        recordStaging(project.getProperties(), poolConfig);
    }

    /**
     * Record the source {@code config} staged in {@code projectProperties},
     * as {@value #STAGED_SOURCE}. Package-private for unit tests.
     */
    static void recordStaging(Properties projectProperties, PoolConfig config) {
        if (config.source() != null) {
            projectProperties.setProperty(STAGED_SOURCE, config.source().toAbsolutePath().toString());
        }
    }

    /** Package-private for unit tests, which make it fail. */
    void startPool(PoolConfig poolConfig, Runnable preStage) throws IOException {
        PoolBootstrap.up(poolConfig, preStage);
    }

    /**
     * Seed {@code projectProperties} with the pool layout, slot setup and
     * size of {@code config}.
     * <p>
     * A property that is already set (in the pom, or by an earlier execution)
     * is left alone so users can still override it; a value differing from the
     * pool actually started is warned about, since test JVMs would then look
     * for the pool in the wrong place. Package-private for unit tests.
     */
    static void publish(Properties projectProperties, PoolConfig config, Log log) {
        for (var entry : createPoolProperties(config).entrySet()) {
            String propertyKey = entry.getKey();
            String newPropertyValue = entry.getValue();
            String existingPropertyValue = projectProperties.getProperty(propertyKey);

            if (existingPropertyValue == null) {
                projectProperties.setProperty(propertyKey, newPropertyValue);
                log.debug("Published " + propertyKey + "=" + newPropertyValue);
            } else if (!existingPropertyValue.equals(newPropertyValue)) {
                log.warn(
                    "Project property " + propertyKey + "=" + existingPropertyValue +
                    " differs from the pool started at " + newPropertyValue +
                    "; leaving it as set");
            }
        }
    }

    private static Map<String, String> createPoolProperties(PoolConfig config) {
        Map<String, String> properties = new LinkedHashMap<>();

        properties.put(SYS_POOL_DIR, config.poolDir().toAbsolutePath().toString());
        if (config.source() != null) {
            properties.put(SYS_SOURCE, config.source().toAbsolutePath().toString());
        }
        properties.put(SYS_ADMIN_BASE, String.valueOf(config.adminPortBase()));
        properties.put(SYS_PORT_STRIDE, String.valueOf(config.portStride()));
        properties.putAll(config.setup().toSystemProperties());
        properties.put(SYS_SIZE, String.valueOf(config.size()));

        return properties;
    }
}
