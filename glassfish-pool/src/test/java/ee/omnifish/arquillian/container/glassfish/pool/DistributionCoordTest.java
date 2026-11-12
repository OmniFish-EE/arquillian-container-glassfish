/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

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
}
