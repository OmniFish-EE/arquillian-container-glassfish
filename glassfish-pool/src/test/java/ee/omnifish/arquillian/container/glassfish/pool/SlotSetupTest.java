/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_ENABLE_ASSERTIONS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_FAIL_ON_COMMAND_ERROR;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_MAX_HEAP_SIZE;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_POST_BOOT_COMMANDS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_POST_START_COMMANDS;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolConfig.SYS_SYSTEM_PROPERTIES;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** How the slot setup reaches the test JVMs, which create slots of their own. */
class SlotSetupTest {

    private static final List<String> KEYS = List.of(SYS_SYSTEM_PROPERTIES, SYS_MAX_HEAP_SIZE,
            SYS_ENABLE_ASSERTIONS, SYS_POST_BOOT_COMMANDS, SYS_POST_START_COMMANDS, SYS_FAIL_ON_COMMAND_ERROR);

    @AfterEach
    void clearProperties() {
        KEYS.forEach(System::clearProperty);
        System.clearProperty("glassfish.debug");
    }

    @Test
    void roundTripsThroughSystemProperties() {
        SlotSetup setup = new SlotSetup(List.of("a=b", "c=d"), "2g", ":org.example...",
                List.of("create-file-user --groups 'g 1' u", "set x=y"), List.of("set-log-levels x=FINE"), false);

        setup.toSystemProperties().forEach(System::setProperty);
        SlotSetup read = SlotSetup.fromSystemProperties();

        assertThat(read.systemProperties(), contains("a=b", "c=d"));
        assertThat(read.maxHeapSize(), equalTo("2g"));
        assertThat(read.enableAssertions(), equalTo(":org.example..."));
        assertThat(read.postBootCommands(), contains("create-file-user --groups 'g 1' u", "set x=y"));
        assertThat(read.postStartCommands(), contains("set-log-levels x=FINE"));
        assertThat(read.failOnCommandError(), equalTo(false));
    }

    /** A forwarded reference to a property up didn't publish arrives empty. */
    @Test
    void emptyValuesCountAsUnset() {
        KEYS.forEach(key -> System.setProperty(key, ""));

        SlotSetup read = SlotSetup.fromSystemProperties();

        assertThat(read.systemProperties(), empty());
        assertThat(read.maxHeapSize(), nullValue());
        assertThat(read.enableAssertions(), nullValue());
        assertThat(read.postBootCommands(), empty());
        assertThat(read.postStartCommands(), empty());
        assertThat(read.failOnCommandError(), equalTo(true));
        assertThat(read.jvmOptions().isEmpty(), equalTo(true));
    }

    @Test
    void leavesUnsetValuesOut() {
        assertThat(SlotSetup.NONE.toSystemProperties(), equalTo(Map.of(SYS_FAIL_ON_COMMAND_ERROR, "true")));
    }

    @Test
    void rejectsAnUnclosedQuote() {
        assertThrows(IllegalArgumentException.class, () -> new SlotSetup(List.of(), null, null,
                List.of(), List.of("create-file-user --groups 'g u"), true));
    }

    /** debug and suspend aren't forwarded, so slots a test JVM creates start without them. */
    @Test
    void testJvmConfigReadsTheForwardedSetupButNotDebug() {
        System.setProperty(SYS_POST_BOOT_COMMANDS, "create-file-user u");
        System.setProperty("glassfish.debug", "true");

        PoolConfig config = PoolConfig.forTestJvm(Paths.get("target", "pool"));

        assertThat(config.setup().postBootCommands(), contains("create-file-user u"));
        assertThat(config.debug(), equalTo(false));
    }
}
