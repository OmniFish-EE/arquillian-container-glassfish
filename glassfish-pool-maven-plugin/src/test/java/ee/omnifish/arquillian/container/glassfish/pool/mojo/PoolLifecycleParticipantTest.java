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
}
