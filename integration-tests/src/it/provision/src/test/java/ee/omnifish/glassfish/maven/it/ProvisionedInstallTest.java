/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Checks what {@code glassfish:provision} left behind, as a test JVM sees it.
 * The pom configures no {@code systemPropertyVariables}, so
 * {@code glassfish.home} only arrives through the plugin's extension. Runs
 * under surefire; {@link ProvisionedInstallIT} repeats it under failsafe.
 */
public class ProvisionedInstallTest {

    @Test
    public void glassFishHomeIsForwarded() {
        String home = System.getProperty("glassfish.home");

        assertThat(home, notNullValue());
        assertThat(Path.of(home).isAbsolute(), is(true));
        // The parent pom's glassfish.home=glassfish7 (a directory name) is replaced.
        assertThat(Path.of(home).getParent().toString(), endsWith("dist"));
    }

    @Test
    public void distributionIsUnpacked() {
        assertThat(Files.isRegularFile(home().resolve("glassfish/bin/asadmin")), is(true));
    }

    @Test
    public void overlayLandsInModules() {
        assertThat(Files.isRegularFile(home().resolve("glassfish/modules/overlay-probe.jar")), is(true));
    }

    private static Path home() {
        return Path.of(System.getProperty("glassfish.home"));
    }
}
