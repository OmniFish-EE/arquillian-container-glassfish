/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DistributionCoordTest {

    @Test
    void versionOnlyResolvesToEclipseGlassFishZip() {
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion("9.0.0");

        assertThat(dist.toGav(), equalTo("org.glassfish.main.distributions:glassfish:9.0.0:zip"));
    }

    @Test
    void explicitCoordinatesWin() {
        DistributionCoord dist = new DistributionCoord();
        dist.setGroupId("fish.payara.distributions");
        dist.setArtifactId("payara");
        dist.setVersion("6.2025.1");
        dist.setType("jar");

        assertThat(dist.toGav(), equalTo("fish.payara.distributions:payara:6.2025.1"));
    }

    @Test
    void blankValuesFallBackToDefaults() {
        DistributionCoord dist = new DistributionCoord(" ", "", "9.0.0", "", null);

        assertThat(dist.toGav(), equalTo("org.glassfish.main.distributions:glassfish:9.0.0:zip"));
    }

    @Test
    void overlaysKeepJarDefault() {
        assertThat(new OverlayCoord().getType(), equalTo("jar"));
    }

    @Test
    void textFormTakesBareVersion() {
        DistributionCoord dist = new DistributionCoord();
        dist.set(" 9.0.0 ");

        assertThat(dist.toGav(), equalTo("org.glassfish.main.distributions:glassfish:9.0.0:zip"));
    }

    @Test
    void textFormTakesFullCoordinates() {
        DistributionCoord payara = new DistributionCoord();
        payara.set("fish.payara.distributions:payara:6.2025.1");
        DistributionCoord classified = new DistributionCoord();
        classified.set("org.example:server:1.0:tar.gz:linux");

        assertThat(payara.toGav(), equalTo("fish.payara.distributions:payara:6.2025.1:zip"));
        assertThat(classified.toGav(), equalTo("org.example:server:1.0:tar.gz:linux"));
    }

    @Test
    void textFormRejectsPartialCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new DistributionCoord().set("org.example:server"));
    }

    @Test
    void onlyDefaultsCountAsEmpty() {
        DistributionCoord versioned = new DistributionCoord();
        versioned.setVersion("9.0.0");
        DistributionCoord payaraWithoutVersion = new DistributionCoord();
        payaraWithoutVersion.setGroupId("fish.payara.distributions");

        assertThat(new DistributionCoord().isEmpty(), equalTo(true));
        assertThat(versioned.isEmpty(), equalTo(false));
        assertThat(payaraWithoutVersion.isEmpty(), equalTo(false));
    }
}
