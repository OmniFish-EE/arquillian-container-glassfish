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
 * Maven artifact coordinate carried through the staging pipeline.
 *
 * <p>Mutable JavaBean shape so Plexus can inject mojo configuration (nested
 * {@code <distribution>} block) by setter. {@link PoolStaging} treats it as
 * read-only after construction.
 */
public class ArtifactCoord {

    private String groupId;
    private String artifactId;
    private String version;
    private String type = "jar";
    private String classifier;

    public ArtifactCoord() {
    }

    public ArtifactCoord(String groupId, String artifactId, String version, String type, String classifier) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.type = (type == null || type.isEmpty()) ? "jar" : type;
        this.classifier = classifier;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getClassifier() {
        return classifier;
    }

    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    /** {@code groupId:artifactId:version[:type][:classifier]} for marker comparison and logs. */
    public String toGav() {
        StringBuilder sb = new StringBuilder()
                .append(groupId).append(':').append(artifactId).append(':').append(version);
        if (type != null && !type.isEmpty() && !"jar".equals(type)) {
            sb.append(':').append(type);
        }
        if (classifier != null && !classifier.isEmpty()) {
            sb.append(':').append(classifier);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toGav();
    }
}
