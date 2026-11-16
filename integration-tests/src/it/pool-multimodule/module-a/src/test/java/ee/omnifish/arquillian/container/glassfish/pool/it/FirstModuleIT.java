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
 * Runs in the first module: the shared pool is in the top-level project's
 * {@code target}, and its slot runs. Records the slot's server, for the
 * second module to check that it's still the same one.
 */
public class FirstModuleIT {

    @Test
    public void runsOnThePoolOfTheTopLevelProject() throws Exception {
        Path poolDir = Path.of(System.getProperty("gf.pool.dir"));
        assertThat(poolDir.toRealPath(), equalTo(Path.of("..", "target", "pool").toRealPath()));
        Path pid = poolDir.resolve("slot-1/glassfish/glassfish/domains/domain1/config/pid");
        assertThat("slot 1 runs", Files.exists(pid), is(true));

        Files.writeString(poolDir.resolveSibling("pid-seen-by-first-module"), Files.readString(pid));
    }
}
