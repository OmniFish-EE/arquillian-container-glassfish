/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;

class UpMojoTest {

    private static final Path POOL = Path.of("target", "pool").toAbsolutePath();
    private static final Path SOURCE = Path.of("target", "dist", "glassfish9").toAbsolutePath();

    @Test
    void publishesPoolLayout() {
        Properties properties = new Properties();

        UpMojo.publish(properties, new PoolConfig(POOL, SOURCE, 2, 24848, 50, List.of()), new SystemStreamLog());

        assertEquals(POOL.toString(), properties.getProperty("gf.pool.dir"));
        assertEquals(SOURCE.toString(), properties.getProperty("gf.pool.source"));
        assertEquals("24848", properties.getProperty("gf.pool.adminBase"));
        assertEquals("50", properties.getProperty("gf.pool.portStride"));
    }

    @Test
    void omitsSourceWhenUnknown() {
        Properties properties = new Properties();

        UpMojo.publish(properties, new PoolConfig(POOL, null, 1, 14848, 100, List.of()), new SystemStreamLog());

        assertFalse(properties.containsKey("gf.pool.source"));
        assertEquals(POOL.toString(), properties.getProperty("gf.pool.dir"));
    }

    @Test
    void leavesAlreadySetPropertiesAlone() {
        Properties properties = new Properties();
        properties.setProperty("gf.pool.dir", "/somewhere/else");

        UpMojo.publish(properties, new PoolConfig(POOL, SOURCE, 1, 14848, 100, List.of()), new SystemStreamLog());

        assertEquals("/somewhere/else", properties.getProperty("gf.pool.dir"));
        assertEquals(SOURCE.toString(), properties.getProperty("gf.pool.source"));
    }
}
