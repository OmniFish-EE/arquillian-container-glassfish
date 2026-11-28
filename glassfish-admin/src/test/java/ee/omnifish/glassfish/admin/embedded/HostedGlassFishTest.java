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
package ee.omnifish.glassfish.admin.embedded;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HostedGlassFish} without GlassFish: this module's tests have its API,
 * but no runtime. The {@code glassfish-embedded} Arquillian Container's tests
 * boot a real one through it.
 */
class HostedGlassFishTest {

    @TempDir
    Path tmp;

    @Test
    void aGlassFishThatDoesntStartLeavesTheJvmAsItWas() {
        String trustStore = System.getProperty("javax.net.ssl.trustStore");
        HostedGlassFish.Settings settings = new HostedGlassFish.Settings()
                .systemProperty("hosted.glassfish.test", "set")
                .trustStore(tmp.resolve("cacerts.p12"), "changeit");

        IOException e = assertThrows(IOException.class,
                () -> HostedGlassFish.start(getClass().getClassLoader(), settings));

        assertThat(e.getMessage(), startsWith("GlassFish Embedded didn't start"));
        assertThat(System.getProperty("hosted.glassfish.test"), nullValue());
        assertThat(System.getProperty("javax.net.ssl.trustStore"), equalTo(trustStore));
    }

    /** One that didn't start doesn't count as the one the JVM hosts. */
    @Test
    void aGlassFishThatDidntStartCanBeTriedAgain() {
        HostedGlassFish.Settings settings = new HostedGlassFish.Settings();
        assertThrows(IOException.class, () -> HostedGlassFish.start(getClass().getClassLoader(), settings));

        IOException e = assertThrows(IOException.class,
                () -> HostedGlassFish.start(getClass().getClassLoader(), settings));

        assertThat(e.getMessage(), startsWith("GlassFish Embedded didn't start"));
    }
}
