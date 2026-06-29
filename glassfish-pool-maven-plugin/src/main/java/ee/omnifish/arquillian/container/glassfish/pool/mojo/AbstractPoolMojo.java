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

import java.io.File;
import java.nio.file.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;

/**
 * Shared {@code @Parameter} surface for every glassfish-pool goal — keeps
 * the per-goal mojos to a few lines each.
 */
abstract class AbstractPoolMojo extends AbstractMojo {

    /** Where the pool of slots is laid out. Created if missing. */
    @Parameter(property = "gf.pool.dir",
               defaultValue = "${project.build.directory}/pool",
               required = true)
    protected File poolDir;

    /**
     * Source GlassFish install used as the template for slot clones. Required
     * for the {@code up} and {@code provision} goals; ignored otherwise.
     */
    @Parameter(property = "gf.pool.source")
    protected File poolSource;

    /** Number of slots the {@code up} goal provisions. Default 1. */
    @Parameter(property = "gf.pool.size", defaultValue = "1")
    protected int poolSize;

    /** Admin port for slot 1; subsequent slots step by {@link #portStride}. */
    @Parameter(property = "gf.pool.adminBase", defaultValue = "14848")
    protected int adminPortBase;

    /** Per-slot port stride. Must be {@code >= 10}. */
    @Parameter(property = "gf.pool.portStride", defaultValue = "100")
    protected int portStride;

    /**
     * Skip this goal entirely. Useful for letting {@code -DskipTests} or
     * {@code -Dmaven.test.skip} disable the pool lifecycle without removing
     * the binding.
     */
    @Parameter(property = "gf.pool.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * Newline-separated {@code key=value} pairs baked into each slot's
     * {@code domain.xml} as {@code <jvm-options>-Dkey=value</jvm-options>}.
     * Mirrors {@code glassfish.systemProperties} on
     * {@code arquillian-glassfish-server-managed} — same syntax, same
     * comment ({@code #}) and blank-line handling. Use this for properties
     * that must be on the GlassFish JVM at startup (e.g.
     * {@code javax.net.ssl.trustStorePassword}, which a PKCS12 truststore
     * needs in order to load any trust anchors).
     */
    @Parameter(property = "glassfish.systemProperties")
    protected String systemProperties;

    /**
     * Start each slot's domain in debug mode. Shares the {@code glassfish.debug}
     * property with the managed container.
     */
    @Parameter(property = "glassfish.debug", defaultValue = "false")
    protected boolean debug;

    /**
     * Start each slot's domain suspended, waiting for a debugger to attach.
     * Shares the {@code glassfish.suspend} property with the managed container.
     * Only valid for a single-slot pool ({@code gf.pool.size=1}) — suspend
     * blocks the server JVM and all slot clones share one debug port.
     */
    @Parameter(property = "glassfish.suspend", defaultValue = "false")
    protected boolean suspend;

    protected PoolConfig poolConfig() {
        Path source = (poolSource == null) ? null : poolSource.toPath();
        return new PoolConfig(poolDir.toPath(), source, poolSize, adminPortBase, portStride,
                PoolConfig.parseSystemProperties(systemProperties), debug, suspend);
    }
}
