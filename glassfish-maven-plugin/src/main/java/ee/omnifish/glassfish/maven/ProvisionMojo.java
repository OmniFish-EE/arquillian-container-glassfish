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
 * Provision a GlassFish install for the build: resolve the distribution,
 * unpack it into {@code stageDir}, copy overlay jars into it, import trusted
 * certificates, run post-boot commands on a brief start, and publish the
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
public class ProvisionMojo extends AbstractStagingMojo {

    /** Skip this goal; nothing is unpacked or published. */
    @Parameter(property = "glassfish.provision.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (isSkipped("provision", skip, "glassfish.provision.skip")) {
            return;
        }

        provision();
    }
}
