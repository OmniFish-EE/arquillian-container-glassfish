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
import java.util.List;
import java.util.Locale;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import ee.omnifish.arquillian.container.glassfish.pool.EmbeddedPool;
import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;
import ee.omnifish.glassfish.maven.Stager;
import ee.omnifish.glassfish.maven.staging.CertImport;
import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.Edition;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
import ee.omnifish.glassfish.maven.staging.StageSpec;
import ee.omnifish.glassfish.maven.staging.StagingCommands;
import ee.omnifish.glassfish.maven.staging.TrustedCert;

/**
 * Adds staging configuration on top of {@link AbstractPoolMojo}: which
 * GlassFish distribution to fetch, which overlay jars to drop into the
 * staged install, where to unpack, which certificates to import and which
 * commands to run on it. Shared by {@link UpMojo} and {@link ProvisionMojo}.
 *
 * <p>The staging itself is {@code glassfish:provision}'s, from
 * {@code glassfish-maven-plugin} (see {@link Stager}); only the parameters
 * live here, because their {@code gf.pool.*} names differ.
 *
 * <p>On an Embedded {@code <edition>}, each slot is a block of ports, on
 * which each deployment starts GlassFish Embedded in a JVM of its own. The
 * staging then describes that GlassFish in the pool directory
 * ({@link EmbeddedPool}), for the test JVMs.
 */
abstract class AbstractStagingPoolMojo extends AbstractPoolMojo {

    /** How the pool spells its explicit install root, for staging messages. */
    private static final String POOL_SOURCE = "<poolSource> (gf.pool.source)";

    /** The {@code <embeddedMode>} that starts each child from the command line. */
    static final String COMMAND_LINE = "command-line";

    /** The {@code <embeddedMode>} that runs GlassFish Embedded inside the test JVM, which #99 adds. */
    static final String IN_PROCESS = "in-process";

    /**
     * The GlassFish edition: {@code full}, {@code web}, {@code embedded} or
     * {@code embedded-web}. {@code full} and {@code web} unpack their
     * distribution ZIP and start a {@code domain1} per slot. {@code embedded}
     * and {@code embedded-web} make each slot a block of ports, on which each
     * deployment starts GlassFish Embedded, in a JVM of its own, with the
     * archive: from its self-contained JAR, or, when a module is overlaid,
     * from the static shell of the Full or Web ZIP.
     */
    @Parameter(property = "gf.pool.edition", defaultValue = "full")
    protected String edition;

    /**
     * The GlassFish version, for the artifact {@code <edition>} names.
     * {@code <distribution>} overrides it, and with {@code <poolSource>}, it
     * doesn't apply.
     */
    @Parameter(property = "gf.pool.version")
    protected String version;

    /**
     * How GlassFish Embedded starts, on an Embedded {@code <edition>}:
     * {@code command-line}, through its own launcher, in a JVM of its own per
     * deployment, with the archive, which the undeploy ends. {@code in-process},
     * inside the test JVM, isn't supported yet. {@code full} and {@code web}
     * ignore it, and {@code glassfish-pool:run} always runs from the command
     * line.
     */
    @Parameter(property = "gf.pool.embeddedMode", defaultValue = COMMAND_LINE)
    protected String embeddedMode;

    /**
     * GlassFish distribution to resolve and unpack, overriding {@code <version>}.
     * When neither is set, no unpack happens and {@code gf.pool.source} must
     * already point at a staged install. {@code groupId}, {@code artifactId}
     * and {@code type} default to the artifact of {@code <edition>}, the
     * Eclipse GlassFish zip ({@code org.glassfish.main.distributions:glassfish:zip})
     * by default, so only {@code <version>} is required.
     */
    @Parameter
    protected DistributionCoord distribution;

    /**
     * Where the dist zip is unpacked. Defaults to {@code dist} in the build
     * directory of the pool's root project, next to {@code poolDir}, or with
     * {@code multiModule} off in the project's own.
     */
    @Parameter(property = "gf.pool.stageDir")
    protected File stageDir;

    /** Skip the dist unpack. Set to {@code true} when reusing an existing install. */
    @Parameter(property = "gf.pool.unpack.skip", defaultValue = "false")
    protected boolean unpackSkip;

    /**
     * Optional artifacts copied into the staged install's modules directory
     * after unpack. Each {@code <overlay>} is a GAV plus a {@code destFileName}.
     * {@code <skip>true</skip>}, or its reverse {@code <apply>false</apply>},
     * leaves one out; an overlay sets one of them, not both.
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

    /**
     * Certificates GlassFish should trust, imported into the staged install's
     * truststore so they are there from its first start: an external identity
     * provider's server certificate for OpenID, or a client certificate for
     * {@code CLIENT-CERT}. Each {@code <trustedCert>} is an {@code <alias>}
     * and a PEM or DER {@code <file>}.
     * Slots inherit them, since they are cloned from the staged install.
     * Changing the list stages the install afresh. With {@code unpackSkip},
     * the import only adds and replaces entries.
     */
    @Parameter
    protected List<TrustedCert> trustedCerts;

    /**
     * The truststore the certificates go into. Defaults to the domain's own,
     * {@code ${gf.pool.source}/glassfish/domains/domain1/config/cacerts.p12}, or
     * {@code cacerts.jks} in older versions. This only changes where the
     * certificates go: GlassFish keeps reading the truststore its domain
     * configuration names.
     */
    @Parameter(property = "gf.pool.trustStore")
    protected File trustStore;

    /** The truststore's password. */
    @Parameter(property = "gf.pool.trustStorePassword", defaultValue = CertImport.DEFAULT_PASSWORD)
    protected String trustStorePassword;

    /** Skip the certificate import. */
    @Parameter(property = "gf.pool.certs.skip", defaultValue = "false")
    protected boolean certsSkip;

