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

import java.util.Locale;

import static ee.omnifish.glassfish.maven.staging.DistributionCoord.DEFAULT_GROUP_ID;
import static ee.omnifish.glassfish.maven.staging.DistributionCoord.DEFAULT_TYPE;

/**
 * The GlassFish {@code <edition>} to resolve, and how it starts: {@code full}
 * and {@code web} unpack their ZIP and start {@code domain1}, while
 * {@code embedded} and {@code embedded-web} run GlassFish Embedded with the
 * application, from its self-contained JAR, or, when a module is overlaid,
 * from the static shell of the matching ZIP.
 */
public enum Edition {

    FULL("full", "glassfish", null),
    WEB("web", "web", null),
    EMBEDDED("embedded", "glassfish", "glassfish-embedded-all"),
    EMBEDDED_WEB("embedded-web", "web", "glassfish-embedded-web");

    /** Where the Embedded JARs are published. */
    public static final String EMBEDDED_GROUP_ID = "org.glassfish.main.extras";

    private final String value;
    private final String zip;
    private final String jar;

    Edition(String value, String zip, String jar) {
        this.value = value;
        this.zip = zip;
        this.jar = jar;
    }

    /** The edition {@code value} names, as {@code <edition>} spells it. */
    public static Edition of(String value) {
        for (Edition edition : values()) {
            if (edition.value.equals(value.trim().toLowerCase(Locale.ROOT))) {
                return edition;
            }
        }

        throw new IllegalArgumentException("<edition> is full, web, embedded or embedded-web, not '" + value + "'");
    }

    /** Whether this edition runs GlassFish Embedded. */
    public boolean isEmbedded() {
        return jar != null;
    }

    /**
     * The artifact to stage. A {@code distribution} with coordinates of its
     * own is used as it is. Otherwise, the version, of {@code distribution}
     * or else {@code version}, goes to this edition's artifact: its ZIP, or
     * for Embedded, its JAR, unless {@code overlaid}, since overlays need the
     * modules of a ZIP.
     *
     * @param distribution {@code <distribution>}, or {@code null}
     * @return the artifact, or {@code null} without a version
     */
    public DistributionCoord distribution(String version, DistributionCoord distribution, boolean overlaid) {
        if (distribution != null && distribution.hasCoordinates()) {
            return distribution;
        }

        String given = (distribution != null && !isBlank(distribution.getVersion())) ? distribution.getVersion()
                : version;
        if (isBlank(given)) {
            return null;
        }
        if (isEmbedded() && !overlaid) {
            return new DistributionCoord(EMBEDDED_GROUP_ID, jar, given.trim(), "jar", null);
        }

        return new DistributionCoord(DEFAULT_GROUP_ID, zip, given.trim(), DEFAULT_TYPE, null);
    }

    /** Whether {@code artifact} is a JAR: GlassFish Embedded's own, rather than a ZIP to unpack. */
    public static boolean isJar(ArtifactCoord artifact) {
        return artifact != null && "jar".equals(artifact.getType());
    }

    @Override
    public String toString() {
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
