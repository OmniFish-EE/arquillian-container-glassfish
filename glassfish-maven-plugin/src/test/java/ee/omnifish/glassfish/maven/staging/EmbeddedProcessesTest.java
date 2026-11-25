/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class EmbeddedProcessesTest {

    private static final Path STAGE = Path.of(File.separator + "build", "target", "dist").toAbsolutePath();
    private static final List<Path> DIRS = List.of(STAGE);

    @Test
    void theJarsChildRunsFromTheStageDirectory() {
        assertThat(EmbeddedProcesses.runsFrom("java -agentlib:jdwp=… -jar " + STAGE.resolve(
                "glassfish-embedded-all-8.0.5.jar") + " --httpPort=8080 --noInfo=true deploy shop.war", DIRS), is(true));
    }

    @Test
    void theStaticShellsChildRunsFromTheStageDirectory() {
        assertThat(EmbeddedProcesses.runsFrom("java --add-opens=java.base/java.lang=ALL-UNNAMED -cp "
                + STAGE.resolve("glassfish8/glassfish/lib/embedded/glassfish-embedded-static-shell.jar")
                + " org.glassfish.runnablejar.UberMain --domainDir=domain", DIRS), is(true));
    }

    @Test
    void othersDontRunGlassFishEmbeddedFromIt() {
        Path sibling = Path.of(STAGE + "2");

        assertThat(EmbeddedProcesses.runsFrom("java -jar " + sibling.resolve("embedded.jar"), DIRS), is(false));
        assertThat(EmbeddedProcesses.runsFrom("java -Dcom.sun.aas.instanceRoot="
                + STAGE.resolve("glassfish8/glassfish/domains/domain1")
                + " com.sun.enterprise.glassfish.bootstrap.ASMain", DIRS), is(false));
    }
}
