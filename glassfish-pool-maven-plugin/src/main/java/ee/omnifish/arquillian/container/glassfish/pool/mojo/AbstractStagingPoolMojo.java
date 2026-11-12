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
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import ee.omnifish.arquillian.container.glassfish.pool.ArtifactCoord;
import ee.omnifish.arquillian.container.glassfish.pool.DistributionCoord;
import ee.omnifish.arquillian.container.glassfish.pool.OverlayCoord;
import ee.omnifish.arquillian.container.glassfish.pool.PoolStaging;
import ee.omnifish.arquillian.container.glassfish.pool.StageSpec;

/**
 * Adds staging configuration on top of {@link AbstractPoolMojo}: which
 * GlassFish distribution to fetch, which overlay jars to drop into the
 * staged install, where to unpack. Shared by {@link UpMojo} and
 * {@link ProvisionMojo}.
 */
abstract class AbstractStagingPoolMojo extends AbstractPoolMojo {

    /**
     * GlassFish distribution to resolve and unpack. When unset, no unpack
     * happens and {@code gf.pool.source} must already point at a staged install.
     * {@code groupId}, {@code artifactId} and {@code type} default to the
     * Eclipse GlassFish zip ({@code org.glassfish.main.distributions:glassfish:zip}),
     * so only {@code <version>} is required.
     */
    @Parameter
    protected DistributionCoord distribution;

    /** Where the dist zip is unpacked. Default {@code ${project.build.directory}/dist}. */
    @Parameter(property = "gf.pool.stageDir", defaultValue = "${project.build.directory}/dist")
    protected File stageDir;

    /** Skip the dist unpack. Set to {@code true} when reusing an existing install. */
    @Parameter(property = "gf.pool.unpack.skip", defaultValue = "false")
    protected boolean unpackSkip;

    /**
     * Optional artifacts copied into the staged install's modules directory
     * after unpack. Each {@code <overlay>} is a GAV plus a {@code destFileName}.
     */
    @Parameter
    protected List<OverlayCoord> overlays;

    /**
     * Where overlay jars land. Defaults to {@code ${gf.pool.source}/glassfish/modules},
     * which matches the standard GlassFish layout.
     */
    @Parameter(property = "gf.pool.overlayTargetDir")
    protected File overlayTargetDir;

    /** Skip overlays. Set via {@code -Dmojarra.noupdate=true} in TCK builds. */
    @Parameter(property = "gf.pool.overlay.skip", defaultValue = "false")
    protected boolean overlaySkip;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * Build the {@code Runnable} that {@link
     * ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap#up} runs
     * inside its provisioning lock. Returns {@code null} if there's no work,
     * which keeps the provisioning hot-path branch-free.
     *
     * <p>Also derives {@link #poolSource} from the distribution when it isn't
     * set, so callers must invoke this <em>before</em> {@link #poolConfig()}.
     */
    protected Runnable buildPreStage() throws MojoExecutionException {
        boolean willUnpack = !unpackSkip && distribution != null;
        if (willUnpack) {
            requireDistributionVersion();
            if (poolSource == null) {
                derivePoolSource();
            }
        }
        StageSpec spec = stageSpec();
        if (!spec.hasWork()) {
            return null;
        }
        return () -> {
            try {
                PoolStaging.stage(spec, this::resolveArtifact);
            } catch (IOException e) {
                throw new RuntimeException("Pool staging failed: " + e.getMessage(), e);
            }
        };
    }

    private void requireDistributionVersion() throws MojoExecutionException {
        String version = distribution.getVersion();
        if (version == null || version.isBlank()) {
            throw new MojoExecutionException("<distribution> requires a <version> (resolving "
                    + distribution.getGroupId() + ":" + distribution.getArtifactId() + ")");
        }
    }

    /**
     * Point {@link #poolSource} at {@code stageDir/<root>}, where {@code <root>}
     * is the distribution zip's single top-level directory ({@code glassfish9},
     * {@code payara6}, …). Read from the zip itself rather than by listing
     * {@code stageDir} after unpack: the unpack runs later, inside
     * {@code PoolBootstrap}'s lock, after the {@code PoolConfig} carrying the
     * source has already been built. Leaves {@code poolSource} unset (with a
     * warning) when the zip has no single root.
     */
    private void derivePoolSource() throws MojoExecutionException {
        String root;
        try {
            root = PoolStaging.singleRootDirectory(resolveArtifact(distribution));
        } catch (IOException e) {
            throw new MojoExecutionException("Could not inspect " + distribution.toGav() + ": " + e.getMessage(), e);
        }
        if (root == null) {
            getLog().warn(distribution.toGav() + " has no single top-level directory; set <poolSource> "
                    + "(gf.pool.source) to the install inside " + stageDir);
            return;
        }
        poolSource = new File(stageDir, root);
        getLog().info("poolSource not set; using " + poolSource + " from " + distribution.toGav());
    }

    private StageSpec stageSpec() throws MojoExecutionException {
        List<OverlayCoord> ov = (overlays == null) ? Collections.emptyList() : overlays;
        return new StageSpec(stageDir.toPath(), distribution, unpackSkip, overlayDir(ov), ov, overlaySkip);
    }

    /** {@code overlayTargetDir}, else {@code poolSource/glassfish/modules}; {@code null} when no overlay needs it. */
    private Path overlayDir(List<OverlayCoord> ov) throws MojoExecutionException {
        if (overlayTargetDir != null) {
            return overlayTargetDir.toPath();
        }
        if (poolSource != null) {
            return poolSource.toPath().resolve("glassfish/modules");
        }
        if (!overlaySkip && !ov.isEmpty()) {
            throw new MojoExecutionException(
                    "<overlays> need a target: set <overlayTargetDir> or <poolSource> (gf.pool.source)");
        }
        return null;
    }

    private Path resolveArtifact(ArtifactCoord coord) throws IOException {
        Artifact aether = new DefaultArtifact(
                coord.getGroupId(),
                coord.getArtifactId(),
                coord.getClassifier(),
                coord.getType(),
                coord.getVersion());
        ArtifactRequest req = new ArtifactRequest(aether, remoteRepos, null);
        try {
            return repoSystem.resolveArtifact(repoSession, req).getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            throw new IOException("Failed to resolve " + coord.toGav() + ": " + e.getMessage(), e);
        }
    }
}
