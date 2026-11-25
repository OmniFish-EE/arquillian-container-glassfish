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
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EditionTest {

    @Test
    void eachEditionResolvesItsArtifact() {
        assertThat(Edition.FULL.distribution("8.0.5", null, false).toGav(),
                equalTo("org.glassfish.main.distributions:glassfish:8.0.5:zip"));
        assertThat(Edition.WEB.distribution("8.0.5", null, false).toGav(),
                equalTo("org.glassfish.main.distributions:web:8.0.5:zip"));
        assertThat(Edition.EMBEDDED.distribution("8.0.5", null, false).toGav(),
                equalTo("org.glassfish.main.extras:glassfish-embedded-all:8.0.5"));
        assertThat(Edition.EMBEDDED_WEB.distribution("8.0.5", null, false).toGav(),
                equalTo("org.glassfish.main.extras:glassfish-embedded-web:8.0.5"));
    }

    /** Overlays replace modules, which only a ZIP has; the Embedded JARs nest none. */
    @Test
    void anOverlaidEmbeddedEditionResolvesItsZip() {
        assertThat(Edition.EMBEDDED.distribution("8.0.5", null, true).toGav(),
                equalTo("org.glassfish.main.distributions:glassfish:8.0.5:zip"));
        assertThat(Edition.EMBEDDED_WEB.distribution("8.0.5", null, true).toGav(),
                equalTo("org.glassfish.main.distributions:web:8.0.5:zip"));
        assertThat(Edition.WEB.distribution("8.0.5", null, true).toGav(),
                equalTo("org.glassfish.main.distributions:web:8.0.5:zip"));
    }

    @Test
    void aVersionOnlyDistributionOverridesTheVersion() {
        DistributionCoord distribution = new DistributionCoord();
        distribution.set("9.0.0-M4");

        assertThat(Edition.EMBEDDED.distribution("8.0.5", distribution, false).toGav(),
                equalTo("org.glassfish.main.extras:glassfish-embedded-all:9.0.0-M4"));
    }

    /** Explicit coordinates win, also when they're the Full ZIP's own, which a version alone defaults to. */
    @Test
    void aDistributionWithCoordinatesIsUsedAsItIs() {
        DistributionCoord full = new DistributionCoord();
        full.set("org.glassfish.main.distributions:glassfish:8.0.5:zip");
        DistributionCoord nested = new DistributionCoord();
        nested.setArtifactId("glassfish");
        nested.setVersion("8.0.5");

        assertThat(Edition.WEB.distribution("9.0.0", full, false), sameInstance(full));
        assertThat(Edition.WEB.distribution("9.0.0", nested, false), sameInstance(nested));
    }

    @Test
    void withoutAVersionThereIsNoArtifact() {
        assertThat(Edition.FULL.distribution(null, null, false), nullValue());
        assertThat(Edition.EMBEDDED.distribution(" ", new DistributionCoord(), false), nullValue());
    }

    @Test
    void editionsAreNamedAsTheParameterSpellsThem() {
        assertThat(Edition.of("embedded-web"), is(Edition.EMBEDDED_WEB));
        assertThat(Edition.of(" Full "), is(Edition.FULL));
        assertThat(Edition.EMBEDDED.isEmbedded(), is(true));
        assertThat(Edition.WEB.isEmbedded(), is(false));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Edition.of("micro"));
        assertThat(e.getMessage(), containsString("full, web, embedded or embedded-web, not 'micro'"));
    }
}
