/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
