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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    AsAdmin(Path glassFishHome) {
        this.glassFishHome = glassFishHome;
    }

    /**
     * Run {@code asadmin <command> <args...>}. Output is collected and logged
     * at the level corresponding to the exit code; {@link AsAdminException}
     * is thrown with the captured output on non-zero.
     */
    void run(String command, String... args) throws AsAdminException {
        Path asadmin = asadminScript();
        List<String> cmd = new ArrayList<>();
        cmd.add(asadmin.toString());
        cmd.add("--terse");
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
            exit = process.waitFor();
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
