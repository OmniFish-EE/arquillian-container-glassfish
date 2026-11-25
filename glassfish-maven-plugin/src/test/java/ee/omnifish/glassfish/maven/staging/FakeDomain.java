/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * An install whose {@code asadmin} is a shell script that appends its
 * arguments to {@code calls.log}, then runs the given shell lines, with a
 * {@code domain.xml} that has the given admin port. {@link #START} and
 * {@link #STOP} make {@code start-domain} start a stand-in server process,
 * whose pid goes where GlassFish keeps it, and {@code stop-domain} end it.
 */
public final class FakeDomain {

    /** Idles; its sleeps are short, so none outlives it for long. */
    private static final String IDLE = "while :; do sleep 1; done";

    /**
     * {@code start-domain} starts a stand-in server, which outlives the
     * script, as GlassFish does. GlassFish passes its server the domain's
     * directory, and so does this. It returns once the server runs, as
     * {@code start-domain} does: until then, its process is a copy of this
     * script, with this script's command line.
     */
    public static final String START = "  *start-domain*) sh -c '" + IDLE + "' \"-Dcom.sun.aas.instanceRoot=$DOMAIN\""
            + " >/dev/null 2>&1 & echo $! > \"$PID\"; i=0;"
            + " until ps -o args= -p $! | grep -q instanceRoot || [ $i -ge 50 ];"
            + " do sleep 0.1; i=$((i+1)); done ;;\n";

    /** {@code stop-domain}, with or without {@code --kill}, ends the stand-in server. */
    public static final String STOP = "  *stop-domain*) kill $(cat \"$PID\") && rm -f \"$PID\" ;;\n";

    private FakeDomain() {
    }

    /**
     * An install at {@code home}, whose {@code asadmin} runs {@code cases},
     * lines of a shell {@code case "$*" in}, with {@code $PID} the domain's
     * pid file, and {@code $DOMAIN} its directory.
     */
    public static Path install(Path home, int adminPort, String cases) throws IOException {
        Path config = Files.createDirectories(home.resolve(GlassFishStaging.DOMAIN_CONFIG));
        Files.writeString(config.resolve("domain.xml"), "<domain><configs><config name=\"server-config\">"
                + "<java-config debug-options=\"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=9009\"/>"
                + "<network-config><network-listeners>"
                + "<network-listener name=\"admin-listener\" port=\"" + adminPort + "\"/>"
                + "<network-listener name=\"http-listener-1\" port=\"8080\"/>"
                + "<network-listener name=\"http-listener-2\" port=\"8181\"/>"
                + "</network-listeners></network-config></config></configs></domain>");
        Path asadmin = Files.createDirectories(home.resolve("glassfish/bin")).resolve("asadmin");
        Files.writeString(asadmin, "#!/bin/sh\n"
                + "PID='" + config.resolve("pid") + "'\n"
                + "DOMAIN='" + config.getParent() + "'\n"
                + "echo \"$@\" >> \"$(dirname \"$0\")/calls.log\"\n"
                + "case \"$*\" in\n" + cases + "esac\n");
        if (!asadmin.toFile().setExecutable(true)) {
            throw new IOException("Can't make " + asadmin + " executable");
        }

        return home;
    }

    /** The asadmin calls made on the install, oldest first. */
    public static List<String> calls(Path home) throws IOException {
        Path log = home.resolve("glassfish/bin/calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    /** The stand-in server process, if it runs. */
    public static Optional<ProcessHandle> server(Path home) {
        return new GlassFishDomain(home).process();
    }

    /** A stand-in server for {@code home}'s domain, started outside asadmin, with its pid where GlassFish keeps it. */
    public static Process startServer(Path home) throws IOException {
        Path pid = Files.createDirectories(home.resolve(GlassFishStaging.DOMAIN_CONFIG)).resolve("pid");
        Files.writeString(pid, "");
        Process server = new ProcessBuilder("sh", "-c", IDLE, "-Dcom.sun.aas.instanceRoot=" + pid.getParent().getParent())
                .start();
        Files.writeString(pid, String.valueOf(server.pid()));
        return server;
    }

    /** A stand-in GlassFish Embedded child, which idles with {@code -jar <jar>} on its command line, as one does. */
    public static Process startEmbeddedChild(Path jar) throws IOException {
        return new ProcessBuilder("sh", "-c", IDLE, "-jar", jar.toAbsolutePath().toString()).start();
    }

    /** End the stand-in server a test left running. */
    public static void killServer(Path home) {
        server(home).ifPresent(ProcessHandle::destroyForcibly);
    }
}
