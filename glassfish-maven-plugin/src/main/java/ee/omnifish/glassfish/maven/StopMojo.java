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

import java.io.IOException;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ee.omnifish.glassfish.maven.staging.GlassFishDomain;

/**
 * Stop {@code domain1} of the GlassFish install that {@code glassfish:start}
 * started: {@code stop-domain}, and {@code stop-domain --kill} if the
 * domain's own process still runs after that. Fails when it still runs after
 * both. A domain that doesn't run is fine, also when another server answers
 * on its admin port; that server keeps running.
 *
 * <p>It doesn't provision: it only works out where the install is, from the
 * same parameters, so it also stops a domain after the pom changed. Default
 * phase {@code post-integration-test}.
 */
@Mojo(name = "stop", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, requiresProject = false,
        threadSafe = true)
public class StopMojo extends AbstractStagingMojo {

    /** Skip this goal. */
    @Parameter(property = "glassfish.stop.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (isSkipped("stop", skip, "glassfish.stop.skip")) {
            return;
        }

        Path home = installRoot();
        if (home == null) {
            throw new MojoExecutionException("Can't stop GlassFish: its install root isn't known. Set <home>.");
        }
        stop(new GlassFishDomain(home), getLog());
    }

    /** Package-private for unit tests. */
    static void stop(GlassFishDomain domain, Log log) throws MojoExecutionException {
        try {
            if (domain.stop()) {
                log.info("GlassFish at " + domain.home() + " stopped");
            } else {
                log.info(GlassFishDomain.DOMAIN + " of " + domain.home() + " doesn't run");
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
