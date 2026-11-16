/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Runs in the second module, which builds at the same time as the first:
 * once the first module's build is done, the slot still runs the server
 * that module saw, so nothing stopped the pool in between.
 */
public class SecondModuleIT {

    @Test
    public void runsOnTheSameServerAsTheFirstModule() throws Exception {
        Path poolDir = Path.of(System.getProperty("gf.pool.dir"));
        assertThat(poolDir.toRealPath(), equalTo(Path.of("..", "target", "pool").toRealPath()));
        awaitTheEndOfTheFirstModule();
        Path pid = poolDir.resolve("slot-1/glassfish/glassfish/domains/domain1/config/pid");

        assertThat(Files.readString(pid), equalTo(Files.readString(poolDir.resolveSibling("pid-seen-by-first-module"))));
    }

    /** The first module marks the end of its build in its {@code verify} phase. */
    private static void awaitTheEndOfTheFirstModule() throws InterruptedException {
        Path finished = Path.of("..", "module-a", "target", "finished");
        for (int i = 0; i < 1200 && !Files.exists(finished); i++) {
            Thread.sleep(100);
        }

        assertThat("the first module's build is done", Files.exists(finished), is(true));
    }
}
