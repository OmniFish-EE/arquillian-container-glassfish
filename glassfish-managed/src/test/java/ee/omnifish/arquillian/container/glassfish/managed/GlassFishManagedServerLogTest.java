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
package ee.omnifish.arquillian.container.glassfish.managed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The container follows the domain's {@code server.log} from {@code start()}
 * to {@code stop()}, here with no server running, as before {@code deploy()}
 * starts one. Nothing answers on the admin port, so neither starts or stops
 * GlassFish.
 */
class GlassFishManagedServerLogTest {

    @TempDir
    Path home;

    @Test
    void whatTheServerWritesAfterStartReachesTheConsole() throws Exception {
        Path log = serverLog("written before\n");
        GlassFishManagedDeployableContainer container = container(true);

        String output = console(() -> {
            container.start();
            Files.writeString(log, "written while following\n", APPEND);
            container.stop();
        });

        assertThat(output, containsString("[glassfish:domain1] written while following"));
        assertThat(output, not(containsString("written before")));
    }

    @Test
    void theServerLogStaysOffTheConsoleByDefault() throws Exception {
        Path log = serverLog("");
        GlassFishManagedDeployableContainer container = container(false);

        String output = console(() -> {
            container.start();
            Files.writeString(log, "written while not following\n", APPEND);
            container.stop();
        });

        assertThat(output, not(containsString("written while not following")));
    }

    private Path serverLog(String content) throws Exception {
        Path logs = Files.createDirectories(home.resolve("glassfish/domains/domain1/logs"));
        return Files.writeString(logs.resolve("server.log"), content);
    }

    private GlassFishManagedDeployableContainer container(boolean serverLogToConsole) throws Exception {
        GlassFishManagedContainerConfiguration config = new GlassFishManagedContainerConfiguration();
        config.setGlassFishHome(home.toString());
        config.setAdminPort(freePort());
        config.setServerLogToConsole(serverLogToConsole);
        GlassFishManagedDeployableContainer container = new GlassFishManagedDeployableContainer();
        container.setup(config);

        return container;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** What {@code steps} print on {@code System.out}. */
    private static String console(Steps steps) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, UTF_8));
        try {
            steps.run();
        } finally {
            System.setOut(original);
        }

        return captured.toString(UTF_8);
    }

    @FunctionalInterface
    private interface Steps {
        void run() throws Exception;
    }
}
