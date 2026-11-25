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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Per-slot {@code ports.properties}: produced by the provisioner once a slot's
 * GlassFish is up, consumed by the test JVM at lease time.
 *
 * <p>Property names are deliberately the same as the keys
 * {@link ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration}
 * expects so a leased slot's properties can be applied to a container config
 * verbatim.
 */
public final class SlotPorts {

    public static final String ADMIN_PORT = "glassfish.adminPort";
    public static final String HTTP_PORT = "glassfish.httpPort";
    public static final String HTTPS_PORT = "glassfish.httpsPort";
    public static final String GLASSFISH_HOME = "glassfish.home";
    /** The port a debugger attaches on; only there when the slot started with one. */
    public static final String DEBUG_PORT = "glassfish.debugPort";

    private final int adminPort;
    private final int httpPort;
    private final int httpsPort;
    private final String glassFishHome;
    private final int debugPort;

    public SlotPorts(int adminPort, int httpPort, int httpsPort, String glassFishHome) {
        this(adminPort, httpPort, httpsPort, glassFishHome, 0);
    }

    /** @param debugPort the port a debugger attaches on, or 0 for a slot that started without one */
    public SlotPorts(int adminPort, int httpPort, int httpsPort, String glassFishHome, int debugPort) {
        this.adminPort = adminPort;
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
        this.glassFishHome = glassFishHome;
        this.debugPort = debugPort;
    }

    public int adminPort() {
        return adminPort;
    }

    public int httpPort() {
        return httpPort;
    }

    public int httpsPort() {
        return httpsPort;
    }

    public String glassFishHome() {
        return glassFishHome;
    }

    /** The port a debugger attaches on, on localhost, or 0 when the slot started without one. */
    public int debugPort() {
        return debugPort;
    }

    public void writeTo(Path file) throws IOException {
        Properties props = new Properties();
        props.setProperty(ADMIN_PORT, String.valueOf(adminPort));
        props.setProperty(HTTP_PORT, String.valueOf(httpPort));
        props.setProperty(HTTPS_PORT, String.valueOf(httpsPort));
        props.setProperty(GLASSFISH_HOME, glassFishHome);
        if (debugPort > 0) {
            props.setProperty(DEBUG_PORT, String.valueOf(debugPort));
        }
        // Atomic publish: write to sibling tmp + move. A test JVM scanning
        // mid-write would otherwise see a half-formed properties file and
        // skip the slot until the next poll.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "Slot ports — written by PoolProvisioner");
        }
        Files.move(tmp, file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    public static SlotPorts readFrom(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        return new SlotPorts(
                requireInt(props, ADMIN_PORT),
                requireInt(props, HTTP_PORT),
                requireInt(props, HTTPS_PORT),
                require(props, GLASSFISH_HOME),
                props.containsKey(DEBUG_PORT) ? requireInt(props, DEBUG_PORT) : 0);
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Missing required property '" + key + "'");
        }
        return v;
    }

    private static int requireInt(Properties p, String key) {
        try {
            return Integer.parseInt(require(p, key));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Property '" + key + "' is not numeric: " + p.getProperty(key));
        }
    }
}
