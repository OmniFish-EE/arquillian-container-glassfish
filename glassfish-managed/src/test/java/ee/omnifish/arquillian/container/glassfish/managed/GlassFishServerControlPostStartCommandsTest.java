/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.
 */
package ee.omnifish.arquillian.container.glassfish.managed;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The commands run after every start, against an install whose
 * {@code asadmin} is a shell script that logs its arguments.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
class GlassFishServerControlPostStartCommandsTest {

    /**
     * Regression: the commands went to port 4848, whatever the admin port,
     * and got the domain appended as an extra operand.
     */
    @Test
    void bothNamesRunOnTheAdminPortWithoutTheDomain(@TempDir Path home) throws Exception {
        Path asadmin = Files.createDirectories(home.resolve("glassfish/bin")).resolve("asadmin");
        Files.writeString(asadmin, "#!/bin/sh\necho \"$@\" >> \"$(dirname \"$0\")/calls.log\"\n");
        assertThat(asadmin.toFile().setExecutable(true), equalTo(true));
        Files.createDirectories(home.resolve("glassfish/domains/domain1"));
        GlassFishManagedContainerConfiguration config = new GlassFishManagedContainerConfiguration();
        config.setGlassFishHome(home.toString());
        config.setDomain("domain1");
        config.setAdminPort(14848);
        config.setOutputToConsole(false);
        config.setPostBootCommands("create-file-user --groups g old");
        config.setPostStartCommands("create-file-user --groups g new");
        config.validate();

        new GlassFishServerControl(config).runPostStartCommands();

        assertThat(Files.readAllLines(home.resolve("glassfish/bin/calls.log")), equalTo(List.of(
                "--terse --port 14848 create-file-user --groups g old",
                "--terse --port 14848 create-file-user --groups g new")));
    }
}
