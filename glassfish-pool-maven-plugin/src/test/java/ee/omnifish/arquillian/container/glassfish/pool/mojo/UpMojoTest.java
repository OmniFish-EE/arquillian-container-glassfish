/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;
import ee.omnifish.arquillian.container.glassfish.pool.SlotSetup;
import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.StagingCommands;

class UpMojoTest {

    private static final Path POOL = Path.of("target", "pool").toAbsolutePath();
    private static final Path SOURCE = Path.of("target", "dist", "glassfish9").toAbsolutePath();
    private static final File ROOT_TARGET = new File("/build/target");
    private static final File MODULE_TARGET = new File("/build/module/target");

    @Test
    void eachModuleHasItsOwnPoolByDefault() {
        UpMojo up = upInModule(false);

        assertEquals(new File(MODULE_TARGET, "pool"), up.poolDir());
        assertEquals(new File(MODULE_TARGET, "dist"), up.stageDir());
    }

    @Test
    void withMultiModuleTheModulesShareTheTopLevelProjectsPool() {
        UpMojo up = upInModule(true);

        assertEquals(new File(ROOT_TARGET, "pool"), up.poolDir());
        assertEquals(new File(ROOT_TARGET, "dist"), up.stageDir());
    }

    @Test
    void explicitDirectoriesWin() {
        UpMojo up = upInModule(true);
        up.poolDir = new File("/elsewhere/pool");
        up.stageDir = new File("/elsewhere/dist");

        assertEquals(new File("/elsewhere/pool"), up.poolDir());
        assertEquals(new File("/elsewhere/dist"), up.stageDir());
    }

    @Test
    void publishesPoolLayout() {
        Properties properties = new Properties();

        UpMojo.publish(properties, new PoolConfig(POOL, SOURCE, 2, 24848, 50, List.of()), new SystemStreamLog());

        assertEquals(POOL.toString(), properties.getProperty("gf.pool.dir"));
        assertEquals(SOURCE.toString(), properties.getProperty("gf.pool.source"));
        assertEquals("24848", properties.getProperty("gf.pool.adminBase"));
        assertEquals("50", properties.getProperty("gf.pool.portStride"));
    }

    /** Slots a test JVM creates must get the same setup as those up starts. */
    @Test
    void publishesSlotSetup() {
        Properties properties = new Properties();
        SlotSetup setup = new SlotSetup(List.of("a=b", "c=d"), "2048m", null,
                List.of("create-file-user --groups g u"), List.of(), false);

        UpMojo.publish(properties, new PoolConfig(POOL, SOURCE, 1, 14848, 100, setup, false, false),
                new SystemStreamLog());

        assertEquals("a=b\nc=d", properties.getProperty("gf.pool.systemProperties"));
        assertEquals("2048m", properties.getProperty("gf.pool.maxHeapSize"));
        assertEquals("create-file-user --groups g u", properties.getProperty("gf.pool.postBootCommands"));
        assertEquals("false", properties.getProperty("gf.pool.failOnCommandError"));
        // Unset ones are left out, so their forwarded references stay empty.
        assertFalse(properties.containsKey("gf.pool.enableAssertions"));
        assertFalse(properties.containsKey("gf.pool.postStartCommands"));
    }

    /** So failsafe's {@code <forkCount>${gf.pool.size}</forkCount>} follows the pool, however it's sized. */
    @Test
    void publishesThePoolSize() {
        Properties properties = new Properties();

        UpMojo.publish(properties, new PoolConfig(POOL, SOURCE, 5, 14848, 100, List.of()), new SystemStreamLog());

        assertEquals("5", properties.getProperty("gf.pool.size"));
    }

    @Test
    void omitsSourceWhenUnknown() {
        Properties properties = new Properties();

        UpMojo.publish(properties, new PoolConfig(POOL, null, 1, 14848, 100, List.of()), new SystemStreamLog());

        assertFalse(properties.containsKey("gf.pool.source"));
        assertEquals(POOL.toString(), properties.getProperty("gf.pool.dir"));
    }

    @Test
    void leavesAlreadySetPropertiesAlone() {
        Properties properties = new Properties();
        properties.setProperty("gf.pool.dir", "/somewhere/else");

        UpMojo.publish(properties, new PoolConfig(POOL, SOURCE, 1, 14848, 100, List.of()), new SystemStreamLog());

        assertEquals("/somewhere/else", properties.getProperty("gf.pool.dir"));
        assertEquals(SOURCE.toString(), properties.getProperty("gf.pool.source"));
    }

    /** On a source staging doesn't own, the commands would run on every build. */
    @Test
    void sourcePostBootCommandsNeedAnUnpack() {
        StagingCommands commands = new StagingCommands("create-file-user --groups g u", true);
        DistributionCoord distribution = new DistributionCoord();
        distribution.setVersion("9.0.0");

        MojoExecutionException withUnpackSkip = assertThrows(MojoExecutionException.class,
                () -> AbstractStagingPoolMojo.requireUnpack(commands, true, distribution));
        assertThrows(MojoExecutionException.class, () -> AbstractStagingPoolMojo.requireUnpack(commands, false, null));
        assertDoesNotThrow(() -> AbstractStagingPoolMojo.requireUnpack(commands, false, distribution));

        assertTrue(withUnpackSkip.getMessage().contains("give that goal the commands as <postBootCommands>"),
                withUnpackSkip.getMessage());
    }

    /** {@code up} running in a module of a build whose top-level project is the module's parent. */
    private static UpMojo upInModule(boolean multiModule) {
        MavenProject root = new MavenProject();
        root.getBuild().setDirectory(ROOT_TARGET.getPath());
        root.setExecutionRoot(true);
        MavenProject module = new MavenProject();
        module.getBuild().setDirectory(MODULE_TARGET.getPath());
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());
        session.setProjects(List.of(root, module));

        UpMojo up = new UpMojo();
        up.session = session;
        up.project = module;
        up.multiModule = multiModule;
        return up;
    }
}