    /**
     * {@code asadmin} commands run once on the source install, one per line,
     * during a brief start after the overlays and certificates, before any
     * slot is cloned from it. Slots inherit what the commands write to files
     * that cloning copies, such as {@code domain.xml} and {@code keyfile},
     * but not what lives in memory, in the JMS broker's store or in the OSGi
     * cache. Same syntax as {@link #postBootCommands}, and failures are
     * handled by {@link #failOnCommandError}. Changing them stages the source
     * afresh. They need a source this goal unpacks, so they can't be combined
     * with {@code unpackSkip}, and need a {@code distribution}.
     */
    @Parameter(property = "gf.pool.sourcePostBootCommands")
    protected String sourcePostBootCommands;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * Build the {@code Runnable} that {@link
     * ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap#up} runs
     * inside its provisioning lock: the staging, then, on an Embedded
     * edition, the description of what the slots run, or else its removal.
     *
     * <p>Also derives {@link #poolSource} from the distribution when it isn't
     * set, so callers must invoke this <em>before</em> {@link #poolConfig()}.
     */
    protected Runnable buildPreStage() throws MojoExecutionException {
        Edition edition = edition();
        boolean overlaid = OverlayCoord.anyApplied(overlays, overlaySkip);
        // A pom's version property mustn't make an existing install unpack one.
        DistributionCoord dist = edition.distribution((poolSource == null) ? version : null, distribution, overlaid);
        Stager.checkEdition(edition, dist, poolSource, unpackSkip, overlaid, POOL_SOURCE);
        StagingCommands sourceCommands = new StagingCommands(sourcePostBootCommands, failOnCommandError);
        if (edition.isEmbedded() && sourceCommands.hasWork()) {
            throw new MojoExecutionException("<sourcePostBootCommands> run on a brief start of domain1, which "
                    + "GlassFish Embedded doesn't have. Its children run <postBootCommands> on every start instead.");
        }
        requireUnpack(sourceCommands, unpackSkip, dist);

        Stager stager = new Stager(repoSystem, repoSession, remoteRepos, getLog());
        CertImport certImport = new CertImport(trustedCerts, (trustStore == null) ? null : trustStore.toPath(),
                trustStorePassword, certsSkip);
        StageSpec spec = stager.spec(dist, stageDir(), unpackSkip, poolSource, overlays, overlayTargetDir,
                overlaySkip, certImport, sourceCommands, POOL_SOURCE);
        if (poolSource == null && spec.home() != null) {
            poolSource = spec.home().toFile();
            getLog().info("poolSource not set; using " + poolSource + " from " + dist.toGav());
        }
        Path pool = poolDir().toPath();
        Path explicitTrustStore = (trustStore == null) ? null : trustStore.toPath();
        return () -> {
            try {
                if (spec.hasWork()) {
                    stager.stage(spec);
                }
                if (edition.isEmbedded()) {
                    EmbeddedPool.write(pool, Stager.embeddedGlassFish(spec, explicitTrustStore, trustStorePassword),
                            edition.toString());
                } else {
                    EmbeddedPool.remove(pool);
                }
            } catch (IOException | MojoExecutionException e) {
                throw new RuntimeException("Pool staging failed: " + e.getMessage(), e);
            }
        };
    }

    /** With the slots of an Embedded {@code <edition>}. */
    @Override
    protected PoolConfig poolConfig() throws MojoExecutionException {
        return poolConfig(edition().isEmbedded());
    }

    /** The {@code <edition>}: {@code full} also when Maven didn't configure this goal, as in a unit test. */
    protected Edition edition() throws MojoExecutionException {
        if (edition == null) {
            return Edition.FULL;
        }

        try {
            return Edition.of(edition);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Fail on an {@code <embeddedMode>} that {@code up} and {@code provision}
     * can't set an Embedded edition's slots up for. The other editions
     * ignore it.
     */
    protected void requireCommandLineMode() throws MojoExecutionException {
        if (edition().isEmbedded()) {
            requireCommandLineMode(embeddedMode);
        }
    }

    /** Package-private for unit tests. */
    static void requireCommandLineMode(String embeddedMode) throws MojoExecutionException {
        String mode = (embeddedMode == null) ? COMMAND_LINE : embeddedMode.trim().toLowerCase(Locale.ROOT);
        if (IN_PROCESS.equals(mode)) {
            throw new MojoExecutionException("<embeddedMode>in-process</embeddedMode> isn't supported yet: GlassFish "
                    + "Embedded runs from the command line, in a JVM of its own per deployment. Leave out "
                    + "<embeddedMode>, or set it to command-line.");
        }
        if (!COMMAND_LINE.equals(mode)) {
            throw new MojoExecutionException("<embeddedMode> is command-line or in-process, not '" + embeddedMode
                    + "'");
        }
    }

    /** {@code stageDir}, or its default. */
    protected File stageDir() {
        return (stageDir != null) ? stageDir : inBuildDirectory("dist");
    }

    /**
     * Fail when there are source commands but no unpack: staging owns only
     * a source it unpacks. On any other, the commands would run on every
     * build, and setup such as {@code create-file-user} fails the second
     * time. Package-private for unit tests.
     */
    static void requireUnpack(StagingCommands commands, boolean unpackSkip, DistributionCoord distribution)
            throws MojoExecutionException {
        if (commands.hasWork() && (unpackSkip || distribution == null)) {
            throw new MojoExecutionException("<sourcePostBootCommands> need a source this goal unpacks: with "
                    + "<unpackSkip>, or without <distribution>, they would run on every build. To set up a source "
                    + "staged by glassfish:provision, give that goal the commands as <postBootCommands>, or use "
                    + "<postBootCommands> here, which run once per slot.");
        }
    }
}
