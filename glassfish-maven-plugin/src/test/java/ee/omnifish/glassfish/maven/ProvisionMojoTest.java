/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class ProvisionMojoTest {

    private static final String INSTALL = "/build/target/dist/glassfish9";

    private final RecordingLog log = new RecordingLog();

    @Test
    void publishesInstallRoot() {
        Properties properties = new Properties();

        ProvisionMojo.publish(properties, "glassfish.home", INSTALL, log);

        assertEquals(INSTALL, properties.getProperty("glassfish.home"));
        assertTrue(log.warnings.isEmpty());
    }

    @Test
    void replacesPomValueWithWarning() {
        Properties properties = new Properties();
        properties.setProperty("glassfish.home", "glassfish7");

        ProvisionMojo.publish(properties, "glassfish.home", INSTALL, log);

        assertEquals(INSTALL, properties.getProperty("glassfish.home"));
        assertThat(log.warnings.get(0), containsString("glassfish.home=glassfish7"));
    }

    @Test
    void sameValueNeedsNoWarning() {
        Properties properties = new Properties();
        properties.setProperty("glassfish.home", INSTALL);

        ProvisionMojo.publish(properties, "glassfish.home", INSTALL, log);

        assertTrue(log.warnings.isEmpty());
    }
}
