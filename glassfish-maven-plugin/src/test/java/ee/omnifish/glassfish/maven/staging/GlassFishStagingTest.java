/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlassFishStagingTest {

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

        StageSpec spec = new StageSpec(stage, null, true, null, modules,
                List.of(copied, skipped), false);

        GlassFishStaging.stage(spec, coord -> "copied".equals(coord.getArtifactId()) ? copiedJar : skippedJar);

        assertThat(Files.exists(modules.resolve("copied.jar")), is(true));
        assertThat(Files.exists(modules.resolve("skipped.jar")), is(false));
        assertThat(Files.readString(modules.resolve("copied.jar")), equalTo("copied"));
    }

    @Test
    void singleRootDirectoryWithDirectoryEntries(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/", "glassfish9/bin/", "glassfish9/bin/asadmin", "glassfish9/glassfish/");

        assertThat(GlassFishStaging.singleRootDirectory(zip), equalTo("glassfish9"));
    }

    @Test
    void singleRootDirectoryWithoutDirectoryEntries(@TempDir Path tmp) throws IOException {
        // Zips need not carry explicit directory entries; the root is inferred from file paths.
        Path zip = zip(tmp, "payara6/bin/asadmin", "payara6/glassfish/domains/domain1/config/domain.xml");

        assertThat(GlassFishStaging.singleRootDirectory(zip), equalTo("payara6"));
    }

    @Test
    void singleRootDirectoryIsNullForSeveralRoots(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/bin/asadmin", "extras/readme.txt");

        assertThat(GlassFishStaging.singleRootDirectory(zip), is(nullValue()));
    }

    @Test
    void singleRootDirectoryIsNullForFileAtRoot(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/bin/asadmin", "LICENSE.txt");

        assertThat(GlassFishStaging.singleRootDirectory(zip), is(nullValue()));
    }

    @Test
    void distributionUnpacksUnderDerivedRoot(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/glassfish/bin/asadmin");
        Path stage = tmp.resolve("stage");
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion("9.0.0");

        GlassFishStaging.stage(new StageSpec(stage, dist, false, null, null, List.of(), false), coord -> zip);

        Path root = stage.resolve(GlassFishStaging.singleRootDirectory(zip));
        assertThat(Files.isRegularFile(root.resolve("glassfish/bin/asadmin")), is(true));
    }

    @Test
    void rerunWithSameDistributionSkipsUnpack(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/glassfish/bin/asadmin");
        Path stage = tmp.resolve("stage");
        StageSpec spec = new StageSpec(stage, distribution("9.0.0"), false, null, null, List.of(), false);
        GlassFishStaging.stage(spec, coord -> zip);
        Path domainState = Files.createFile(stage.resolve("glassfish9/glassfish/domain-state.txt"));

        GlassFishStaging.stage(spec, coord -> zip);

        // A wipe + re-unpack would have removed it
        assertThat(Files.exists(domainState), is(true));
    }

    @Test
    void rebuiltZipReplacesStagedOne(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/old.txt");
        Path stage = tmp.resolve("stage");
        StageSpec spec = new StageSpec(stage, distribution("9.0.0-SNAPSHOT"), false, null, null, List.of(), false);
        GlassFishStaging.stage(spec, coord -> zip);

        // What `mvn install` of the SNAPSHOT does: same GAV and path, newer file
        zip(tmp, "glassfish9/new.txt");
        Files.setLastModifiedTime(zip, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        GlassFishStaging.stage(spec, coord -> zip);

        assertThat(Files.exists(stage.resolve("glassfish9/old.txt")), is(false));
        assertThat(Files.isRegularFile(stage.resolve("glassfish9/new.txt")), is(true));
    }

    @Test
    void otherDistributionReplacesStagedOne(@TempDir Path tmp) throws IOException {
        Path oldZip = zip(tmp.resolve("old"), "glassfish9/old.txt");
        Path newZip = zip(tmp.resolve("new"), "glassfish9/new.txt");
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), false, null, null, List.of(), false),
                coord -> oldZip);

        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.1"), false, null, null, List.of(), false),
                coord -> newZip);

        assertThat(Files.exists(stage.resolve("glassfish9/old.txt")), is(false));
        assertThat(Files.isRegularFile(stage.resolve("glassfish9/new.txt")), is(true));
    }

    private static DistributionCoord distribution(String version) {
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion(version);
        return dist;
    }

    private static Path zip(Path dir, String... entries) throws IOException {
        Files.createDirectories(dir);
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
