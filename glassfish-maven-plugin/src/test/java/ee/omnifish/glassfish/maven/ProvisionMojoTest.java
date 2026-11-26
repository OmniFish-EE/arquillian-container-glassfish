/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Properties;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.Edition;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
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

    /** Only an overlay that applies makes GlassFish Embedded run from a ZIP. */
    @Test
    void onlyAnOverlayThatAppliesCounts() {
        OverlayCoord applies = new OverlayCoord("org.glassfish", "jakarta.faces", "4.1.18", "jar", null,
                "jakarta.faces.jar");
        OverlayCoord skipped = new OverlayCoord("org.glassfish", "jakarta.faces", "4.1.18", "jar", null,
                "jakarta.faces.jar");
        skipped.setSkip(true);
        OverlayCoord notApplied = new OverlayCoord("org.glassfish", "jakarta.faces", "4.1.18", "jar", null,
                "jakarta.faces.jar");
        notApplied.setApply(false);

        assertTrue(OverlayCoord.anyApplied(List.of(skipped, applies), false));
        assertFalse(OverlayCoord.anyApplied(List.of(applies), true));
        assertFalse(OverlayCoord.anyApplied(List.of(skipped, notApplied), false));
        assertFalse(OverlayCoord.anyApplied(null, false));
    }

    @Test
    void embeddedEditionsStageNoExistingInstall() {
        DistributionCoord jar = Edition.EMBEDDED.distribution("8.0.5", null, false);

        MojoExecutionException withHome = assertThrows(MojoExecutionException.class,
                () -> ProvisionMojo.checkEdition(Edition.EMBEDDED, null, new File("glassfish8"), false, false));
        assertThrows(MojoExecutionException.class,
                () -> ProvisionMojo.checkEdition(Edition.EMBEDDED_WEB, jar, null, true, false));
        assertDoesNotThrow(() -> ProvisionMojo.checkEdition(Edition.EMBEDDED, jar, null, false, false));
        assertDoesNotThrow(() -> ProvisionMojo.checkEdition(Edition.FULL, null, new File("glassfish8"), true, false));

        assertThat(withHome.getMessage(), containsString("<home> and <unpackSkip> work with the full and web editions"));
    }

    @Test
    void anEmbeddedJarCantBeOverlaidNorStartDomain1() {
        DistributionCoord jar = new DistributionCoord();
        jar.set("org.glassfish.main.extras:glassfish-embedded-all:8.0.5:jar");

        MojoExecutionException overlaid = assertThrows(MojoExecutionException.class,
                () -> ProvisionMojo.checkEdition(Edition.EMBEDDED, jar, null, false, true));
        MojoExecutionException full = assertThrows(MojoExecutionException.class,
                () -> ProvisionMojo.checkEdition(Edition.FULL, jar, null, false, false));

        assertThat(overlaid.getMessage(), containsString("Overlays need the modules of a ZIP"));
        assertThat(full.getMessage(), containsString("for GlassFish Embedded, set <edition>embedded</edition>"));
    }
}
