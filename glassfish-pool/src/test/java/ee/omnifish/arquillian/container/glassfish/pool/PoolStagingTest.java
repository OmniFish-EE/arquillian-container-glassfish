/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoolStagingTest {

    @Test
    void perOverlaySkipIsHonoured(@TempDir Path tmp) throws IOException {
        Path stage = tmp.resolve("stage");
        Path modules = tmp.resolve("modules");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        Path copiedJar = repo.resolve("copied.jar");
        Path skippedJar = repo.resolve("skipped.jar");
        Files.writeString(copiedJar, "copied");
        Files.writeString(skippedJar, "skipped");

        OverlayCoord copied = new OverlayCoord("g", "copied", "1", "jar", null, "copied.jar");
        OverlayCoord skipped = new OverlayCoord("g", "skipped", "1", "jar", null, "skipped.jar");
        skipped.setSkip(true);

        StageSpec spec = new StageSpec(stage, null, true, modules,
                List.of(copied, skipped), false);

        PoolStaging.stage(spec, coord -> "copied".equals(coord.getArtifactId()) ? copiedJar : skippedJar);

        assertThat(Files.exists(modules.resolve("copied.jar")), is(true));
        assertThat(Files.exists(modules.resolve("skipped.jar")), is(false));
        assertThat(Files.readString(modules.resolve("copied.jar")), equalTo("copied"));
    }

    @Test
    void singleRootDirectoryWithDirectoryEntries(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/", "glassfish9/bin/", "glassfish9/bin/asadmin", "glassfish9/glassfish/");

        assertThat(PoolStaging.singleRootDirectory(zip), equalTo("glassfish9"));
    }

    @Test
    void singleRootDirectoryWithoutDirectoryEntries(@TempDir Path tmp) throws IOException {
        // Zips need not carry explicit directory entries; the root is inferred from file paths.
        Path zip = zip(tmp, "payara6/bin/asadmin", "payara6/glassfish/domains/domain1/config/domain.xml");

        assertThat(PoolStaging.singleRootDirectory(zip), equalTo("payara6"));
    }

    @Test
    void singleRootDirectoryIsNullForSeveralRoots(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/bin/asadmin", "extras/readme.txt");

        assertThat(PoolStaging.singleRootDirectory(zip), is(nullValue()));
    }

    @Test
    void singleRootDirectoryIsNullForFileAtRoot(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/bin/asadmin", "LICENSE.txt");

        assertThat(PoolStaging.singleRootDirectory(zip), is(nullValue()));
    }

    @Test
    void distributionUnpacksUnderDerivedRoot(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/glassfish/bin/asadmin");
        Path stage = tmp.resolve("stage");
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion("9.0.0");

        PoolStaging.stage(new StageSpec(stage, dist, false, null, List.of(), false), coord -> zip);

        Path root = stage.resolve(PoolStaging.singleRootDirectory(zip));
        assertThat(Files.isRegularFile(root.resolve("glassfish/bin/asadmin")), is(true));
    }

    private static Path zip(Path dir, String... entries) throws IOException {
        Path zip = dir.resolve("dist.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    out.write(entry.getBytes());
                }
                out.closeEntry();
            }
        }
        return zip;
    }
}
