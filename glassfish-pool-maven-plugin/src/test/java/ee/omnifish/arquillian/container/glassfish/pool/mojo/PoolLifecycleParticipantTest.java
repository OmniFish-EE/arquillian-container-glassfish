/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;

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
            // The slot setup, so slots a test JVM creates get the same setup as those up starts.
            assertEquals("${gf.pool.systemProperties}", variables.getChild("gf.pool.systemProperties").getValue());
            assertEquals("${gf.pool.maxHeapSize}", variables.getChild("gf.pool.maxHeapSize").getValue());
            assertEquals("${gf.pool.enableAssertions}", variables.getChild("gf.pool.enableAssertions").getValue());
            assertEquals("${gf.pool.postBootCommands}", variables.getChild("gf.pool.postBootCommands").getValue());
            assertEquals("${gf.pool.postStartCommands}", variables.getChild("gf.pool.postStartCommands").getValue());
            assertEquals("${gf.pool.failOnCommandError}",
                    variables.getChild("gf.pool.failOnCommandError").getValue());
            // Published for the pom, such as failsafe's forkCount; the test JVMs don't read it.
            assertNull(variables.getChild("gf.pool.size"));
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
        // Idempotent: each published property once, plus the unrelated variable.
        assertEquals(UpMojo.FORWARDED_PROPERTIES.size() + 1, forwarded.getChildCount());
    }

    private static Xpp3Dom variables(Xpp3Dom configuration) {
        return configuration.getChild("systemPropertyVariables");
    }

    /** One pool for the build, which the participant stops after the build instead. */
    @Test
    void bindsOnlyUpByDefault() {
        Plugin plugin = new Plugin();

        PoolLifecycleParticipant.bindGoals(plugin, new Properties());

        assertEquals(List.of("pool-up"), executionIds(plugin));
    }

    @Test
    void withMultiModuleBindsOnlyUp() {
        Plugin plugin = withMultiModule("true");

        PoolLifecycleParticipant.bindGoals(plugin, new Properties());

        assertEquals(List.of("pool-up"), executionIds(plugin));
    }

    @Test
    void withMultiModuleOffBindsUpAndDown() {
        Plugin plugin = withMultiModule("false");

        PoolLifecycleParticipant.bindGoals(plugin, new Properties());

        assertEquals(List.of("pool-up", "pool-down"), executionIds(plugin));
    }

    @Test
    void withMultiModuleOffFromTheCommandLineBindsUpAndDown() {
        Plugin plugin = new Plugin();
        Properties userProperties = new Properties();
        userProperties.setProperty("gf.pool.multiModule", "false");

        PoolLifecycleParticipant.bindGoals(plugin, userProperties);

        assertEquals(List.of("pool-up", "pool-down"), executionIds(plugin));
    }

    /** So that the up in the package the run forks starts its slots with a debugger. */
    @Test
    void aRunTurnsOnTheDebugger() {
        MavenProject project = new MavenProject();

        PoolLifecycleParticipant.debugRun(project, List.of("-pl", ":app", "glassfish-pool:run"));

        assertEquals("true", project.getProperties().getProperty("glassfish.debug"));
    }

    @Test
    void aBuildWithoutARunLeavesTheDebuggerOff() {
        MavenProject project = new MavenProject();

        PoolLifecycleParticipant.debugRun(project, List.of("verify"));

        assertNull(project.getProperties().getProperty("glassfish.debug"));
    }

    @Test
    void aRunLeavesTheDebugSettingOfThePom() {
        MavenProject project = new MavenProject();
        project.getProperties().setProperty("glassfish.debug", "false");

        PoolLifecycleParticipant.debugRun(project, List.of("glassfish-pool:run"));

        assertEquals("false", project.getProperties().getProperty("glassfish.debug"));
    }

    @Test
    void aRunIsTheGoalByPrefixOrByCoordinates() {
        assertTrue(PoolLifecycleParticipant.isRunGoal("glassfish-pool:run"));
        assertTrue(PoolLifecycleParticipant.isRunGoal("glassfish-pool:run@app"));
        assertTrue(PoolLifecycleParticipant.isRunGoal("ee.omnifish.arquillian:glassfish-pool-maven-plugin:run"));
        assertTrue(PoolLifecycleParticipant.isRunGoal("ee.omnifish.arquillian:glassfish-pool-maven-plugin:2.0:run"));
        assertFalse(PoolLifecycleParticipant.isRunGoal("glassfish-pool:up"));
        assertFalse(PoolLifecycleParticipant.isRunGoal("glassfish:run"));
        assertFalse(PoolLifecycleParticipant.isRunGoal("org.example:glassfish-pool-maven-plugin:run"));
    }

    /** The mojos then fall back to their default, so the participant does too. */
    @Test
    void aPropertyThatIsNotDefinedLeavesTheDefault() {
        Plugin plugin = withMultiModule("${shared.pool}");

        PoolLifecycleParticipant.bindGoals(plugin, new Properties());

        assertEquals(List.of("pool-up"), executionIds(plugin));
    }

    /**
     * The participant leaves executions the pom configures alone, so a
     * module's own {@code down} still runs where it's bound.
     */
    @Test
    void aDownThatAModuleBindsItselfIsKept() {
        Plugin plugin = new Plugin();
        PluginExecution down = new PluginExecution();
        down.setId("own-down");
        down.addGoal("down");
        plugin.addExecution(down);

        PoolLifecycleParticipant.bindGoals(plugin, new Properties());

        assertEquals(List.of("own-down", "pool-up"), executionIds(plugin));
    }

    /** As the mojos do: otherwise each module's pool runs until Maven exits. */
    @Test
    void aPomPropertySetsMultiModuleToo() {
        Plugin plugin = new Plugin();
        plugin.setGroupId(PoolLifecycleParticipant.GROUP_ID);
        plugin.setArtifactId(PoolLifecycleParticipant.ARTIFACT_ID);
        MavenProject project = new MavenProject();
        project.getBuild().addPlugin(plugin);
        project.getProperties().setProperty("gf.pool.multiModule", "false");
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());
        session.setProjects(List.of(project));

        new PoolLifecycleParticipant().afterProjectsRead(session);

        assertEquals(List.of("pool-up", "pool-down"), executionIds(plugin));
    }

    /**
     * Nothing answers on the slot's admin port, as after a {@code down} the
     * module binds itself, so stopping the pool again doesn't fail.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void afterTheBuildASharedPoolIsStopped(@TempDir Path tmp) throws Exception {
        PoolLifecycleParticipant.stopAfterBuild(poolWithOneSlot(tmp));

        new PoolLifecycleParticipant().afterSessionEnd(null);

        assertEquals(List.of("--terse stop-domain domain1"), Files.readAllLines(tmp.resolve("calls.log")));
    }

    /** As {@code down} does, since the next build's {@code up} would reuse the slot as it is. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void aSlotThatKeepsRunningFailsTheBuild(@TempDir Path tmp) throws Exception {
        PoolConfig pool = poolWithOneSlot(tmp);
        PoolLifecycleParticipant.stopAfterBuild(pool);

        try (ServerSocket stillRunning = new ServerSocket(pool.adminPort(1))) {
            MavenExecutionException e = assertThrows(MavenExecutionException.class,
                    () -> new PoolLifecycleParticipant().afterSessionEnd(null));

            assertTrue(e.getMessage().contains("still running"), e.getMessage());
            // Each pool is stopped once, so the next build's end doesn't try again.
            assertDoesNotThrow(() -> new PoolLifecycleParticipant().afterSessionEnd(null));
        }
    }

    /**
     * A pool in {@code tmp/pool} with one slot, whose {@code asadmin} logs its
     * arguments to {@code tmp/calls.log}. Its admin port is free.
     */
    static PoolConfig poolWithOneSlot(Path tmp) throws IOException {
        Path asadmin = Files.createDirectories(tmp.resolve("pool/slot-1/glassfish/glassfish/bin")).resolve("asadmin");
        Files.writeString(asadmin, "#!/bin/sh\necho \"$@\" >> '" + tmp.resolve("calls.log") + "'\n");
        assertTrue(asadmin.toFile().setExecutable(true));
        int adminPort;
        try (ServerSocket free = new ServerSocket(0)) {
            adminPort = free.getLocalPort();
        }

        return new PoolConfig(tmp.resolve("pool"), null, 1, adminPort, 100);
    }

    private static Plugin withMultiModule(String value) {
        Plugin plugin = new Plugin();
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom multiModule = new Xpp3Dom("multiModule");
        multiModule.setValue(value);
        configuration.addChild(multiModule);
        plugin.setConfiguration(configuration);

        return plugin;
    }

    private static List<String> executionIds(Plugin plugin) {
        return plugin.getExecutions().stream().map(PluginExecution::getId).collect(Collectors.toList());
    }
}
