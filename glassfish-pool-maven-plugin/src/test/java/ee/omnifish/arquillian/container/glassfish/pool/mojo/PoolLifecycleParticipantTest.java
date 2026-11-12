/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class PoolLifecycleParticipantTest {

    @Test
    void bindsUpAndDownWithoutPhase() {
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
    void isIdempotent() {
        Plugin plugin = new Plugin();

        bindBoth(plugin);
        bindBoth(plugin);

        assertEquals(List.of("pool-up:[up]", "pool-down:[down]"), describe(plugin));
    }

    private static void bindBoth(Plugin plugin) {
        PoolLifecycleParticipant.bind(plugin, "pool-up", "up");
        PoolLifecycleParticipant.bind(plugin, "pool-down", "down");
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

    @Test
    void forwardsPoolPropertiesToFailsafeAsReferences() {
        Plugin failsafe = new Plugin();
        failsafe.addExecution(execution("default", "integration-test"));

        PoolLifecycleParticipant.forwardPoolProperties(failsafe);

        Xpp3Dom pluginLevel = variables((Xpp3Dom) failsafe.getConfiguration());
        Xpp3Dom executionLevel = variables((Xpp3Dom) failsafe.getExecutions().get(0).getConfiguration());
        for (Xpp3Dom variables : List.of(pluginLevel, executionLevel)) {
            assertEquals("${gf.pool.dir}", variables.getChild("gf.pool.dir").getValue());
            assertEquals("${gf.pool.source}", variables.getChild("gf.pool.source").getValue());
            assertEquals("${gf.pool.adminBase}", variables.getChild("gf.pool.adminBase").getValue());
            assertEquals("${gf.pool.portStride}", variables.getChild("gf.pool.portStride").getValue());
        }
    }

    @Test
    void keepsUserConfiguredFailsafeVariables() {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom variables = new Xpp3Dom("systemPropertyVariables");
        Xpp3Dom poolDir = new Xpp3Dom("gf.pool.dir");
        poolDir.setValue("/custom/pool");
        Xpp3Dom other = new Xpp3Dom("unrelated");
        other.setValue("kept");
        variables.addChild(poolDir);
        variables.addChild(other);
        configuration.addChild(variables);
        Plugin failsafe = new Plugin();
        failsafe.setConfiguration(configuration);

        PoolLifecycleParticipant.forwardPoolProperties(failsafe);
        PoolLifecycleParticipant.forwardPoolProperties(failsafe);

        Xpp3Dom forwarded = variables((Xpp3Dom) failsafe.getConfiguration());
        assertEquals("/custom/pool", forwarded.getChild("gf.pool.dir").getValue());
        assertEquals("kept", forwarded.getChild("unrelated").getValue());
        assertEquals("${gf.pool.source}", forwarded.getChild("gf.pool.source").getValue());
        assertEquals(5, forwarded.getChildCount()); // idempotent: no duplicates
    }

    private static Xpp3Dom variables(Xpp3Dom configuration) {
        return configuration.getChild("systemPropertyVariables");
    }
}
