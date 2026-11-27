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

import ee.omnifish.glassfish.admin.embedded.EmbeddedPorts;

/**
 * Per-slot {@code ports.properties}: produced by the provisioner once a slot's
 * GlassFish is up, consumed by the test JVM at lease time.
 *
 * <p>Property names are deliberately the same as the keys
 * {@link ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration}
 * expects so a leased slot's properties can be applied to a container config
 * verbatim.
 *
 * <p>A slot for GlassFish Embedded ({@link #isEmbedded}) has no admin port
 * and no install: each deployment starts a child on its ports, which also
 * has the IIOP and JMS ports of the slot's own.
 */
public final class SlotPorts {

    public static final String ADMIN_PORT = "glassfish.adminPort";
    public static final String HTTP_PORT = "glassfish.httpPort";
    public static final String HTTPS_PORT = "glassfish.httpsPort";
    public static final String GLASSFISH_HOME = "glassfish.home";
    /** The port a debugger attaches on; only there when the slot started with one. */
    public static final String DEBUG_PORT = "glassfish.debugPort";
    /** Whether the slot runs GlassFish Embedded; only there when it does. */
    public static final String EMBEDDED = "glassfish.embedded";
    public static final String IIOP_PORT = "glassfish.iiopPort";
    public static final String IIOP_SSL_PORT = "glassfish.iiopSslPort";
    public static final String IIOP_MUTUAL_AUTH_PORT = "glassfish.iiopMutualAuthPort";
    public static final String JMS_PORT = "glassfish.jmsPort";
    public static final String JMS_RMI_PORT = "glassfish.jmsRmiPort";

    private final int adminPort;
    private final int httpPort;
    private final int httpsPort;
    private final String glassFishHome;
    private final int debugPort;
    private final boolean embedded;
    private final int iiopPort;
    private final int iiopSslPort;
    private final int iiopMutualAuthPort;
    private final int jmsPort;
    private final int jmsRmiPort;

    public SlotPorts(int adminPort, int httpPort, int httpsPort, String glassFishHome) {
        this(adminPort, httpPort, httpsPort, glassFishHome, 0);
    }

    /** @param debugPort the port a debugger attaches on, or 0 for a slot that started without one */
    public SlotPorts(int adminPort, int httpPort, int httpsPort, String glassFishHome, int debugPort) {
        this(adminPort, httpPort, httpsPort, glassFishHome, debugPort, false, 0, 0, 0, 0, 0);
    }

    private SlotPorts(int adminPort, int httpPort, int httpsPort, String glassFishHome, int debugPort,
                      boolean embedded, int iiopPort, int iiopSslPort, int iiopMutualAuthPort, int jmsPort,
                      int jmsRmiPort) {
        this.adminPort = adminPort;
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
        this.glassFishHome = glassFishHome;
        this.debugPort = debugPort;
        this.embedded = embedded;
        this.iiopPort = iiopPort;
        this.iiopSslPort = iiopSslPort;
        this.iiopMutualAuthPort = iiopMutualAuthPort;
        this.jmsPort = jmsPort;
        this.jmsRmiPort = jmsRmiPort;
    }

    /**
     * The ports of a slot for GlassFish Embedded, whose children open them.
     *
     * @param debugPort the port its children's debugger listens on, or 0 when they start without one
     */
    public static SlotPorts embedded(int httpPort, int httpsPort, int debugPort, int iiopPort, int iiopSslPort,
                                     int iiopMutualAuthPort, int jmsPort, int jmsRmiPort) {
        return new SlotPorts(0, httpPort, httpsPort, null, debugPort, true, iiopPort, iiopSslPort,
                iiopMutualAuthPort, jmsPort, jmsRmiPort);
    }

    /** The domain's admin port, or 0 for a slot for GlassFish Embedded, which has none. */
    public int adminPort() {
        return adminPort;
    }

    public int httpPort() {
        return httpPort;
    }

    public int httpsPort() {
        return httpsPort;
    }

    /** The slot's install, or {@code null} for a slot for GlassFish Embedded, which has none. */
    public String glassFishHome() {
        return glassFishHome;
    }

    /** The port a debugger attaches on, on localhost, or 0 when the slot started without one. */
    public int debugPort() {
        return debugPort;
    }

    /** Whether the slot runs GlassFish Embedded, a child per deployment, rather than a domain. */
    public boolean isEmbedded() {
        return embedded;
    }

    /**
     * The ports of a child of this slot for GlassFish Embedded, with its
     * debugger on {@code debugPort}, when it starts with one.
     */
    public EmbeddedPorts embeddedPorts(int debugPort) {
        return EmbeddedPorts.of(httpPort, httpsPort, debugPort, iiopPort, iiopSslPort, iiopMutualAuthPort, jmsPort,
                jmsRmiPort);
    }

    public void writeTo(Path file) throws IOException {
        Properties props = new Properties();
        if (embedded) {
            props.setProperty(EMBEDDED, "true");
        } else {
            props.setProperty(ADMIN_PORT, String.valueOf(adminPort));
            props.setProperty(GLASSFISH_HOME, glassFishHome);
        }
        props.setProperty(HTTP_PORT, String.valueOf(httpPort));
        props.setProperty(HTTPS_PORT, String.valueOf(httpsPort));
        if (debugPort > 0) {
            props.setProperty(DEBUG_PORT, String.valueOf(debugPort));
        }
        if (embedded) {
            props.setProperty(IIOP_PORT, String.valueOf(iiopPort));
            props.setProperty(IIOP_SSL_PORT, String.valueOf(iiopSslPort));
            props.setProperty(IIOP_MUTUAL_AUTH_PORT, String.valueOf(iiopMutualAuthPort));
            props.setProperty(JMS_PORT, String.valueOf(jmsPort));
            props.setProperty(JMS_RMI_PORT, String.valueOf(jmsRmiPort));
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
        int debugPort = props.containsKey(DEBUG_PORT) ? requireInt(props, DEBUG_PORT) : 0;
        if (Boolean.parseBoolean(props.getProperty(EMBEDDED))) {
            return embedded(
                    requireInt(props, HTTP_PORT),
                    requireInt(props, HTTPS_PORT),
                    debugPort,
                    requireInt(props, IIOP_PORT),
                    requireInt(props, IIOP_SSL_PORT),
                    requireInt(props, IIOP_MUTUAL_AUTH_PORT),
                    requireInt(props, JMS_PORT),
                    requireInt(props, JMS_RMI_PORT));
        }

        return new SlotPorts(
                requireInt(props, ADMIN_PORT),
                requireInt(props, HTTP_PORT),
                requireInt(props, HTTPS_PORT),
                require(props, GLASSFISH_HOME),
                debugPort);
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
