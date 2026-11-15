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
package ee.omnifish.glassfish.admin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin wrapper around {@code glassfish/bin/asadmin}. Used by the pool to
 * start, restart and stop a slot's install and to run commands on it. Every
 * call has a deadline.
 *
 * <p>Acknowledged duplication with managed's {@code GlassFishServerControl},
 * which streams its output to the console instead.
 */
public final class AsAdmin {

    private static final Logger LOG = Logger.getLogger(AsAdmin.class.getName());

    /** How long the output may trail the process's exit. */
    private static final long DRAIN_GRACE_MILLIS = 5_000L;

    private static final String JAVA = System.getProperty("os.name").toLowerCase().contains("win")
            ? "java.exe"
            : "java";

    private final Path glassFishHome;
    private final Integer adminPort;

    public AsAdmin(Path glassFishHome) {
        this(glassFishHome, null);
    }

    /**
     * @param adminPort DAS admin port to target via the {@code --port} program
     *        option, for subcommands that connect to a running DAS (e.g.
     *        {@code create-file-user}) instead of operating on a local domain
     *        directory. A {@code null} port omits {@code --port}, letting
     *        asadmin default to 4848 or resolve it from the domain operand.
     */
    public AsAdmin(Path glassFishHome, Integer adminPort) {
        this.glassFishHome = glassFishHome;
        this.adminPort = adminPort;
    }

    /**
     * Run {@code asadmin <command> <args...>} and return its output. A
     * non-zero exit throws an {@link AsAdminException} with the output. If
     * asadmin hasn't exited within {@code timeout}, it is killed, together
     * with any processes it started, and an {@link AsAdminException} is thrown.
     * An interrupted call kills them the same way, and keeps the thread's
     * interrupt status.
     */
    public String run(Duration timeout, String command, String... args) throws AsAdminException {
        Path asadmin = asadminScript();
        List<String> cmd = new ArrayList<>();
        cmd.add(asadmin.toString());
        cmd.add("--terse");
        if (adminPort != null) {
            cmd.add("--port");
            cmd.add(adminPort.toString());
        }
        cmd.add(command);
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        // Ensure asadmin uses the same JDK as the calling process unless the
        // user has set AS_JAVA explicitly. asadmin honours AS_JAVA over JAVA_HOME.
        Path javaHome = Paths.get(System.getProperty("java.home"));
        pb.environment().putIfAbsent("AS_JAVA", javaHome.toString());
        pb.environment().putIfAbsent("JAVA_HOME", javaHome.toString());

        // Raw bytes, decoded once, so multi-byte characters split across reads
        // survive. ByteArrayOutputStream is synchronized, so the output so far
        // can be read while the drainer still writes.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new AsAdminException("asadmin " + command + " failed to run", e);
        }

        // Drain on another thread: asadmin can fill the pipe buffer, and a
        // hung asadmin keeps its output open, so reading it here would keep
        // the deadline below from ever being checked.
        Thread drainer = new Thread(() -> drain(process, buf), "asadmin-" + command);
        drainer.setDaemon(true);
        drainer.start();
        int exit;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyTree(process);
                throw new AsAdminException(
                        "asadmin " + command + " timed out after " + timeout + "; output so far:\n"
                        + buf.toString(StandardCharsets.UTF_8));
            }
            // The end of the output can trail the exit a little.
            drainer.join(DRAIN_GRACE_MILLIS);
            exit = process.exitValue();
        } catch (InterruptedException e) {
            // A cancelled call must not leave asadmin, or what it started, running.
            destroyTree(process);
            Thread.currentThread().interrupt();
            throw new AsAdminException("asadmin " + command + " was interrupted", e);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new AsAdminException("asadmin " + command + " exit=" + exit + " output:\n" + out);
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("asadmin " + command + " ok\n" + out);
        }

        return out;
    }

    private static void drain(Process process, ByteArrayOutputStream buf) {
        try (InputStream in = process.getInputStream()) {
            in.transferTo(buf);
        } catch (IOException e) {
            // The process was killed; the output so far is in buf.
        }
    }

    /**
     * Kill {@code process} and what it started: the asadmin launcher script
     * may have children that keep running, and keep its output open, after
     * the script itself is gone.
     */
    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private Path asadminScript() {
        Path script = glassFishHome.resolve("glassfish").resolve("bin").resolve(asadminFile());
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException(
                    "asadmin not found at " + script + " — is glassFishHome correct?");
        }
        return script;
    }

    private static String asadminFile() {
        return JAVA.equals("java.exe") ? "asadmin.bat" : "asadmin";
    }

    public static final class AsAdminException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AsAdminException(String message) {
            super(message);
        }

        AsAdminException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
