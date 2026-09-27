/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.
 */
package ee.omnifish.arquillian.container.glassfish.managed;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import ee.omnifish.arquillian.container.glassfish.process.ProcessOutputConsumer;
import ee.omnifish.arquillian.container.glassfish.process.SilentOutputConsumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression test for issue #36.
 *
 * <p>{@code executeAdminDomainCommand} previously mutated its caller-supplied {@code args}
 * list, throwing {@link UnsupportedOperationException} when callers passed immutable lists
 * from {@code Arrays.asList(...)} or {@code List.of(...)} — which is exactly what the
 * post-boot-commands loop and the {@code stop()}/{@code stopContainer()} teardown paths do.
 *
 * <p>The fix is a defensive copy. These tests verify that immutable {@code args} no longer
 * cause an {@code UnsupportedOperationException} from inside the method.
 */
class GlassFishServerControlPostBootCommandsTest {

    @Test
    void immutableArgsFromArraysAsList_doNotThrowUOE() throws Exception {
        runWithImmutableArgs(Arrays.asList("foo"));
    }

    @Test
    void immutableArgsFromListOf_doNotThrowUOE() throws Exception {
        runWithImmutableArgs(List.of());
    }

    @Test
    void immutableArgsFromListOfWithMultipleEntries_doNotThrowUOE() throws Exception {
        runWithImmutableArgs(List.of("foo=bar", "baz=qux"));
    }

    private void runWithImmutableArgs(List<String> immutableArgs) throws Exception {
        GlassFishManagedContainerConfiguration config = new GlassFishManagedContainerConfiguration();
        config.setDomain("domain1");

        GlassFishServerControl control = new GlassFishServerControl(config);

        Method method = GlassFishServerControl.class.getDeclaredMethod(
            "executeAdminDomainCommand",
            String.class, String.class, List.class, ProcessOutputConsumer.class);
        method.setAccessible(true);

        InvocationTargetException invocationException = assertThrows(InvocationTargetException.class,
            () -> method.invoke(control, "test", "create-system-properties",
                immutableArgs, new SilentOutputConsumer()));

        Throwable cause = invocationException.getCause();
        Class<?> causeType = cause == null ? null : cause.getClass();
        assertNotEquals(UnsupportedOperationException.class, causeType,
            "executeAdminDomainCommand must not mutate caller-supplied immutable args list");
    }
}
