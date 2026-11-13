/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class LifecycleSupportTest {

    @Test
    void bindsWithoutPhase() {
        Plugin plugin = new Plugin();

        bindBoth(plugin);

        assertEquals(List.of("pool-up:[up]", "pool-down:[down]"), describe(plugin));
        // No phase: Maven falls back to each mojo's defaultPhase.
        plugin.getExecutions().forEach(execution -> assertNull(execution.getPhase()));
    }

    @Test
    void copiesPluginConfigurationIntoExecutions() {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom poolSize = new Xpp3Dom("poolSize");
        poolSize.setValue("4");
        configuration.addChild(poolSize);
        Plugin plugin = new Plugin();
        plugin.setConfiguration(configuration);

        bindBoth(plugin);

        for (PluginExecution execution : plugin.getExecutions()) {
            Xpp3Dom copied = (Xpp3Dom) execution.getConfiguration();
            assertEquals("4", copied.getChild("poolSize").getValue());
            assertNotSame(configuration, copied);
        }
    }

    @Test
    void leavesExplicitlyBoundGoalsAlone() {
        Plugin plugin = new Plugin();
        plugin.addExecution(execution("start-pool", "up"));

        bindBoth(plugin);

        assertEquals(List.of("start-pool:[up]", "pool-down:[down]"), describe(plugin));
    }

    @Test
    void doesNotReuseAnExistingExecutionId() {
        Plugin plugin = new Plugin();
        plugin.addExecution(execution("pool-up", "status"));

        bindBoth(plugin);

        assertEquals(List.of("pool-up:[status]", "pool-down:[down]"), describe(plugin));
    }

    @Test
    void bindingIsIdempotent() {
        Plugin plugin = new Plugin();

        bindBoth(plugin);
        bindBoth(plugin);

        assertEquals(List.of("pool-up:[up]", "pool-down:[down]"), describe(plugin));
    }

    @Test
    void forwardsPropertiesAsReferences() {
        Plugin surefire = new Plugin();
        surefire.addExecution(execution("default-test", "test"));

        LifecycleSupport.forwardProperties(surefire, List.of("glassfish.home"));

        Xpp3Dom pluginLevel = variables((Xpp3Dom) surefire.getConfiguration());
        Xpp3Dom executionLevel = variables((Xpp3Dom) surefire.getExecutions().get(0).getConfiguration());
        for (Xpp3Dom variables : List.of(pluginLevel, executionLevel)) {
            assertEquals("${glassfish.home}", variables.getChild("glassfish.home").getValue());
        }
    }

    @Test
    void keepsUserConfiguredVariables() {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom variables = new Xpp3Dom("systemPropertyVariables");
        Xpp3Dom home = new Xpp3Dom("glassfish.home");
        home.setValue("/opt/glassfish9");
        Xpp3Dom other = new Xpp3Dom("unrelated");
        other.setValue("kept");
        variables.addChild(home);
        variables.addChild(other);
        configuration.addChild(variables);
        Plugin failsafe = new Plugin();
        failsafe.setConfiguration(configuration);

        LifecycleSupport.forwardProperties(failsafe, List.of("glassfish.home", "gf9.home"));
        LifecycleSupport.forwardProperties(failsafe, List.of("glassfish.home", "gf9.home"));

        Xpp3Dom forwarded = variables((Xpp3Dom) failsafe.getConfiguration());
        assertEquals("/opt/glassfish9", forwarded.getChild("glassfish.home").getValue());
        assertEquals("kept", forwarded.getChild("unrelated").getValue());
        assertEquals("${gf9.home}", forwarded.getChild("gf9.home").getValue());
        assertEquals(3, forwarded.getChildCount()); // idempotent: no duplicates
    }

    private static void bindBoth(Plugin plugin) {
        LifecycleSupport.bind(plugin, "pool-up", "up");
        LifecycleSupport.bind(plugin, "pool-down", "down");
    }

    private static PluginExecution execution(String id, String goal) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.addGoal(goal);
        return execution;
    }

    private static List<String> describe(Plugin plugin) {
        return plugin.getExecutions().stream()
                .map(execution -> execution.getId() + ":" + execution.getGoals())
                .collect(Collectors.toList());
    }

    private static Xpp3Dom variables(Xpp3Dom configuration) {
        return configuration.getChild("systemPropertyVariables");
    }
}
