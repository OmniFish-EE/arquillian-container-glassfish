/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;

/** What {@code run} does before it leases a slot. Leasing and the run itself are covered by the pool-run IT. */
class RunMojoTest {

    @TempDir
    Path tmp;

    private final List<String> infos = new ArrayList<>();

    @Test
    void aProjectThatIsNoApplicationLeasesNothing() throws Exception {
        RunMojo run = runMojo("jar");

        run.execute();

        assertTrue(infos.stream().anyMatch(info -> info.contains("nothing to deploy in a jar project")), infos::toString);
        assertFalse(Files.exists(tmp.resolve("pool")));
    }

    @Test
    void aMissingArtifactFailsTheRunBeforeAnythingIsLeased() throws Exception {
        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> runMojo("war").execute());

        assertTrue(e.getMessage().contains("glassfish-pool:run has nothing to deploy: the package phase made no war"),
                e.getMessage());
        assertFalse(Files.exists(tmp.resolve("pool")));
    }

    @Test
    void skipSkipsTheRun() throws Exception {
        RunMojo run = runMojo("war");
        run.skip = true;

        run.execute();

        assertTrue(infos.contains("glassfish-pool:run skipped (gf.pool.skip=true)"), infos::toString);
    }

    /**
     * As in the Jakarta Security TCK, whose pom sets gf.pool.source, and whose
     * build sets during initialize which overlays to skip: the run uses what
     * the up in its package staged, instead of staging again, with the
     * overlays that up skipped.
     */
    @Test
    void aRunUsesTheSourceAnUpInItsPackageStagedAlsoWhereThePomSetsIt() {
        Path source = tmp.resolve("dist").resolve("glassfish9");
        MavenProject executedProject = new MavenProject();
        executedProject.getProperties().setProperty("gf.pool.source", source.toString());
        assertNull(RunMojo.stagedByUp(executedProject));

        UpMojo.recordStaging(executedProject.getProperties(),
                new PoolConfig(tmp.resolve("pool"), source, 1, 14848, 100, List.of()));

        assertEquals(source.toString(), RunMojo.stagedByUp(executedProject));
    }

    private RunMojo runMojo(String packaging) throws ReflectiveOperationException {
        MavenProject project = new MavenProject();
        project.setArtifactId("app");
        project.setPackaging(packaging);
        RunMojo run = new RunMojo();
        run.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                infos.add(content.toString());
            }
        });
        run.poolDir = tmp.resolve("pool").toFile();
        Field executedProject = RunMojo.class.getDeclaredField("executedProject");
        executedProject.setAccessible(true);
        executedProject.set(run, project);
        return run;
    }
}
