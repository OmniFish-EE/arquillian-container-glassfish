/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class GlassFishLifecycleParticipantTest {

    private static final String GLASSFISH = "ee.omnifish.glassfish:glassfish-maven-plugin";
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String FAILSAFE = "org.apache.maven.plugins:maven-failsafe-plugin";

    @Test
    void bindsProvisionAndForwardsHomeToSurefireAndFailsafe() {
        MavenProject project = project(glassfish(true, null), plugin(SUREFIRE), plugin(FAILSAFE));

        GlassFishLifecycleParticipant.wire(project);

        assertEquals(List.of("glassfish-provision:[provision]"), describe(project.getPlugin(GLASSFISH)));
        for (String testPlugin : List.of(SUREFIRE, FAILSAFE)) {
            assertEquals("${glassfish.home}", variables(project.getPlugin(testPlugin)).getChild("glassfish.home").getValue());
        }
    }

    @Test
    void leavesPluginWithoutExtensionsAlone() {
        MavenProject project = project(glassfish(false, null), plugin(SUREFIRE));

        GlassFishLifecycleParticipant.wire(project);

        assertTrue(project.getPlugin(GLASSFISH).getExecutions().isEmpty());
        assertNull(project.getPlugin(SUREFIRE).getConfiguration());
    }

    @Test
    void forwardsConfiguredHomeProperty() {
        MavenProject project = project(glassfish(true, "gf9.home"), plugin(FAILSAFE));

        GlassFishLifecycleParticipant.wire(project);

        Xpp3Dom variables = variables(project.getPlugin(FAILSAFE));
        assertEquals("${gf9.home}", variables.getChild("gf9.home").getValue());
        assertNull(variables.getChild("glassfish.home"));
    }

    @Test
    void forwardsHomePropertyOfEachExplicitExecution() {
        Plugin glassfish = glassfish(true, null);
        glassfish.addExecution(provision("gf7", "gf7.home"));
        glassfish.addExecution(provision("gf8", "gf8.home"));
        MavenProject project = project(glassfish, plugin(FAILSAFE));

        GlassFishLifecycleParticipant.wire(project);

        assertEquals(List.of("gf7:[provision]", "gf8:[provision]"), describe(glassfish));
        Xpp3Dom variables = variables(project.getPlugin(FAILSAFE));
        assertEquals("${gf7.home}", variables.getChild("gf7.home").getValue());
        assertEquals("${gf8.home}", variables.getChild("gf8.home").getValue());
    }

    private static MavenProject project(Plugin... plugins) {
        Build build = new Build();
        for (Plugin plugin : plugins) {
            build.addPlugin(plugin);
        }
        Model model = new Model();
        model.setBuild(build);
        return new MavenProject(model);
    }

    private static Plugin glassfish(boolean extensions, String homeProperty) {
        Plugin plugin = plugin(GLASSFISH);
        plugin.setExtensions(extensions);
        if (homeProperty != null) {
            plugin.setConfiguration(homePropertyConfiguration(homeProperty));
        }
        return plugin;
    }

    private static Plugin plugin(String key) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(key.substring(0, key.indexOf(':')));
        plugin.setArtifactId(key.substring(key.indexOf(':') + 1));
        return plugin;
    }

    private static PluginExecution provision(String id, String homeProperty) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.addGoal("provision");
        execution.setConfiguration(homePropertyConfiguration(homeProperty));
        return execution;
    }

    private static Xpp3Dom homePropertyConfiguration(String homeProperty) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom child = new Xpp3Dom("homeProperty");
        child.setValue(homeProperty);
        configuration.addChild(child);
        return configuration;
    }

    private static List<String> describe(Plugin plugin) {
        return plugin.getExecutions().stream()
                .map(execution -> execution.getId() + ":" + execution.getGoals())
                .collect(Collectors.toList());
    }

    private static Xpp3Dom variables(Plugin testPlugin) {
        return ((Xpp3Dom) testPlugin.getConfiguration()).getChild("systemPropertyVariables");
    }
}
