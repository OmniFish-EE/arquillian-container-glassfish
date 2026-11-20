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
package ee.omnifish.glassfish.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.maven.staging.ArtifactCoord;
import ee.omnifish.glassfish.maven.staging.CertImport;
import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.GlassFishStaging;
import ee.omnifish.glassfish.maven.staging.GlassFishStaging.ArtifactResolver;
import ee.omnifish.glassfish.maven.staging.GlassFishStaging.CommandRunner;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
import ee.omnifish.glassfish.maven.staging.StageSpec;
import ee.omnifish.glassfish.maven.staging.StagingCommands;
import ee.omnifish.glassfish.maven.staging.TrustedCert;

/**
 * The Maven side of staging, shared by {@code glassfish:provision} and the
 * pool plugin's {@code up}/{@code provision}: resolves artifacts through the
 * build's repositories, works out where the install lands, and runs
 * {@link GlassFishStaging}.
 *
 * <p>The goals declare their staging {@code @Parameter}s themselves, because
 * their {@code -D} names differ ({@code glassfish.*} here, {@code gf.pool.*}
 * in the pool plugin), and hand the values in.
 */
public final class Stager {

    private final ArtifactResolver resolver;
    private final Log log;

    public Stager(RepositorySystem repoSystem, RepositorySystemSession repoSession,
                  List<RemoteRepository> remoteRepos, Log log) {
        this(coord -> resolve(repoSystem, repoSession, remoteRepos, coord), log);
    }

    /** Package-private for unit tests, which resolve to local files. */
    Stager(ArtifactResolver resolver, Log log) {
        this.resolver = resolver;
        this.log = log;
    }

    /**
     * Collect a staging run from a goal's parameters.
     *
     * <p>The install root is {@code home} when set, else the distribution
     * zip's single top-level directory under {@code stageDir}
     * ({@code glassfish9}, {@code payara6}, …). That name is read from the zip
     * itself rather than by listing {@code stageDir}, so it is known before
     * the unpack runs: the pool unpacks later, inside its lock, after building
     * a config that already needs the path. Overlays land in
     * {@code overlayTargetDir}, else in the install's {@code glassfish/modules}.
     * Certificates go into the truststore {@code certImport} names, else into
     * the install's domain truststore, which staging finds once it exists.
     *
     * @param homeParameter how the goal spells its explicit install root, for messages
     */
    public StageSpec spec(DistributionCoord distribution, File stageDir, boolean unpackSkip, File home,
                          List<OverlayCoord> overlays, File overlayTargetDir, boolean overlaySkip,
                          CertImport certImport, String homeParameter) throws MojoExecutionException {
        return spec(distribution, stageDir, unpackSkip, home, overlays, overlayTargetDir, overlaySkip, certImport,
                StagingCommands.NONE, homeParameter);
    }

    /**
     * Collect a staging run that also runs {@code commands} on the staged
     * install. The goal checks that it unpacks the install, since the
     * commands then run once per staging, not on every build.
     *
     * @param homeParameter how the goal spells its explicit install root, for messages
     */
    public StageSpec spec(DistributionCoord distribution, File stageDir, boolean unpackSkip, File home,
                          List<OverlayCoord> overlays, File overlayTargetDir, boolean overlaySkip,
                          CertImport certImport, StagingCommands commands, String homeParameter)
            throws MojoExecutionException {
        boolean willUnpack = !unpackSkip && distribution != null;
        if (willUnpack) {
            requireVersion(distribution);
        }
        Path installRoot = null;
        if (home != null) {
            installRoot = home.toPath();
        } else if (willUnpack) {
            installRoot = deriveHome(distribution, stageDir.toPath(), homeParameter);
        }
        List<OverlayCoord> ov = (overlays == null) ? List.of() : overlays;
        checkOverlays(ov);
        Path overlayDir = overlayDir(overlayTargetDir, installRoot, ov, overlaySkip, homeParameter);
        checkCertImport(certImport, installRoot, homeParameter);
        checkCommands(commands, installRoot, homeParameter);
        return new StageSpec(stageDir.toPath(), distribution, unpackSkip, installRoot, overlayDir, ov, overlaySkip,
                certImport, commands);
    }

