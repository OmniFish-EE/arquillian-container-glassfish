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

import java.util.List;

/**
 * Single overlay artifact: an {@link ArtifactCoord} plus the file name it
 * should land under in the staged install's {@code modules/} directory.
 *
 * <p>JavaBean for Plexus injection (nested {@code <overlay>} blocks).
 * {@code skip} and {@code apply} stay {@code null} when the pom doesn't set
 * them, so staging can tell that a pom sets both.
 */
public class OverlayCoord extends ArtifactCoord {

    private String destFileName;
    private Boolean skip;
    private Boolean apply;

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

    /** {@code <skip>}, or {@code null} when the pom doesn't set it. */
    public Boolean getSkip() {
        return skip;
    }

    public void setSkip(Boolean skip) {
        this.skip = skip;
    }

    /** {@code <apply>}, the reverse of {@code <skip>}, or {@code null} when the pom doesn't set it. */
    public Boolean getApply() {
        return apply;
    }

    public void setApply(Boolean apply) {
        this.apply = apply;
    }

    /** Whether staging leaves this overlay out: with {@code <skip>true</skip>}, or {@code <apply>false</apply>}. */
    public boolean isSkipped() {
        return Boolean.TRUE.equals(skip) || Boolean.FALSE.equals(apply);
    }

    /** Whether one of {@code overlays} applies: one that isn't skipped, without {@code overlaySkip}. */
    public static boolean anyApplied(List<OverlayCoord> overlays, boolean overlaySkip) {
        return !overlaySkip && overlays != null && overlays.stream().anyMatch(overlay -> !overlay.isSkipped());
    }
}
