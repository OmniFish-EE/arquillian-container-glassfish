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
 *
 * <p>It also forwards the pool layout to the test JVMs: failsafe passes on
 * {@code -D} user properties and its own {@code systemPropertyVariables}, but
 * not project properties. So each of {@link UpMojo#PUBLISHED_PROPERTIES} is
 * added to failsafe's {@code systemPropertyVariables} as a {@code ${...}}
 * reference, which Maven resolves when failsafe runs, after {@code up} has
 * published the values. A reference {@code up} never published (e.g.
 * {@code -Dgf.pool.skip}) resolves to an empty string, which the test-JVM side
 * treats as unset. Entries the user already configures are left alone.
 */
public class PoolLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    static final String GROUP_ID = "ee.omnifish.arquillian";
    static final String ARTIFACT_ID = "glassfish-pool-maven-plugin";
    static final String FAILSAFE_KEY = "org.apache.maven.plugins:maven-failsafe-plugin";

    private static final String SYSTEM_PROPERTY_VARIABLES = "systemPropertyVariables";

    private static final Logger LOG = LoggerFactory.getLogger(PoolLifecycleParticipant.class);

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            Plugin plugin = project.getPlugin(GROUP_ID + ":" + ARTIFACT_ID);
            if (plugin != null) {
                bind(plugin, "pool-up", "up");
                bind(plugin, "pool-down", "down");
                Plugin failsafe = project.getPlugin(FAILSAFE_KEY);
                if (failsafe != null) {
                    forwardPoolProperties(failsafe);
                }
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

    /**
     * Add a {@code ${key}} reference for each published pool property to the
     * failsafe plugin's {@code systemPropertyVariables}. Applied to every
     * execution as well as the plugin-level configuration: model building has
     * already merged the latter into each execution, and the build plan reads
     * an execution's own copy. Package-private for unit tests.
     */
    static void forwardPoolProperties(Plugin failsafe) {
        failsafe.setConfiguration(withPoolProperties((Xpp3Dom) failsafe.getConfiguration()));
        for (PluginExecution execution : failsafe.getExecutions()) {
            execution.setConfiguration(withPoolProperties((Xpp3Dom) execution.getConfiguration()));
        }
    }

    private static Xpp3Dom withPoolProperties(Xpp3Dom configuration) {
        if (configuration == null) {
            configuration = new Xpp3Dom("configuration");
        }
        Xpp3Dom variables = configuration.getChild(SYSTEM_PROPERTY_VARIABLES);
        if (variables == null) {
            variables = new Xpp3Dom(SYSTEM_PROPERTY_VARIABLES);
            configuration.addChild(variables);
        }
        for (String key : UpMojo.PUBLISHED_PROPERTIES) {
            if (variables.getChild(key) == null) {
                Xpp3Dom variable = new Xpp3Dom(key);
                variable.setValue("${" + key + "}");
                variables.addChild(variable);
            }
        }
        return configuration;
    }
}
