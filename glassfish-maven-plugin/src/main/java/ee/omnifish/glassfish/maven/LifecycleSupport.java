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

import java.util.Collection;
import java.util.Objects;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project model edits shared by this plugin's and the pool plugin's lifecycle
 * participants: bind a goal without an {@code <executions>} block, and hand
 * project properties a goal publishes to the forked test JVMs.
 */
public final class LifecycleSupport {

    private static final String SYSTEM_PROPERTY_VARIABLES = "systemPropertyVariables";

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleSupport.class);

    private LifecycleSupport() {
    }

    /**
     * Add an execution {@code id} running {@code goal} unless the plugin already
     * runs that goal or uses that id. The execution carries no phase, so the
     * goal lands on its mojo's {@code defaultPhase}.
     */
    public static void bind(Plugin plugin, String id, String goal) {
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
        LOG.debug("Bound {}:{} as execution '{}'", plugin.getArtifactId(), goal, id);
    }

    /**
     * Add a {@code ${key}} reference for each of {@code keys} to the
     * {@code systemPropertyVariables} of {@code testPlugin} (surefire or
     * failsafe), which pass on {@code -D} user properties and those variables
     * but not project properties. Maven resolves the references when the test
     * plugin runs, after the goal that published the values; one that was never
     * published resolves to an empty string. Variables the user already
     * configures are left alone.
     *
     * <p>Applied to every execution as well as the plugin-level configuration:
     * model building has already merged the latter into each execution, and the
     * build plan reads an execution's own copy.
     */
    public static void forwardProperties(Plugin testPlugin, Collection<String> keys) {
        testPlugin.setConfiguration(withProperties((Xpp3Dom) testPlugin.getConfiguration(), keys));
        for (PluginExecution execution : testPlugin.getExecutions()) {
            execution.setConfiguration(withProperties((Xpp3Dom) execution.getConfiguration(), keys));
        }
    }

    private static Xpp3Dom withProperties(Xpp3Dom configuration, Collection<String> keys) {
        if (configuration == null) {
            configuration = new Xpp3Dom("configuration");
        }
        Xpp3Dom variables = configuration.getChild(SYSTEM_PROPERTY_VARIABLES);
        if (variables == null) {
            variables = new Xpp3Dom(SYSTEM_PROPERTY_VARIABLES);
            configuration.addChild(variables);
        }
        for (String key : keys) {
            if (variables.getChild(key) == null) {
                Xpp3Dom variable = new Xpp3Dom(key);
                variable.setValue("${" + key + "}");
                variables.addChild(variable);
            }
        }
        return configuration;
    }
}
