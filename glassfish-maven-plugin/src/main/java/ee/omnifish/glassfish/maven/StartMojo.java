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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Start {@code domain1} of the GlassFish install, and return once it answers
 * and its {@code postStartCommands} have run, leaving it running, for
 * example for the remote Arquillian Container. The install is provisioned
 * first, as {@code provision} does, which costs nothing when it's staged
 * already. Its {@code postBootCommands}, when staging has them to run, run on
 * this start, not on a brief one of their own, followed by a restart when
 * {@code postBootRestart} says so. Stop it with {@code glassfish:stop}.
 *
 * <p>The extension doesn't bind this goal: a server that outlives the build
 * should be started explicitly, in an {@code <execution>}. Default phase
 * {@code pre-integration-test}.
 */
@Mojo(name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, requiresProject = false,
        threadSafe = true)
public class StartMojo extends AbstractStartMojo {

    /** Skip this goal. */
    @Parameter(property = "glassfish.start.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (isSkipped("start", skip, "glassfish.start.skip")) {
            return;
        }

        requireDomainEdition("start");
        startDomain();
    }
}
