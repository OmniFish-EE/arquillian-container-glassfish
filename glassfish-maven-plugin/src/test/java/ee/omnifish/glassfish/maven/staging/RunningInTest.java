/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GlassFishDomain#runningIn} with a stand-in server that runs on any
 * system: on Windows, where Java doesn't say what a process runs, only its
 * {@code config/pid} finds it.
 */
class RunningInTest {

    @TempDir
    Path stageDir;

    @Test
    void aServerOfAnInstallInTheStageDirectoryIsFoundByItsPidFile() throws Exception {
        Path install = stageDir.resolve("glassfish7");
        Path config = Files.createDirectories(install.resolve(GlassFishStaging.DOMAIN_CONFIG));
        Files.createDirectories(stageDir.resolve("glassfish8").resolve(GlassFishStaging.DOMAIN_CONFIG));
        Process server = startServer(config.getParent());
        try {
            // Written after its server started, as GlassFish does.
            Files.writeString(config.resolve("pid"), String.valueOf(server.pid()));

            assertThat(GlassFishDomain.runningIn(stageDir).map(GlassFishDomain::home), equalTo(Optional.of(install)));
        } finally {
            server.destroyForcibly().waitFor();
        }

        // Its config/pid stays behind, naming a process that ended.
        assertThat(GlassFishDomain.runningIn(stageDir), equalTo(Optional.empty()));
    }

    /** A Java process that idles, passed the domain's directory as GlassFish passes its server. */
    private Process startServer(Path domainDir) throws Exception {
        Path idle = Files.writeString(stageDir.resolve("Idle.java"),
                "class Idle { public static void main(String[] args) throws Exception { Thread.sleep(120_000); } }");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(java.toString(), idle.toString(), "-Dcom.sun.aas.instanceRoot=" + domainDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
