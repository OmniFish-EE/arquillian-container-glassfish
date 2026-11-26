/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;

/**
 * Publishes a pool's description of GlassFish Embedded again and again, in a
 * JVM of its own, as the {@code glassfish-pool:run} of another module that
 * shares the pool does: {@code <poolDir> <trustStore> <password> <times> <go>}.
 * It says it's ready, and starts once the file {@code go} exists, so that
 * two of them publish at once.
 */
public final class EmbeddedPoolWriter {

    private EmbeddedPoolWriter() {
    }

    public static void main(String[] args) throws Exception {
        Path pool = Paths.get(args[0]);
        Path stage = pool.resolveSibling("dist");
        EmbeddedGlassFish glassfish = EmbeddedGlassFish.jar(stage, stage.resolve("glassfish-embedded-all-8.0.5.jar"),
                Paths.get(args[1]), args[2]);
        System.out.println("ready");
        System.out.flush();
        Path go = Paths.get(args[4]);
        while (!Files.exists(go)) {
            Thread.sleep(10);
        }
        for (int i = 0; i < Integer.parseInt(args[3]); i++) {
            EmbeddedPool.write(pool, glassfish, "embedded");
        }
    }
}
