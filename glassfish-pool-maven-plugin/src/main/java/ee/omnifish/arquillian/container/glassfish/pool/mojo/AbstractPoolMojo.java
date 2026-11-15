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
import ee.omnifish.arquillian.container.glassfish.pool.SlotSetup;

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
     * for the {@code up} and {@code provision} goals; ignored otherwise. When
     * a {@code <distribution>} is unpacked and this is unset, it defaults to
     * the zip's top-level directory under {@code stageDir}.
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
     * Each slot's maximum heap size, baked into its {@code domain.xml} as
     * {@code -Xmx<maxHeapSize>}, e.g. {@code 2048m}. Mirrors
     * {@code glassfish.maxHeapSize} on the managed container.
     */
    @Parameter(property = "glassfish.maxHeapSize")
    protected String maxHeapSize;

    /**
     * What follows {@code -ea} in each slot's {@code domain.xml}, e.g.
     * {@code :org.jboss.cdi.tck...} for {@code -ea:org.jboss.cdi.tck...}.
     * Mirrors {@code glassfish.enableAssertions} on the managed container.
     */
    @Parameter(property = "glassfish.enableAssertions")
    protected String enableAssertions;

    /**
     * {@code asadmin} commands run once per slot, after its domain first
     * starts, one per line, for setup whose effects persist: users,
     * resources, {@code set} calls. Quotes group words; backslashes are passed
     * on as they are. Blank lines and lines starting with {@code #} are
     * skipped. If the commands leave changes that need a restart, the slot
     * restarts once before its post-start commands.
     */
    @Parameter(property = "gf.pool.postBootCommands")
    protected String postBootCommands;

    /**
     * {@code asadmin} commands run after every slot start and restart, one
     * per line, for what a restart loses. Same syntax as
     * {@link #postBootCommands}.
     */
    @Parameter(property = "gf.pool.postStartCommands")
    protected String postStartCommands;

    /**
     * Whether a failed command fails the slot's setup: {@code up}, or the test
     * JVM's lease when it creates the slot. After a {@code restartOnRelease}
     * restart, the slot is stopped instead and re-created before its next
     * lease. When {@code false}, a failure is logged and the setup continues.
     */
    @Parameter(property = "gf.pool.failOnCommandError", defaultValue = "true")
    protected boolean failOnCommandError;

    /**
     * Start each slot's domain in debug mode. Shares the {@code glassfish.debug}
     * property with the managed container. Only valid for a single-slot pool
     * ({@code gf.pool.size=1}) — all slot clones share one debug port.
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
        SlotSetup setup = new SlotSetup(
                PoolConfig.parseLines(systemProperties),
                maxHeapSize,
                enableAssertions,
                PoolConfig.parseLines(postBootCommands),
                PoolConfig.parseLines(postStartCommands),
                failOnCommandError);
        return new PoolConfig(poolDir.toPath(), source, poolSize, adminPortBase, portStride, setup, debug, suspend);
    }
}