    /** Run {@code spec}; see {@link GlassFishStaging#stage}. */
    public void stage(StageSpec spec) throws IOException {
        GlassFishStaging.stage(spec, resolver);
    }

    /** Run {@code spec}, with {@code commandRunner} running its commands; see {@link GlassFishStaging#stage}. */
    public void stage(StageSpec spec, CommandRunner commandRunner) throws IOException {
        GlassFishStaging.stage(spec, resolver, commandRunner);
    }

    private static void requireVersion(DistributionCoord distribution) throws MojoExecutionException {
        String version = distribution.getVersion();
        if (version == null || version.isBlank()) {
            throw new MojoExecutionException("<distribution> requires a <version> (resolving "
                    + distribution.getGroupId() + ":" + distribution.getArtifactId() + ")");
        }
    }

    /** {@code stageDir/<zip root>}, or {@code null} (with a warning) when the zip has no single root. */
    private Path deriveHome(DistributionCoord distribution, Path stageDir, String homeParameter)
            throws MojoExecutionException {
        String root;
        try {
            root = GlassFishStaging.singleRootDirectory(resolver.resolve(distribution));
        } catch (IOException e) {
            throw new MojoExecutionException("Could not inspect " + distribution.toGav() + ": " + e.getMessage(), e);
        }
        if (root == null) {
            log.warn(distribution.toGav() + " has no single top-level directory; set " + homeParameter
                    + " to the install inside " + stageDir);
            return null;
        }
        return stageDir.resolve(root);
    }

    /** {@code overlayTargetDir}, else {@code home/glassfish/modules}; {@code null} when no overlay needs it. */
    private static Path overlayDir(File overlayTargetDir, Path home, List<OverlayCoord> overlays,
                                   boolean overlaySkip, String homeParameter) throws MojoExecutionException {
        if (overlayTargetDir != null) {
            return overlayTargetDir.toPath();
        }
        if (home != null) {
            return home.resolve("glassfish/modules");
        }
        if (!overlaySkip && !overlays.isEmpty()) {
            throw new MojoExecutionException("<overlays> need a target: set <overlayTargetDir> or " + homeParameter);
        }
        return null;
    }

    /**
     * No overlay sets both {@code <skip>} and {@code <apply>}. Also with
     * {@code overlaySkip}, since the pom is wrong either way.
     */
    private static void checkOverlays(List<OverlayCoord> overlays) throws MojoExecutionException {
        for (OverlayCoord overlay : overlays) {
            if (overlay.getSkip() != null && overlay.getApply() != null) {
                throw new MojoExecutionException("Overlay " + overlay.toGav()
                        + " sets both <skip> and <apply>; set one of them");
            }
        }
    }

    /** Every certificate has an alias and a file, and the truststore is known, or found under {@code home}. */
    private static void checkCertImport(CertImport certImport, Path home, String homeParameter)
            throws MojoExecutionException {
        if (!certImport.hasWork()) {
            return;
        }

        for (TrustedCert cert : certImport.certs()) {
            if (cert.getAlias() == null || cert.getAlias().isBlank() || cert.getFile() == null) {
                throw new MojoExecutionException("<trustedCert> needs an <alias> and a <file> (got " + cert + ")");
            }
        }
        if (certImport.trustStore() == null && home == null) {
            throw new MojoExecutionException("<trustedCerts> need a truststore: set <trustStore> or " + homeParameter);
        }
    }

    /** Every command can be read, and the install they run on is known. */
    private static void checkCommands(StagingCommands commands, Path home, String homeParameter)
            throws MojoExecutionException {
        if (!commands.hasWork()) {
            return;
        }

        for (String command : commands.lines()) {
            try {
                PostBootCommands.words(command);
            } catch (IllegalArgumentException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        if (home == null) {
            throw new MojoExecutionException("Commands to run during staging need the install root: set "
                    + homeParameter);
        }
    }

    private static Path resolve(RepositorySystem repoSystem, RepositorySystemSession repoSession,
                                List<RemoteRepository> remoteRepos, ArtifactCoord coord) throws IOException {
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
