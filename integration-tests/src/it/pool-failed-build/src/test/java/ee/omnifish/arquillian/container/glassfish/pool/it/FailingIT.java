/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Fails on purpose, once it has seen the pool's slot run, so the build
 * fails after {@code up} started the pool.
 */
public class FailingIT {

    @Test
    public void failsWhileThePoolRuns() {
        Path poolDir = Path.of(System.getProperty("gf.pool.dir"));
        Path pid = poolDir.resolve("slot-1/glassfish/glassfish/domains/domain1/config/pid");
        assertThat("slot 1 runs", Files.exists(pid), is(true));

        fail("Failing on purpose, with the pool running");
    }
}
