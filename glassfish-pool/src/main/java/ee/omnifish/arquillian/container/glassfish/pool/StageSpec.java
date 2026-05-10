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

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregates the optional staging step for {@link PoolBootstrap#up}: where to
 * unpack the GlassFish dist, which dist artifact to fetch, and which overlay
 * jars to drop into the staged install's {@code modules/}. Unpack and overlay
 * each have their own skip flag so callers can opt out of one without the other
 * (e.g. reuse an existing install but still overlay a custom Mojarra into it).
 */
public final class StageSpec {

    private final Path stageDir;
    private final ArtifactCoord distribution;
    private final boolean unpackSkip;
    private final Path overlayTargetDir;
    private final List<OverlayCoord> overlays;
    private final boolean overlaySkip;

    public StageSpec(Path stageDir,
                     ArtifactCoord distribution,
                     boolean unpackSkip,
                     Path overlayTargetDir,
                     List<OverlayCoord> overlays,
                     boolean overlaySkip) {
        this.stageDir = stageDir;
        this.distribution = distribution;
        this.unpackSkip = unpackSkip;
        this.overlayTargetDir = overlayTargetDir;
        this.overlays = (overlays == null) ? List.of() : Collections.unmodifiableList(overlays);
        this.overlaySkip = overlaySkip;
    }

    public Path stageDir() {
        return stageDir;
    }

    public ArtifactCoord distribution() {
        return distribution;
    }

    public boolean unpackSkip() {
        return unpackSkip;
    }

    public Path overlayTargetDir() {
        return overlayTargetDir;
    }

    public List<OverlayCoord> overlays() {
        return overlays;
    }

    public boolean overlaySkip() {
        return overlaySkip;
    }

    /** True iff at least one of unpack or overlay would do real work. */
    public boolean hasWork() {
        boolean willUnpack = !unpackSkip && distribution != null;
        boolean willOverlay = !overlaySkip && !overlays.isEmpty();
        return willUnpack || willOverlay;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StageSpec)) {
            return false;
        }
        StageSpec other = (StageSpec) o;
        return unpackSkip == other.unpackSkip
                && overlaySkip == other.overlaySkip
                && Objects.equals(stageDir, other.stageDir)
                && Objects.equals(distribution, other.distribution)
                && Objects.equals(overlayTargetDir, other.overlayTargetDir)
                && Objects.equals(overlays, other.overlays);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stageDir, distribution, unpackSkip, overlayTargetDir, overlays, overlaySkip);
    }
}
