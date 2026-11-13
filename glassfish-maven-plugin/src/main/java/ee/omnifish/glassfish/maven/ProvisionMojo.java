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
import java.util.List;
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
import ee.omnifish.glassfish.maven.staging.StageSpec;

/**
 * Provision a GlassFish install for the build: resolve the distribution,
 * unpack it into {@code stageDir}, copy overlay jars into it, and publish the
 * install root as the project property {@code glassfish.home}. Default phase
 * {@code process-test-classes}, the last one before {@code test}, so the
 * install is there for surefire and failsafe alike.
 *
 * <p>The property is set even when the pom already defines it, with a
 * warning: unlike the pool plugin's properties it is no input to this goal,
 * only its result, and a stale pom value would otherwise point the tests at
 * something else. {@link GlassFishLifecycleParticipant} forwards it to the
 * test JVMs when the plugin is declared with {@code <extensions>true</extensions>}.
 *
 * <p>Skips when {@code -DskipTests} or {@code -Dmaven.test.skip} is set, like
 * the pool plugin's {@code up}.
 */
@Mojo(name = "provision", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, requiresProject = false,
        threadSafe = true)
public class ProvisionMojo extends AbstractMojo {

    static final String DEFAULT_HOME_PROPERTY = "glassfish.home";

    /** How this goal spells its explicit install root, for staging messages. */
    private static final String HOME_PARAMETER = "<home>";

    /**
     * GlassFish distribution to resolve and unpack. {@code groupId},
     * {@code artifactId} and {@code type} default to the Eclipse GlassFish zip
     * ({@code org.glassfish.main.distributions:glassfish:zip}), so only
     * {@code <version>} is required. Also takes text: a version, as in
     * {@code <distribution>9.0.0</distribution>}, or
     * {@code groupId:artifactId:version[:type[:classifier]]}.
     */
    @Parameter(property = "glassfish.distribution")
    private DistributionCoord distribution;

    /** Where the distribution zip is unpacked. */
    @Parameter(property = "glassfish.stageDir", defaultValue = "${project.build.directory}/dist")
    private File stageDir;

    /**
     * The install root. Defaults to the zip's top-level directory under
     * {@code stageDir} (e.g. {@code target/dist/glassfish9}). Set it when the
     * zip has several top-level entries, or with {@code unpackSkip} to overlay
     * into an existing install.
     */
    @Parameter
    private File home;

    /**
     * Project property that receives the install root. No {@code -D} switch,
     * so the extension, which forwards it to the test JVMs, always knows it.
     */
    @Parameter(defaultValue = DEFAULT_HOME_PROPERTY)
    private String homeProperty;

    /** Skip the unpack, e.g. to overlay into an existing install given as {@code home}. */
    @Parameter(property = "glassfish.unpack.skip", defaultValue = "false")
    private boolean unpackSkip;

    /**
     * Optional artifacts copied into the install's modules directory after
     * unpack. Each {@code <overlay>} is a GAV plus a {@code destFileName}.
     */
    @Parameter
    private List<OverlayCoord> overlays;

    /** Where overlay jars land. Defaults to {@code ${home}/glassfish/modules}. */
    @Parameter(property = "glassfish.overlayTargetDir")
    private File overlayTargetDir;

    /** Skip overlays. */
    @Parameter(property = "glassfish.overlay.skip", defaultValue = "false")
    private boolean overlaySkip;

    /** Skip this goal; nothing is unpacked or published. */
    @Parameter(property = "glassfish.provision.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "skipTests", defaultValue = "false")
    private boolean skipTests;

    @Parameter(property = "maven.test.skip", defaultValue = "false")
    private boolean mavenTestSkip;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session.executionRootDirectory}", readonly = true, required = true)
    private File executionRootDirectory;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepos;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("glassfish:provision skipped (glassfish.provision.skip=true)");
            return;
        }
        if (skipTests) {
            getLog().info("glassfish:provision skipped (-DskipTests)");
            return;
        }
        if (mavenTestSkip) {
            getLog().info("glassfish:provision skipped (-Dmaven.test.skip)");
            return;
        }
        // Because of its -D property, Maven injects an empty bean when <distribution> isn't configured.
        DistributionCoord dist = (distribution == null || distribution.isEmpty()) ? null : distribution;
        if (dist == null && home == null) {
            throw new MojoExecutionException("Nothing to provision: set <distribution> "
                    + "(-Dglassfish.distribution=<version>), or <home> for an existing install");
        }

        Stager stager = new Stager(repoSystem, repoSession, remoteRepos, getLog());
        StageSpec spec = stager.spec(dist, stageDir(), unpackSkip, home, overlays, overlayTargetDir,
                overlaySkip, HOME_PARAMETER);
        try {
            stager.stage(spec);
        } catch (IOException e) {
            throw new MojoExecutionException("Provisioning GlassFish failed: " + e.getMessage(), e);
        }

        if (spec.home() == null) {
            // A zip without a single root was already warned about by the Stager.
            if (unpackSkip) {
                getLog().warn("Not publishing " + homeProperty + ": the unpack is skipped and <home> isn't set");
            }
            return;
        }
        String installRoot = spec.home().toAbsolutePath().toString();
        publish(project.getProperties(), homeProperty, installRoot, getLog());
        getLog().info("GlassFish provisioned at " + installRoot + " (" + homeProperty + ")");
    }

    /**
     * {@code stageDir}, or {@code ./target/dist} when run without a pom: Maven's
     * stand-in project leaves the default's {@code ${project.build.directory}}
     * unresolved, which would otherwise end up as a literal directory name.
     */
    private File stageDir() {
        if (stageDir.getPath().contains("${")) {
            return new File(executionRootDirectory, "target/dist");
        }
        return stageDir;
    }

    /**
     * Set {@code key} to the install root, replacing a different value the pom
     * defined (with a warning). Package-private for unit tests.
     */
    static void publish(Properties projectProperties, String key, String installRoot, Log log) {
        String existing = projectProperties.getProperty(key);
        if (existing != null && !existing.equals(installRoot)) {
            log.warn("Replacing project property " + key + "=" + existing
                    + " with the provisioned GlassFish at " + installRoot);
        }
        projectProperties.setProperty(key, installRoot);
    }
}
