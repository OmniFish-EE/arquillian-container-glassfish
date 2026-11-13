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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Binds {@code provision} for every project that declares this plugin with
 * {@code <extensions>true</extensions>}, and forwards the install root it
 * publishes to the test JVMs, so a consumer pom needs neither an
 * {@code <executions>} block nor {@code <systemPropertyVariables>}.
 *
 * <p>The added execution carries no phase, so it lands on the mojo's
 * {@code defaultPhase} ({@code process-test-classes}). A {@code provision}
 * the user already binds is left alone. The {@code homeProperty} of each
 * {@code provision} execution (default {@code glassfish.home}) is added to the
 * {@code systemPropertyVariables} of surefire and failsafe as a {@code ${...}}
 * reference; see {@link LifecycleSupport#forwardProperties}.
 *
 * <p>Registered via {@code META-INF/plexus/components.xml}. Maven loads it for
 * plugins declared as extensions, but also as part of the pool plugin, which
 * builds on this one; hence the explicit {@link Plugin#isExtensions()} check.
 */
public class GlassFishLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    static final String GROUP_ID = "ee.omnifish.glassfish";
    static final String ARTIFACT_ID = "glassfish-maven-plugin";
    static final List<String> TEST_PLUGINS = List.of(
            "org.apache.maven.plugins:maven-surefire-plugin",
            "org.apache.maven.plugins:maven-failsafe-plugin");

    private static final String GOAL = "provision";

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            wire(project);
        }
    }

    /** Bind and forward for one project. Package-private for unit tests. */
    static void wire(MavenProject project) {
        Plugin plugin = project.getPlugin(GROUP_ID + ":" + ARTIFACT_ID);
        if (plugin == null || !plugin.isExtensions()) {
            return;
        }
        LifecycleSupport.bind(plugin, "glassfish-provision", GOAL);
        Set<String> homeProperties = homeProperties(plugin);
        for (String key : TEST_PLUGINS) {
            Plugin testPlugin = project.getPlugin(key);
            if (testPlugin != null) {
                LifecycleSupport.forwardProperties(testPlugin, homeProperties);
            }
        }
    }

    /** The {@code homeProperty} of every execution that runs {@code provision}. */
    private static Set<String> homeProperties(Plugin plugin) {
        Set<String> keys = new LinkedHashSet<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getGoals().contains(GOAL)) {
                Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();
                Xpp3Dom homeProperty = (configuration == null) ? null : configuration.getChild("homeProperty");
                String key = (homeProperty == null) ? null : homeProperty.getValue();
                keys.add((key == null || key.isBlank()) ? ProvisionMojo.DEFAULT_HOME_PROPERTY : key.trim());
            }
        }
        return keys;
    }
}
