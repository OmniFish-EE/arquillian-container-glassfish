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
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import ee.omnifish.glassfish.maven.staging.CertImport;
import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.GlassFishStaging;
import ee.omnifish.glassfish.maven.staging.GlassFishStaging.CommandRunner;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
import ee.omnifish.glassfish.maven.staging.StageSpec;
import ee.omnifish.glassfish.maven.staging.StagingCommands;
import ee.omnifish.glassfish.maven.staging.TrustedCert;

/**
 * The staging parameters {@code provision}, {@code start} and {@code stop}
 * share, and the staging itself. {@code start} stages as {@code provision}
 * does, and {@code stop} only works out where the install is.
 */
abstract class AbstractStagingMojo extends AbstractMojo {


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
     * {@code <skip>true</skip>}, or its reverse {@code <apply>false</apply>},
     * leaves one out; an overlay sets one of them, not both.
     */
    @Parameter
    private List<OverlayCoord> overlays;

    /** Where overlay jars land. Defaults to {@code ${home}/glassfish/modules}. */
    @Parameter(property = "glassfish.overlayTargetDir")
    private File overlayTargetDir;

    /** Skip overlays. */
    @Parameter(property = "glassfish.overlay.skip", defaultValue = "false")
    private boolean overlaySkip;

    /**
     * Certificates GlassFish should trust, imported into the staged install's
     * truststore so they are there from its first start: an external identity
     * provider's server certificate for OpenID, or a client certificate for
     * {@code CLIENT-CERT}. Each {@code <trustedCert>} is an {@code <alias>}
     * and a PEM or DER {@code <file>}.
     * Changing the list stages the install afresh. With {@code unpackSkip},
     * the import only adds and replaces entries.
     */
    @Parameter
    private List<TrustedCert> trustedCerts;

    /**
     * The truststore the certificates go into. Defaults to the domain's own,
     * {@code ${home}/glassfish/domains/domain1/config/cacerts.p12}, or
     * {@code cacerts.jks} in older versions. This only changes where the
     * certificates go: GlassFish keeps reading the truststore its domain
     * configuration names.
     */
    @Parameter(property = "glassfish.trustStore")
    private File trustStore;

    /** The truststore's password. */
    @Parameter(property = "glassfish.trustStorePassword", defaultValue = CertImport.DEFAULT_PASSWORD)
    private String trustStorePassword;

    /** Skip the certificate import. */
    @Parameter(property = "glassfish.certs.skip", defaultValue = "false")
    private boolean certsSkip;

    /**
     * {@code asadmin} commands run once on the staged install, one per line,
     * during a brief start after the overlays and certificates: setup whose
     * effects persist, such as users, resources and {@code set} calls.
     * {@code glassfish:start} runs them on the start it makes anyway, so a
     * command that only takes effect when GlassFish restarts, such as
     * {@code create-jvm-options}, takes effect from the next start; run
     * {@code glassfish:provision} before it to have them in effect at once. Quotes
     * group words; backslashes are passed on as they are. Blank lines and
     * lines starting with {@code #} are skipped. Changing them stages the
     * install afresh. They need an install this goal unpacks, so they can't
     * be combined with {@code unpackSkip}, and need a {@code distribution}.
     *
     * <p>No {@code -D} switch: {@code glassfish.postBootCommands} is what the
     * Arquillian Containers run after every start.
     */
    @Parameter
    private String postBootCommands;

    /**
     * Whether a failed post-boot command fails the build, which then stages
     * the install afresh. When {@code false}, a failure is logged and the
     * remaining commands run.
     */
    @Parameter(property = "glassfish.failOnCommandError", defaultValue = "true")
    protected boolean failOnCommandError;

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

    /** Whether the goal's own {@code skip}, {@code -DskipTests} or {@code -Dmaven.test.skip} skips it. */
    protected boolean isSkipped(String goal, boolean skip, String skipProperty) {
        if (skip) {
            getLog().info("glassfish:" + goal + " skipped (" + skipProperty + "=true)");
            return true;
        }
        if (skipTests) {
            getLog().info("glassfish:" + goal + " skipped (-DskipTests)");
            return true;
        }
        if (mavenTestSkip) {
            getLog().info("glassfish:" + goal + " skipped (-Dmaven.test.skip)");
            return true;
        }

        return false;
    }

