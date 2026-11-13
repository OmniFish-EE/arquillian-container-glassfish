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
 * <p>JavaBean for Plexus injection (nested {@code <distribution>} block), plus
 * {@link #set(String)} for the one-line text form.
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

    /**
     * The text form, {@code <distribution>9.0.0</distribution>} or
     * {@code -Dglassfish.distribution=9.0.0}: Maven calls this method when the
     * element holds text instead of child elements. Takes a bare version, or
     * {@code groupId:artifactId:version[:type[:classifier]]} as {@link #toGav()}
     * prints it.
     */
    public void set(String coordinates) {
        String[] parts = coordinates.trim().split(":", -1);
        if (parts.length == 1) {
            setVersion(parts[0]);
            return;
        }
        if (parts.length < 3 || parts.length > 5) {
            throw new IllegalArgumentException("Expected a version or groupId:artifactId:version[:type[:classifier]], got '"
                    + coordinates + "'");
        }
        setGroupId(parts[0]);
        setArtifactId(parts[1]);
        setVersion(parts[2]);
        setType(parts.length > 3 ? parts[3] : null);
        setClassifier(parts.length > 4 ? parts[4] : null);
    }

    /**
     * Whether nothing beyond the defaults is set. Maven injects such a bean
     * for a {@code <distribution>} parameter that has a {@code -D} property but
     * was not configured, so callers treat it as "no distribution".
     */
    public boolean isEmpty() {
        return isBlank(getVersion())
                && isBlank(getClassifier())
                && DEFAULT_GROUP_ID.equals(getGroupId())
                && DEFAULT_ARTIFACT_ID.equals(getArtifactId())
                && DEFAULT_TYPE.equals(getType());
    }

    private static String orDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
