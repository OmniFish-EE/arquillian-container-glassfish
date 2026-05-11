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
import java.util.Collections;
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
 */
public final class PoolConfig {

    /** Default per-slot port stride. 10 ports per domain plus headroom. */
    public static final int DEFAULT_PORT_STRIDE = 100;

    /** Default starting admin port for slot 1. */
    public static final int DEFAULT_ADMIN_BASE = 14848;

    private static final int MIN_PORT_STRIDE = 10;

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

    private final Path poolDir;
    private final Path source;
    private final int size;
    private final int adminPortBase;
    private final int portStride;
    private final List<String> systemProperties;

    public PoolConfig(Path poolDir, Path source, int size, int adminPortBase, int portStride) {
        this(poolDir, source, size, adminPortBase, portStride, List.of());
    }

    public PoolConfig(Path poolDir, Path source, int size, int adminPortBase, int portStride,
                      List<String> systemProperties) {
        if (portStride < MIN_PORT_STRIDE) {
            throw new IllegalArgumentException(
                    "portStride must be >= " + MIN_PORT_STRIDE + " (got " + portStride + ")");
        }
        this.poolDir = Objects.requireNonNull(poolDir, "poolDir");
        this.source = source;
        this.size = size;
        this.adminPortBase = adminPortBase;
        this.portStride = portStride;
        this.systemProperties = (systemProperties == null)
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(systemProperties));
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

    /**
     * Extra {@code -D…} jvm-options to bake into each slot's {@code domain.xml}.
     * Each entry is a bare {@code key=value} pair (no leading {@code -D}).
     */
    public List<String> systemProperties() {
        return systemProperties;
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
                parseSystemProperties(System.getProperty(SYS_SYSTEM_PROPERTIES)));
    }

    /**
     * Parse a multiline {@code key=value} block into a list. Blank lines and
     * lines starting with {@code #} are dropped, mirroring managed's
     * {@code glassfish.systemProperties} convention.
     */
    public static List<String> parseSystemProperties(String multiline) {
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