    /**
     * Stage the install, with its post-boot commands on a brief start, and
     * publish its root as {@code homeProperty}.
     *
     * @return the install root, or {@code null} when it isn't known
     */
    protected Path provision() throws MojoExecutionException {
        return provision(GlassFishStaging.BRIEF_START);
    }

    /**
     * Stage the install, with {@code commandRunner} running its post-boot
     * commands when they're due, and publish its root as {@code homeProperty}.
     *
     * @return the install root, or {@code null} when it isn't known
     */
    protected Path provision(CommandRunner commandRunner) throws MojoExecutionException {
        Stager stager = new Stager(repoSystem, repoSession, remoteRepos, getLog());
        StageSpec spec = spec(stager);
        try {
            stager.stage(spec, commandRunner);
        } catch (IOException e) {
            throw new MojoExecutionException("Provisioning GlassFish failed: " + e.getMessage(), e);
        }

        if (spec.home() == null) {
            // A zip without a single root was already warned about by the Stager.
            if (unpackSkip) {
                getLog().warn("Not publishing " + homeProperty + ": the unpack is skipped and <home> isn't set");
            }
            return null;
        }
        String installRoot = spec.home().toAbsolutePath().toString();
        publish(project.getProperties(), homeProperty, installRoot, getLog());
        getLog().info("GlassFish provisioned at " + installRoot + " (" + homeProperty + ")");
        return spec.home();
    }

    /**
     * Where the install is, without staging it: {@code home}, else the one
     * staged in {@code stageDir}, as its marker records it, else the one the
     * distribution would unpack to. The staged one wins over that, since
     * another distribution unpacks to another directory, while a domain
     * {@code glassfish:start} started runs from the one staged before.
     *
     * @return the install root, or {@code null} when it isn't known
     */
    protected Path installRoot() throws MojoExecutionException {
        if (home != null) {
            return home.toPath();
        }
        Path staged = GlassFishStaging.stagedHome(stageDir().toPath());
        if (staged != null) {
            return staged;
        }

        return spec(new Stager(repoSystem, repoSession, remoteRepos, getLog())).home();
    }

    private StageSpec spec(Stager stager) throws MojoExecutionException {
        // Because of its -D property, Maven injects an empty bean when <distribution> isn't configured.
        DistributionCoord dist = (distribution == null || distribution.isEmpty()) ? null : distribution;
        if (dist == null && home == null) {
            throw new MojoExecutionException("Nothing to provision: set <distribution> "
                    + "(-Dglassfish.distribution=<version>), or <home> for an existing install");
        }

        StagingCommands commands = new StagingCommands(postBootCommands, failOnCommandError);
        requireUnpack(commands, unpackSkip, dist);

        CertImport certImport = new CertImport(trustedCerts, (trustStore == null) ? null : trustStore.toPath(),
                trustStorePassword, certsSkip);
        return stager.spec(dist, stageDir(), unpackSkip, home, overlays, overlayTargetDir,
                overlaySkip, certImport, commands, HOME_PARAMETER);
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
     * Fail when there are commands but no unpack: staging owns only an
     * install it unpacks. On any other, the commands would run on every
     * build, and setup such as {@code create-file-user} fails the second
     * time. Package-private for unit tests.
     */
    static void requireUnpack(StagingCommands commands, boolean unpackSkip, DistributionCoord distribution)
            throws MojoExecutionException {
        if (commands.hasWork() && (unpackSkip || distribution == null)) {
            throw new MojoExecutionException("<postBootCommands> need an install this goal unpacks: with "
                    + "<unpackSkip>, or without <distribution>, they would run on every build. For commands that "
                    + "run after every start, use the managed Arquillian Container's glassfish.postStartCommands.");
        }
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
