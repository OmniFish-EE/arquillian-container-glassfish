/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
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

    @Test
    void parseSystemPropertiesStripsBlanksAndComments() {
        String input = "# leading comment\n"
                + "javax.net.ssl.trustStorePassword=changeit\n"
                + "\n"
                + "  java.awt.headless=true\n"
                + "# trailing comment\n";
        assertThat(PoolConfig.parseSystemProperties(input),
                contains("javax.net.ssl.trustStorePassword=changeit",
                         "java.awt.headless=true"));
    }

    @Test
    void parseSystemPropertiesEmptyForNullOrBlank() {
        assertThat(PoolConfig.parseSystemProperties(null), empty());
        assertThat(PoolConfig.parseSystemProperties(""), empty());
        assertThat(PoolConfig.parseSystemProperties("   \n  \n"), empty());
    }

    @Test
    void systemPropertiesPropagatedFromConstructor() {
        PoolConfig c = new PoolConfig(Paths.get("/x"), null, 1, 14848, 100,
                java.util.List.of("a=b", "c=d"));
        assertThat(c.systemProperties(), contains("a=b", "c=d"));
    }

    @Test
    void suspendRejectsMultiSlotPool() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolConfig(Paths.get("/x"), null, 2, 14848, 100,
                        java.util.List.of(), false, true));
    }

    @Test
    void suspendAllowsSingleSlotPool() {
        PoolConfig c = new PoolConfig(Paths.get("/x"), null, 1, 14848, 100,
                java.util.List.of(), true, true);
        assertThat(c.debug(), equalTo(true));
        assertThat(c.suspend(), equalTo(true));
    }
}
