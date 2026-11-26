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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Provisioning-side pool config — used by {@link PoolBootstrap} and the
 * Maven plugin. The test-JVM-side config (used by the Arquillian extension)
 * is {@link GlassFishPoolConfiguration}.
 *
 * <p>Each slot N occupies a contiguous port window starting at
 * {@code adminPortBase + N*portStride}. Port stride must be {@code >= 10}
 * because a GlassFish domain uses 10 ports.
 *
 * <p>On GlassFish Embedded ({@link #embedded}), a slot is that window, and no
 * more: each deployment starts a child of its own on its ports, which uses
 * them as {@code +1} HTTP, {@code +2} HTTPS, {@code +3} to {@code +5} its
 * IIOP listeners, {@code +6} the JMS broker, {@code +7} the broker's RMI
 * registry and {@code +9} its debugger.
 */
public final class PoolConfig {

    /** Default per-slot port stride. 10 ports per domain plus headroom. */
    public static final int DEFAULT_PORT_STRIDE = 100;

    /** Default starting admin port for slot 1. */
    public static final int DEFAULT_ADMIN_BASE = 14848;

    private static final int MIN_PORT_STRIDE = 10;

    /** Where a slot's debug port is in its window: the last of its first ten ports. */
    private static final int DEBUG_PORT_OFFSET = 9;

    /** Where an Embedded slot's first IIOP listener is in its window; the SSL ones follow. */
    private static final int IIOP_PORT_OFFSET = 3;

    /** Where an Embedded slot's JMS broker is in its window; its RMI registry follows. */
    private static final int JMS_PORT_OFFSET = 6;

    public static final String SYS_POOL_DIR = "gf.pool.dir";
    public static final String SYS_SOURCE = "gf.pool.source";
    public static final String SYS_SIZE = "gf.pool.size";
    public static final String SYS_ADMIN_BASE = "gf.pool.adminBase";
    public static final String SYS_PORT_STRIDE = "gf.pool.portStride";
    /**
     * Newline-separated {@code key=value} pairs to bake into each slot's
     * {@code domain.xml} as {@code <jvm-options>-Dkey=value</jvm-options>}.
     * Mirrors {@code glassfish.systemProperties} on the managed container.
     */
    public static final String SYS_SYSTEM_PROPERTIES = "gf.pool.systemProperties";
    /** Each slot's {@code -Xmx} value. Mirrors {@code glassfish.maxHeapSize} on the managed container. */
    public static final String SYS_MAX_HEAP_SIZE = "gf.pool.maxHeapSize";
    /** What follows each slot's {@code -ea}. Mirrors {@code glassfish.enableAssertions} on the managed container. */
    public static final String SYS_ENABLE_ASSERTIONS = "gf.pool.enableAssertions";
    /** Newline-separated {@code asadmin} commands run once per slot. See {@link SlotSetup}. */
    public static final String SYS_POST_BOOT_COMMANDS = "gf.pool.postBootCommands";
    /** Newline-separated {@code asadmin} commands run after every slot start. See {@link SlotSetup}. */
    public static final String SYS_POST_START_COMMANDS = "gf.pool.postStartCommands";
    /** Whether a failed command fails a slot's setup. Default {@code true}. */
    public static final String SYS_FAIL_ON_COMMAND_ERROR = "gf.pool.failOnCommandError";

    private final Path poolDir;
    private final Path source;
    private final int size;
    private final int adminPortBase;
    private final int portStride;
    private final SlotSetup setup;
    private final boolean debug;
    private final boolean suspend;
    private final boolean embedded;

    public PoolConfig(Path poolDir, Path source, int size, int adminPortBase, int portStride) {
        this(poolDir, source, size, adminPortBase, portStride, SlotSetup.NONE, false, false);
    }

    public PoolConfig(Path poolDir, Path source, int size, int adminPortBase, int portStride,
                      List<String> systemProperties) {
        this(poolDir, source, size, adminPortBase, portStride, systemProperties, false, false);
    }

    public PoolConfig(Path poolDir, Path source, int size, int adminPortBase, int portStride,
                      List<String> systemProperties, boolean debug, boolean suspend) {
        this(poolDir, source, size, adminPortBase, portStride,
                new SlotSetup(systemProperties, null, null, List.of(), List.of(), true), debug, suspend);
    }

    public PoolConfig(Path poolDir, Path source, int size, int adminPortBase, int portStride,
                      SlotSetup setup, boolean debug, boolean suspend) {
        this(poolDir, source, size, adminPortBase, portStride, setup, debug, suspend, false);
    }

    /** @param embedded whether the slots run GlassFish Embedded, a child per deployment */
    public PoolConfig(Path poolDir, Path source, int size, int adminPortBase, int portStride,
                      SlotSetup setup, boolean debug, boolean suspend, boolean embedded) {
        if (portStride < MIN_PORT_STRIDE) {
            throw new IllegalArgumentException(
                    "portStride must be >= " + MIN_PORT_STRIDE + " (got " + portStride + ")");
        }
        if (suspend && size > 1) {
            throw new IllegalArgumentException(
                    "glassfish.suspend requires a single-slot pool (gf.pool.size=1); got size=" + size
                    + ". Suspend blocks each slot's server JVM until a debugger attaches to it "
                    + "— run the suspended pool with -T1.");
        }
        this.poolDir = Objects.requireNonNull(poolDir, "poolDir");
        this.source = source;
        this.size = size;
        this.adminPortBase = adminPortBase;
        this.portStride = portStride;
        this.setup = (setup == null) ? SlotSetup.NONE : setup;
        this.debug = debug;
        this.suspend = suspend;
        this.embedded = embedded;
    }

    public Path poolDir() {
        return poolDir;
    }

    /** Source GlassFish install used as the template for slot clones. */
    public Path source() {
        return source;
    }

    public int size() {
        return size;
    }

    public int adminPortBase() {
        return adminPortBase;
    }

    public int portStride() {
        return portStride;
    }

    /** Admin port for slot {@code idx} (1-based). Slot 1 lands on {@link #adminPortBase}. */
    public int adminPort(int idx) {
        return adminPortBase + (idx - 1) * portStride;
    }

    public int httpPort(int idx) {
        return adminPort(idx) + 1;
    }

    public int httpsPort(int idx) {
        return adminPort(idx) + 2;
    }

    /** The port slot {@code idx}'s debugger listens on, on localhost, when it starts with one. */
    public int debugPort(int idx) {
        return adminPort(idx) + DEBUG_PORT_OFFSET;
    }

    /** The port of the IIOP listener of an Embedded slot {@code idx}'s child. */
    public int iiopPort(int idx) {
        return adminPort(idx) + IIOP_PORT_OFFSET;
    }

    /** The port of the SSL IIOP listener of an Embedded slot {@code idx}'s child. */
    public int iiopSslPort(int idx) {
        return iiopPort(idx) + 1;
    }

    /** The port of the IIOP listener with mutual authentication of an Embedded slot {@code idx}'s child. */
    public int iiopMutualAuthPort(int idx) {
        return iiopPort(idx) + 2;
    }

    /** The port of the JMS broker of an Embedded slot {@code idx}'s child. */
    public int jmsPort(int idx) {
        return adminPort(idx) + JMS_PORT_OFFSET;
    }

    /** The port of the JMS broker's RMI registry of an Embedded slot {@code idx}'s child. */
    public int jmsRmiPort(int idx) {
        return jmsPort(idx) + 1;
    }

    /**
     * Whether the slots run GlassFish Embedded: each a block of ports, on
     * which each deployment starts a child of its own, rather than a domain.
     */
    public boolean embedded() {
        return embedded;
    }

    /**
     * Extra {@code -D…} jvm-options to bake into each slot's {@code domain.xml}.
     * Each entry is a bare {@code key=value} pair (no leading {@code -D}).
     */
    public List<String> systemProperties() {
        return setup.systemProperties();
    }

    /** The settings and commands every slot gets. */
    public SlotSetup setup() {
        return setup;
    }

    /**
     * Start each slot's domain in debug mode ({@code start-domain --debug}),
     * with its debugger on its own {@link #debugPort}. Sourced from the
     * {@code glassfish.debug} property, shared with the managed container.
     */
    public boolean debug() {
        return debug;
    }

    /**
     * Start each slot's domain suspended, waiting for a debugger to attach
     * ({@code start-domain --suspend}). Sourced from {@code glassfish.suspend},
     * shared with the managed container. Only valid for a single-slot pool
     * (enforced in the constructor) — see that guard for the rationale.
     */
    public boolean suspend() {
        return suspend;
    }

    /**
     * Construct from system properties. Used by {@link PoolBootstrap} so a
     * Maven antrun {@code <java>} invocation can pass everything via
     * {@code <sysproperty>} without a CLI dance.
     */
    public static PoolConfig fromSystemProperties() {
        return new PoolConfig(
                Paths.get(requireSys(SYS_POOL_DIR)),
                pathOrNull(System.getProperty(SYS_SOURCE)),
                Integer.parseInt(System.getProperty(SYS_SIZE, "1")),
                Integer.parseInt(System.getProperty(SYS_ADMIN_BASE, String.valueOf(DEFAULT_ADMIN_BASE))),
                Integer.parseInt(System.getProperty(SYS_PORT_STRIDE, String.valueOf(DEFAULT_PORT_STRIDE))),
                SlotSetup.fromSystemProperties(),
                Boolean.getBoolean("glassfish.debug"),
                Boolean.getBoolean("glassfish.suspend"),
                EmbeddedPool.exists(Paths.get(requireSys(SYS_POOL_DIR))));
    }

    /**
     * The config a test JVM leases from {@code poolDir} with, built from the
     * system properties {@link #fromSystemProperties} reads, as {@code up}
     * forwards them. {@code debug} and {@code suspend} aren't forwarded, so
     * slots the test JVM creates start without them. Without a forwarded
     * source, the test JVM can't create domain slots, only lease existing
     * ones. The pool runs GlassFish Embedded when {@code up} described it in
     * {@code poolDir} ({@link EmbeddedPool}).
     */
    static PoolConfig forTestJvm(Path poolDir) {
        return new PoolConfig(
                poolDir,
                pathOrNull(System.getProperty(SYS_SOURCE)),
                0,
                Integer.getInteger(SYS_ADMIN_BASE, DEFAULT_ADMIN_BASE),
                Integer.getInteger(SYS_PORT_STRIDE, DEFAULT_PORT_STRIDE),
                SlotSetup.fromSystemProperties(),
                false,
                false,
                EmbeddedPool.exists(poolDir));
    }

    /**
     * Parse a multiline {@code key=value} block into a list. Blank lines and
     * lines starting with {@code #} are dropped, mirroring managed's
     * {@code glassfish.systemProperties} convention.
     */
    public static List<String> parseSystemProperties(String multiline) {
        return parseLines(multiline);
    }

    /**
     * Split a multiline block, such as a command list, into trimmed lines.
     * Blank lines and lines starting with {@code #} are dropped.
     */
    public static List<String> parseLines(String multiline) {
        if (multiline == null || multiline.isBlank()) {
            return List.of();
        }
        return multiline.lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .filter(s -> !s.startsWith("#"))
                        .collect(Collectors.toUnmodifiableList());
    }

    private static Path pathOrNull(String s) {
        return (s == null || s.isEmpty()) ? null : Paths.get(s);
    }

    private static String requireSys(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("System property '" + key + "' is required");
        }
        return v;
    }
}
