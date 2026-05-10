/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

class PoolConfigTest {

    @Test
    void portStrideMath() {
        PoolConfig config = new PoolConfig(
                Paths.get("/tmp/pool"), Paths.get("/src"), 4, 14848, 100);
        assertThat(config.adminPort(1), equalTo(14848));
        assertThat(config.httpPort(1), equalTo(14849));
        assertThat(config.httpsPort(1), equalTo(14850));
        assertThat(config.adminPort(4), equalTo(15148));
    }

    @Test
    void rejectsTooSmallStride() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolConfig(Paths.get("/x"), null, 1, 14848, 5));
    }

    @Test
    void acceptsMinStride() {
        // 10 is the documented minimum (one GF domain occupies 10 ports).
        PoolConfig c = new PoolConfig(Paths.get("/x"), null, 1, 14848, 10);
        assertThat(c.portStride(), equalTo(10));
    }
}
