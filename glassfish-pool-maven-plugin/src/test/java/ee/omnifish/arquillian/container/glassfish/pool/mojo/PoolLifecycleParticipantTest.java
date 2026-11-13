/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

/**
 * Pool-specific wiring. Binding and the generic forwarding are tested with
 * {@code LifecycleSupport} in glassfish-maven-plugin.
 */
class PoolLifecycleParticipantTest {

    @Test
    void forwardsPoolPropertiesToFailsafeAsReferences() {
        Plugin failsafe = new Plugin();
        PluginExecution execution = new PluginExecution();
        execution.setId("default");
        execution.addGoal("integration-test");
        failsafe.addExecution(execution);

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
