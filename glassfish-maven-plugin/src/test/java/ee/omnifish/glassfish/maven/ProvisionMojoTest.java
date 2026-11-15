/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.StagingCommands;

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

    /** On an install staging doesn't own, the commands would run on every build. */
    @Test
    void postBootCommandsNeedAnUnpack() {
        StagingCommands commands = new StagingCommands("create-file-user --groups g u", true);
        DistributionCoord distribution = new DistributionCoord();
        distribution.setVersion("9.0.0");

        MojoExecutionException withUnpackSkip = assertThrows(MojoExecutionException.class,
                () -> ProvisionMojo.requireUnpack(commands, true, distribution));
        assertThrows(MojoExecutionException.class, () -> ProvisionMojo.requireUnpack(commands, false, null));
        assertDoesNotThrow(() -> ProvisionMojo.requireUnpack(commands, false, distribution));
        assertDoesNotThrow(() -> ProvisionMojo.requireUnpack(StagingCommands.NONE, true, null));

        assertThat(withUnpackSkip.getMessage(), containsString("<postBootCommands> need an install this goal unpacks"));
    }
}
