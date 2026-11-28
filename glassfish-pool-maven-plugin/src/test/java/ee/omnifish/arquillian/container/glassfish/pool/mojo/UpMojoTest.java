/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.arquillian.container.glassfish.pool.EmbeddedPool;
import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;
import ee.omnifish.arquillian.container.glassfish.pool.SlotSetup;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.StagingCommands;

class UpMojoTest {

    private static final Path POOL = Path.of("target", "pool").toAbsolutePath();
    private static final Path SOURCE = Path.of("target", "dist", "glassfish9").toAbsolutePath();
    private static final File ROOT_TARGET = new File("/build/target");
    private static final File MODULE_TARGET = new File("/build/module/target");

    @Test
    void withMultiModuleOffEachModuleHasItsOwnPool() {
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

    /** There getTopLevelProject() is the first module of the build. */
    @Test
    void withMultiModuleThePoolIsWhereMavenRunsAlsoWhenPlLeavesThatProjectOut() {
        UpMojo up = upInModule(true);
        MavenProject root = up.session.getTopLevelProject();
        MavenProject otherModule = new MavenProject();
        otherModule.getBuild().setDirectory("/build/other/target");
        up.session.setProjects(List.of(up.project, otherModule));
        up.session.setAllProjects(List.of(root, up.project, otherModule));

        assertEquals(new File(ROOT_TARGET, "pool"), up.poolDir());
    }

    /** Say slot 1 started, and slot 2 didn't: slot 1 mustn't outlive the build. */
    @Test
    @DisabledOnOs(value = WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void withMultiModuleAPoolThatFailsToStartIsStillStoppedAfterTheBuild(@TempDir Path tmp) throws Exception {
        PoolConfig pool = PoolLifecycleParticipantTest.poolWithOneSlot(tmp);
        UpMojo up = new UpMojo() {
            @Override
            void startPool(PoolConfig poolConfig, Runnable preStage) throws IOException {
                throw new IOException("slot 2 didn't start");
            }
        };
        UpMojo inModule = upInModule(true);
        up.session = inModule.session;
        up.project = inModule.project;
        up.multiModule = true;
        up.poolDir = pool.poolDir().toFile();
        up.poolSize = 2;
        up.adminPortBase = pool.adminPort(1);
        up.portStride = 100;
        assertThrows(MojoExecutionException.class, up::execute);

        new PoolLifecycleParticipant().afterSessionEnd(null);

        assertEquals(List.of("--terse stop-domain domain1"), Files.readAllLines(tmp.resolve("calls.log")));
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

    /** GlassFish Embedded starts from the command line, by default, or in the JVM that asks for it. */
    @Test
    void anEmbeddedPoolStartsFromTheCommandLineOrInProcess() throws Exception {
        assertEquals("command-line", AbstractStagingPoolMojo.embeddedMode("command-line"));
        assertEquals("command-line", AbstractStagingPoolMojo.embeddedMode(null));
        assertEquals("in-process", AbstractStagingPoolMojo.embeddedMode(" In-Process "));
        MojoExecutionException other = assertThrows(MojoExecutionException.class,
                () -> AbstractStagingPoolMojo.embeddedMode("forked"));

        assertTrue(other.getMessage().contains("is command-line or in-process, not 'forked'"), other.getMessage());
    }

    /** The full and web editions ignore <embeddedMode>, which a parent pom may set for others. */
    @Test
    void onlyAnEmbeddedEditionChecksItsMode() throws Exception {
        UpMojo up = upInModule(true);
        up.embeddedMode = "forked";

        up.edition = "web";
        assertEquals("command-line", up.embeddedMode());

        up.edition = "embedded-web";
        assertThrows(MojoExecutionException.class, () -> up.embeddedMode());
    }

    /**
     * With in-process, up sets what the extension refers to in failsafe's
     * configuration: the JAR that names GlassFish's class path, and its
     * module options, replacing the empty ones the extension defaulted.
     */
    @Test
    void anInProcessPoolPublishesWhatTheTestJvmsHostItWith(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("pool");
        Path jar = tmp.resolve("dist/glassfish-embedded-web-8.0.5.jar");
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Add-Opens", "java.base/java.lang");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.finish();
        }
        EmbeddedPool.write(pool, EmbeddedGlassFish.jar(jar.getParent(), jar, null, null), "embedded-web",
                EmbeddedPool.IN_PROCESS);
        Properties properties = new Properties();
        properties.setProperty(UpMojo.EMBEDDED_JVM_OPTIONS, "");

        UpMojo.publishHosting(properties, new PoolConfig(pool, null, 1, PoolConfig.DEFAULT_ADMIN_BASE,
                PoolConfig.DEFAULT_PORT_STRIDE));

        assertEquals(jar.getParent().resolve("embedded-classpath.jar").toAbsolutePath().toString(),
                properties.getProperty(UpMojo.EMBEDDED_CLASSPATH));
        assertEquals("--add-opens=java.base/java.lang=ALL-UNNAMED", properties.getProperty(UpMojo.EMBEDDED_JVM_OPTIONS));
    }

    /** From the command line, the test JVMs start children, and need neither. */
    @Test
    void aCommandLinePoolPublishesNothingToHostWith(@TempDir Path tmp) throws Exception {
        Path pool = tmp.resolve("pool");
        EmbeddedPool.write(pool, EmbeddedGlassFish.jar(tmp, tmp.resolve("glassfish-embedded-web-8.0.5.jar"), null,
                null), "embedded-web");
        Properties properties = new Properties();

        UpMojo.publishHosting(properties, new PoolConfig(pool, null, 1, PoolConfig.DEFAULT_ADMIN_BASE,
                PoolConfig.DEFAULT_PORT_STRIDE));

        assertTrue(properties.isEmpty(), properties.toString());
    }

    @Test
    void anEmbeddedEditionsSlotsRunGlassFishEmbedded() throws Exception {
        UpMojo up = upInModule(true);
        up.adminPortBase = PoolConfig.DEFAULT_ADMIN_BASE;
        up.portStride = PoolConfig.DEFAULT_PORT_STRIDE;

        assertFalse(up.poolConfig().embedded());

        up.edition = "embedded";
        assertTrue(up.poolConfig().embedded());
    }

    /** GlassFish Embedded has no domain1 to run them on: its children run <postBootCommands> instead. */
    @Test
    void anEmbeddedEditionTakesNoSourcePostBootCommands() {
        UpMojo up = upInModule(true);
        up.edition = "embedded";
        up.version = "8.0.5";
        up.sourcePostBootCommands = "create-file-user --groups g u";

        MojoExecutionException e = assertThrows(MojoExecutionException.class, up::buildPreStage);

        assertTrue(e.getMessage().contains("Its children run <postBootCommands> on every start"), e.getMessage());
    }

    /** It stages what it runs, as glassfish:provision does, so it doesn't take an install of its own. */
    @Test
    void anEmbeddedEditionTakesNoPoolSource() {
        UpMojo up = upInModule(true);
        up.edition = "embedded";
        up.version = "8.0.5";
        up.poolSource = SOURCE.toFile();

        MojoExecutionException e = assertThrows(MojoExecutionException.class, up::buildPreStage);

        assertTrue(e.getMessage().contains("<poolSource> (gf.pool.source) and <unpackSkip> work with the full and "
                + "web editions"), e.getMessage());
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
