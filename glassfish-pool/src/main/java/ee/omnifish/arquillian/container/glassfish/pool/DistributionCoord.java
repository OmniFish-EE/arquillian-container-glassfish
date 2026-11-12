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
package ee.omnifish.arquillian.container.glassfish.pool;

/**
 * The {@code <distribution>} coordinate: an {@link ArtifactCoord} whose
 * {@code groupId}, {@code artifactId} and {@code type} default to the
 * canonical Eclipse GlassFish distribution zip, so a vanilla setup only has
 * to declare {@code <version>}.
 *
 * <p>The defaults live on the bean rather than being applied after injection
 * because {@link ArtifactCoord} already defaults {@code type} to {@code jar}
 * (right for overlays), which would be indistinguishable from an explicit
 * {@code <type>jar</type>} once Plexus is done. Setters treat a blank value as
 * "not set", so {@code <groupId></groupId>} or an unresolved-to-empty property
 * falls back to the default instead of producing an unresolvable GAV.
 *
 * <p>JavaBean for Plexus injection (nested {@code <distribution>} block).
 */
public class DistributionCoord extends ArtifactCoord {

    public static final String DEFAULT_GROUP_ID = "org.glassfish.main.distributions";
    public static final String DEFAULT_ARTIFACT_ID = "glassfish";
    public static final String DEFAULT_TYPE = "zip";

    public DistributionCoord() {
        setGroupId(null);
        setArtifactId(null);
        setType(null);
    }

    public DistributionCoord(String groupId, String artifactId, String version, String type, String classifier) {
        this();
        setGroupId(groupId);
        setArtifactId(artifactId);
        setVersion(version);
        setType(type);
        setClassifier(classifier);
    }

    @Override
    public void setGroupId(String groupId) {
        super.setGroupId(orDefault(groupId, DEFAULT_GROUP_ID));
    }

    @Override
    public void setArtifactId(String artifactId) {
        super.setArtifactId(orDefault(artifactId, DEFAULT_ARTIFACT_ID));
    }

    @Override
    public void setType(String type) {
        super.setType(orDefault(type, DEFAULT_TYPE));
    }

    private static String orDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
