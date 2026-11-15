/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A source install whose {@code asadmin} is a shell script that appends its
 * arguments to {@code calls.log} next to itself, then runs the given shell
 * lines: enough for {@link PoolProvisioner} to clone, patch, "start" and set
 * up a slot without GlassFish. Its {@code domain.xml} has the three listeners
 * and a {@code java-config}.
 */
final class FakeGlassFish {

    /** Fails a command line containing {@code boom}, and answers the restart check with {@code false}. */
    static final String BOOM_FAILS = "case \"$*\" in\n"
            + "  *boom*) echo \"Remote failure: boom\"; exit 1 ;;\n"
            + "  *_get-restart-required*) echo false ;;\n"
            + "esac\n";

    private FakeGlassFish() {
    }

    /** An install whose asadmin exits with {@code exitCode} for every command. */
    static Path install(Path dir, int exitCode) throws IOException {
        return install(dir, "exit " + exitCode + "\n");
    }

    /** An install whose asadmin runs {@code script} after logging its arguments. */
    static Path install(Path dir, String script) throws IOException {
        Path asadmin = Files.createDirectories(dir.resolve("glassfish/bin")).resolve("asadmin");
        Files.writeString(asadmin, "#!/bin/sh\n"
                + "echo \"$@\" >> \"$(dirname \"$0\")/calls.log\"\n"
                + script);
        assertThat(asadmin.toFile().setExecutable(true), equalTo(true));
        Path config = Files.createDirectories(dir.resolve("glassfish/domains/domain1/config"));
        Files.writeString(config.resolve("domain.xml"), "<domain><configs><config name=\"server-config\">"
                + "<java-config><jvm-options>-Xmx512m</jvm-options></java-config>"
                + "<network-config><network-listeners>"
                + "<network-listener name=\"admin-listener\" port=\"4848\"/>"
                + "<network-listener name=\"http-listener-1\" port=\"8080\"/>"
                + "<network-listener name=\"http-listener-2\" port=\"8181\"/>"
                + "</network-listeners></network-config></config></configs></domain>");
        return dir;
    }

    /** The asadmin calls made on a slot's install, oldest first. */
    static List<String> calls(Path slotInstall) throws IOException {
        Path log = slotInstall.resolve("glassfish/bin/calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    /**
     * Something answering on {@code port}, as a running server's admin port
     * does, until the returned socket is closed. The fake asadmin's
     * {@code stop-domain} doesn't stop it.
     */
    static ServerSocket answerOn(int port) throws IOException {
        ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket accepted = socket.accept()) {
                    // A probe only needs the connect to succeed.
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-admin-port");
        acceptor.setDaemon(true);
        acceptor.start();
        return socket;
    }

    /** A port nothing listens on, at least right now. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
