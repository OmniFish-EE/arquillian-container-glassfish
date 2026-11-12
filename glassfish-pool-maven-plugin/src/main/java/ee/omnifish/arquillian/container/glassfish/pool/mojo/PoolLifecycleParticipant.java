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

import java.util.Objects;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds {@code up} and {@code down} for every project that declares this
 * plugin with {@code <extensions>true</extensions>}, so the consumer pom needs
 * no {@code <executions>} block.
 *
 * <p>A {@code lifecycle-mappings}/{@code LifecycleMapping} component could only
 * contribute bindings for a custom packaging type, which would force users off
 * {@code jar}/{@code war}. A lifecycle participant instead adds the two
 * executions to the already-built project model, before Maven computes the
 * build plan. They carry no phase, so each goal lands on its mojo's
 * {@code defaultPhase} ({@code pre-}/{@code post-integration-test}).
 *
 * <p>Goals the user already binds in an explicit execution are left alone, so
 * existing poms that keep their {@code <executions>} block don't run a goal
 * twice. Registered via {@code META-INF/plexus/components.xml}; Maven only
 * loads it when the plugin is declared as an extension.
 */
public class PoolLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    static final String GROUP_ID = "ee.omnifish.arquillian";
    static final String ARTIFACT_ID = "glassfish-pool-maven-plugin";

    private static final Logger LOG = LoggerFactory.getLogger(PoolLifecycleParticipant.class);

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            Plugin plugin = project.getPlugin(GROUP_ID + ":" + ARTIFACT_ID);
            if (plugin != null) {
                bind(plugin, "pool-up", "up");
                bind(plugin, "pool-down", "down");
            }
        }
    }

    /**
     * Add an execution {@code id} running {@code goal} unless the plugin already
     * runs that goal or uses that id. Package-private for unit tests.
     */
    static void bind(Plugin plugin, String id, String goal) {
        for (PluginExecution existing : plugin.getExecutions()) {
            if (existing.getGoals().contains(goal) || Objects.equals(existing.getId(), id)) {
                return;
            }
        }
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.addGoal(goal);
        // Model building merges plugin-level <configuration> into each declared
        // execution before we run, and the build plan reads only the execution's
        // copy, so an added execution must carry the plugin config itself.
        if (plugin.getConfiguration() != null) {
            execution.setConfiguration(new Xpp3Dom((Xpp3Dom) plugin.getConfiguration()));
        }
        plugin.addExecution(execution);
        LOG.debug("Bound {}:{} as execution '{}'", ARTIFACT_ID, goal, id);
    }
}
