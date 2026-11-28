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
package ee.omnifish.glassfish.admin.embedded;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModuleOptionsTest {

    private static final Module UNNAMED = ModuleOptionsTest.class.getClassLoader().getUnnamedModule();

    /** No test JVM opens sun.nio.fs, which GlassFish opens to read its files. */
    @Test
    void aPackageThatIsntOpenIsMissing() {
        assertThat(ModuleOptions.missing(List.of("--add-opens=java.base/sun.nio.fs=ALL-UNNAMED"), UNNAMED),
                contains("--add-opens=java.base/sun.nio.fs=ALL-UNNAMED"));
    }

    @Test
    void aPackageThatIsntExportedIsMissing() {
        assertThat(ModuleOptions.missing(List.of("--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED"),
                UNNAMED), contains("--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED"));
    }

    @Test
    void anExportedPackageIsntMissing() {
        assertThat(ModuleOptions.missing(List.of("--add-exports=java.base/java.lang=ALL-UNNAMED"), UNNAMED),
                empty());
    }

    /** A module the JVM doesn't have has nothing to open, as in a runtime image without it. */
    @Test
    void anOptionAboutAModuleTheJvmLacksIsntMissing() {
        assertThat(ModuleOptions.missing(List.of("--add-opens=no.such.module/no.such.pkg=ALL-UNNAMED"), UNNAMED),
                empty());
    }

    @Test
    void aModuleToAddThatTheJvmLacksIsMissing() {
        assertThat(ModuleOptions.missing(List.of("--add-modules=java.sql", "--add-modules=java.sql,no.such.module"),
                UNNAMED), contains("--add-modules=java.sql,no.such.module"));
    }
}
