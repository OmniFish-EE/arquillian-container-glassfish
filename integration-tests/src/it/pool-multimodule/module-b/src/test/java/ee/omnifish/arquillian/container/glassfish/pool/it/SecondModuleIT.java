/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Runs in the second module: the slot still runs the server the first
 * module saw, so nothing stopped the pool in between.
 */
public class SecondModuleIT {

    @Test
    public void runsOnTheSameServerAsTheFirstModule() throws Exception {
        Path poolDir = Path.of(System.getProperty("gf.pool.dir"));
        assertThat(poolDir.toRealPath(), equalTo(Path.of("..", "target", "pool").toRealPath()));
        Path pid = poolDir.resolve("slot-1/glassfish/glassfish/domains/domain1/config/pid");

        assertThat(Files.readString(pid), equalTo(Files.readString(poolDir.resolveSibling("pid-seen-by-first-module"))));
    }
}
