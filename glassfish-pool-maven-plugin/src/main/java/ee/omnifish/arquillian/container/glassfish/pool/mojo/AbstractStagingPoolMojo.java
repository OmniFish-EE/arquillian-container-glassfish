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
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import ee.omnifish.glassfish.maven.Stager;
import ee.omnifish.glassfish.maven.staging.CertImport;
import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
import ee.omnifish.glassfish.maven.staging.StageSpec;
import ee.omnifish.glassfish.maven.staging.TrustedCert;

/**
 * Adds staging configuration on top of {@link AbstractPoolMojo}: which
 * GlassFish distribution to fetch, which overlay jars to drop into the
 * staged install, where to unpack. Shared by {@link UpMojo} and
 * {@link ProvisionMojo}.
 *
 * <p>The staging itself is {@code glassfish:provision}'s, from
 * {@code glassfish-maven-plugin} (see {@link Stager}); only the parameters
 * live here, because their {@code gf.pool.*} names differ.
 */
abstract class AbstractStagingPoolMojo extends AbstractPoolMojo {

    /** How the pool spells its explicit install root, for staging messages. */
    private static final String POOL_SOURCE = "<poolSource> (gf.pool.source)";

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
        Stager stager = new Stager(repoSystem, repoSession, remoteRepos, getLog());
        CertImport certImport = new CertImport(trustedCerts, (trustStore == null) ? null : trustStore.toPath(),
                trustStorePassword, certsSkip);
        StageSpec spec = stager.spec(distribution, stageDir, unpackSkip, poolSource, overlays, overlayTargetDir,
                overlaySkip, certImport, POOL_SOURCE);
        if (poolSource == null && spec.home() != null) {
            poolSource = spec.home().toFile();
            getLog().info("poolSource not set; using " + poolSource + " from " + distribution.toGav());
        }
        if (!spec.hasWork()) {
            return null;
        }
        return () -> {
            try {
                stager.stage(spec);
            } catch (IOException e) {
                throw new RuntimeException("Pool staging failed: " + e.getMessage(), e);
            }
        };
    }
}
