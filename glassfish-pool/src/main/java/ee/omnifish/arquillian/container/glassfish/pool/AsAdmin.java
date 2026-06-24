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
package ee.omnifish.arquillian.container.glassfish.pool;

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
 * Thin wrapper around {@code glassfish/bin/asadmin}. Used by the provisioner
 * to run {@code start-domain}/{@code stop-domain} against a per-slot install.
 *
 * <p>Acknowledged duplication with managed's {@code GlassFishServerControl};
 * a follow-up PR can promote this to {@code glassfish-common}.
 */
final class AsAdmin {

    private static final Logger LOG = Logger.getLogger(AsAdmin.class.getName());

    private static final String JAVA = System.getProperty("os.name").toLowerCase().contains("win")
            ? "java.exe"
            : "java";

    private final Path glassFishHome;
    private final Integer adminPort;

    AsAdmin(Path glassFishHome) {
        this(glassFishHome, null);
    }

    /**
     * @param adminPort DAS admin port to target via the {@code --port} program
     *        option, for subcommands that connect to a running DAS (e.g.
     *        {@code create-file-user}) instead of operating on a local domain
     *        directory. A {@code null} port omits {@code --port}, letting
     *        asadmin default to 4848 or resolve it from the domain operand.
     */
    AsAdmin(Path glassFishHome, Integer adminPort) {
        this.glassFishHome = glassFishHome;
        this.adminPort = adminPort;
    }

    /**
     * Run {@code asadmin <command> <args...>}. Output is collected and logged
     * at the level corresponding to the exit code; {@link AsAdminException}
     * is thrown with the captured output on non-zero. Waits unbounded for
     * the process to exit — use {@link #run(Duration, String, String...)}
     * if the caller can't tolerate a wedged asadmin.
     */
    void run(String command, String... args) throws AsAdminException {
        run(null, command, args);
    }

    /**
     * Like {@link #run(String, String...)} but bounded: if the asadmin process
     * does not exit within {@code timeout}, it is force-killed and an
     * {@link AsAdminException} is thrown. A {@code null} timeout means wait
     * unbounded.
     */
    void run(Duration timeout, String command, String... args) throws AsAdminException {
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

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int exit;
        try {
            Process process = pb.start();
            // Drain inline: asadmin can write enough to fill the OS pipe buffer,
            // so consuming after waitFor() risks deadlock. Buffer raw bytes and
            // decode once to avoid mangling multi-byte chars across read chunks.
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buf);
            }
            if (timeout == null) {
                exit = process.waitFor();
            } else if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AsAdminException(
                        "asadmin " + command + " timed out after " + timeout + "; output so far:\n"
                        + buf.toString(StandardCharsets.UTF_8));
            } else {
                exit = process.exitValue();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AsAdminException("asadmin " + command + " failed to run", e);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new AsAdminException("asadmin " + command + " exit=" + exit + " output:\n" + out);
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("asadmin " + command + " ok\n" + out);
        }
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

    static final class AsAdminException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AsAdminException(String message) {
            super(message);
        }

        AsAdminException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
