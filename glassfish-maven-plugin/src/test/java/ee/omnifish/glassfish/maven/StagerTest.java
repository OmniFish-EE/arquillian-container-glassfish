/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
import ee.omnifish.glassfish.maven.staging.StageSpec;

class StagerTest {

    private static final String HOME = "<home>";

    @TempDir
    Path tmp;

    private final RecordingLog log = new RecordingLog();

    @Test
    void derivesHomeFromZipRootAndOverlaysIntoIt() throws Exception {
        Path zip = zip("glassfish9/glassfish/bin/asadmin");
        File stageDir = tmp.resolve("dist").toFile();

        StageSpec spec = new Stager(coord -> zip, log)
                .spec(distribution("9.0.0"), stageDir, false, null, null, null, false, HOME);

        assertEquals(stageDir.toPath().resolve("glassfish9"), spec.home());
        assertEquals(spec.home().resolve("glassfish/modules"), spec.overlayTargetDir());
    }

    @Test
    void explicitHomeWins() throws Exception {
        File home = tmp.resolve("opt/glassfish9").toFile();

        StageSpec spec = new Stager(coord -> {
            throw new AssertionError("resolved " + coord.toGav());
        }, log).spec(distribution("9.0.0"), tmp.toFile(), false, home, null, null, false, HOME);

        assertEquals(home.toPath(), spec.home());
    }

    @Test
    void distributionWithoutVersionFails() {
        Stager stager = new Stager(coord -> zip("glassfish9/x"), log);

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> stager.spec(new DistributionCoord(), tmp.toFile(), false, null, null, null, false, HOME));

        assertThat(e.getMessage(), containsString("requires a <version>"));
    }

    @Test
    void zipWithSeveralRootsLeavesHomeUnset() throws Exception {
        Path zip = zip("glassfish9/bin/asadmin", "extras/readme.txt");

        StageSpec spec = new Stager(coord -> zip, log)
                .spec(distribution("9.0.0"), tmp.toFile(), false, null, null, null, false, HOME);

        assertNull(spec.home());
        assertThat(log.warnings.get(0), containsString("set <home>"));
    }

    @Test
    void overlaysWithoutTargetNameTheCallersHomeParameter() {
        Stager stager = new Stager(coord -> zip("glassfish9/x"), log);
        List<OverlayCoord> overlays = List.of(new OverlayCoord("g", "a", "1", "jar", null, "a.jar"));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> stager.spec(null, tmp.toFile(), true, null, overlays, null, false, "<poolSource> (gf.pool.source)"));

        assertThat(e.getMessage(), containsString("<overlayTargetDir> or <poolSource> (gf.pool.source)"));
    }

    private static DistributionCoord distribution(String version) {
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion(version);
        return dist;
    }

    private Path zip(String... entries) throws IOException {
        Path zip = tmp.resolve("dist.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.closeEntry();
            }
        }
        return zip;
    }
}
