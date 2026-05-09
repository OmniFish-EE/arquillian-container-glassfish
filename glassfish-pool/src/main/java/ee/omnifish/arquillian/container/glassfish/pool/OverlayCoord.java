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
 * Single overlay artifact: an {@link ArtifactCoord} plus the file name it
 * should land under in the staged install's {@code modules/} directory.
 *
 * <p>JavaBean for Plexus injection (nested {@code <overlay>} blocks).
 */
public class OverlayCoord extends ArtifactCoord {

    private String destFileName;

    public OverlayCoord() {
    }

    public OverlayCoord(String groupId, String artifactId, String version, String type, String classifier,
                        String destFileName) {
        super(groupId, artifactId, version, type, classifier);
        this.destFileName = destFileName;
    }

    public String getDestFileName() {
        return destFileName;
    }

    public void setDestFileName(String destFileName) {
        this.destFileName = destFileName;
    }
}
