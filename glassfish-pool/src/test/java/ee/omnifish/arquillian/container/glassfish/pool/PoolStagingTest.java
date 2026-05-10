/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
