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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands;

import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_ENABLE_ASSERTIONS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_FAIL_ON_COMMAND_ERROR;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_MAX_HEAP_SIZE;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_POST_BOOT_COMMANDS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_POST_START_COMMANDS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_SYSTEM_PROPERTIES;

/**
 * What every slot gets besides its ports: server settings baked into its
 * {@code domain.xml}, and the {@code asadmin} commands run on it. {@code up}
 * and the pool's Arquillian Container, which creates slots when the pool
 * grows or a slot dies, set slots up from the same values: {@code up}
 * forwards them to the test JVMs under the names {@link #fromSystemProperties}
 * reads.
 *
 * <ul>
 *   <li>Post-boot commands run once per slot, after its domain first starts,
 *       for setup whose effects persist, such as users and resources.</li>
 *   <li>Post-start commands run after every start and restart, for what a
 *       restart loses.</li>
 *   <li>With {@code failOnCommandError}, a failed command fails the slot's
 *       setup; otherwise it is logged and the setup continues.</li>
 * </ul>
 */
public final class SlotSetup {

    /** No settings and no commands. */
    public static final SlotSetup NONE = new SlotSetup(List.of(), null, null, List.of(), List.of(), true);

    private final List<String> systemProperties;
    private final String maxHeapSize;
    private final String enableAssertions;
    private final List<String> postBootCommands;
    private final List<String> postStartCommands;
    private final boolean failOnCommandError;

    /**
     * An empty {@code maxHeapSize} or {@code enableAssertions} counts as unset.
     *
     * @param systemProperties {@code key=value} pairs, baked in as {@code -Dkey=value} jvm-options
     * @param maxHeapSize the {@code -Xmx} value, e.g. {@code 2048m}, or {@code null}
     * @param enableAssertions what follows {@code -ea}, e.g. {@code :org.jboss.cdi.tck...}, or {@code null}
     * @param postBootCommands {@code asadmin} command lines run once per slot
     * @param postStartCommands {@code asadmin} command lines run after every start
     * @param failOnCommandError whether a failed command fails the slot's setup
     * @throws IllegalArgumentException if a command line has an unclosed quote
     */
    public SlotSetup(List<String> systemProperties, String maxHeapSize, String enableAssertions,
                     List<String> postBootCommands, List<String> postStartCommands, boolean failOnCommandError) {
        this.systemProperties = copy(systemProperties);
        this.maxHeapSize = emptyToNull(maxHeapSize);
        this.enableAssertions = emptyToNull(enableAssertions);
        this.postBootCommands = copy(postBootCommands);
        this.postStartCommands = copy(postStartCommands);
        this.failOnCommandError = failOnCommandError;

        // An unclosed quote fails here, rather than halfway through a slot's setup.
        this.postBootCommands.forEach(PostBootCommands::words);
        this.postStartCommands.forEach(PostBootCommands::words);
    }

    public List<String> systemProperties() {
        return systemProperties;
    }

    public String maxHeapSize() {
        return maxHeapSize;
    }

    public String enableAssertions() {
        return enableAssertions;
    }

    public List<String> postBootCommands() {
        return postBootCommands;
    }

    public List<String> postStartCommands() {
        return postStartCommands;
    }

    public boolean failOnCommandError() {
        return failOnCommandError;
    }

    /** The {@code -Xmx} and {@code -ea} jvm-options, mapped as the managed Arquillian Container maps them. */
    Map<String, String> jvmOptions() {
        return DomainXmlEditor.heapAndAssertionOptions(maxHeapSize, enableAssertions);
    }

    /**
     * The JVM options of a GlassFish Embedded child, which has no
     * {@code domain.xml} of a slot to bake them into: the system properties
     * as {@code -D} options, then the heap and assertion options.
     */
    public List<String> childJvmOptions() {
        List<String> options = new ArrayList<>();
        for (String property : systemProperties) {
            options.add("-D" + property);
        }
        options.addAll(jvmOptions().values());
        return options;
    }

    /**
     * The commands a GlassFish Embedded child runs as it starts, before its
     * deploy: each start is a slot's first start too, so the post-boot
     * commands, then the post-start ones.
     */
    public List<String> childCommands() {
        List<String> commands = new ArrayList<>(postBootCommands);
        commands.addAll(postStartCommands);
        return commands;
    }

    /**
     * Read from the {@code gf.pool.*} system properties that {@code up}
     * forwards. An empty value counts as unset, and {@code failOnCommandError}
     * defaults to {@code true}.
     */
    public static SlotSetup fromSystemProperties() {
        return new SlotSetup(
                PoolConfig.parseLines(System.getProperty(SYS_SYSTEM_PROPERTIES)),
                System.getProperty(SYS_MAX_HEAP_SIZE),
                System.getProperty(SYS_ENABLE_ASSERTIONS),
                PoolConfig.parseLines(System.getProperty(SYS_POST_BOOT_COMMANDS)),
                PoolConfig.parseLines(System.getProperty(SYS_POST_START_COMMANDS)),
                !"false".equalsIgnoreCase(System.getProperty(SYS_FAIL_ON_COMMAND_ERROR, "").trim()));
    }

    /** This setup as the system properties {@link #fromSystemProperties} reads, without the unset ones. */
    public Map<String, String> toSystemProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        putLines(properties, SYS_SYSTEM_PROPERTIES, systemProperties);
        putIfSet(properties, SYS_MAX_HEAP_SIZE, maxHeapSize);
        putIfSet(properties, SYS_ENABLE_ASSERTIONS, enableAssertions);
        putLines(properties, SYS_POST_BOOT_COMMANDS, postBootCommands);
        putLines(properties, SYS_POST_START_COMMANDS, postStartCommands);
        properties.put(SYS_FAIL_ON_COMMAND_ERROR, String.valueOf(failOnCommandError));

        return properties;
    }

    private static void putLines(Map<String, String> properties, String key, List<String> lines) {
        if (!lines.isEmpty()) {
            properties.put(key, String.join("\n", lines));
        }
    }

    private static void putIfSet(Map<String, String> properties, String key, String value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    private static List<String> copy(List<String> list) {
        return (list == null) ? List.of() : List.copyOf(list);
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
