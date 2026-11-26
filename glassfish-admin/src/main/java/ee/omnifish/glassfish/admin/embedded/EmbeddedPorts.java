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

import java.util.List;

/**
 * The ports of a GlassFish Embedded child: HTTP, HTTPS, its debugger, and the
 * ports an application's remote EJBs and JMS open, its three IIOP listeners,
 * the JMS broker and the broker's RMI registry. {@link #standard()} leaves
 * those to Embedded's own {@code domain.xml}; a pool slot moves them, so the
 * children of several slots run side by side.
 */
public final class EmbeddedPorts {

    private final int http;
    private final int https;
    private final String debugAddress;
    private final int iiop;
    private final int iiopSsl;
    private final int iiopMutualAuth;
    private final int jms;
    private final int jmsRmi;

    private EmbeddedPorts(int http, int https, String debugAddress, int iiop, int iiopSsl, int iiopMutualAuth,
            int jms, int jmsRmi) {
        this.http = http;
        this.https = https;
        this.debugAddress = debugAddress;
        this.iiop = iiop;
        this.iiopSsl = iiopSsl;
        this.iiopMutualAuth = iiopMutualAuth;
        this.jms = jms;
        this.jmsRmi = jmsRmi;
    }

    /**
     * HTTP on 8080, HTTPS on 8181, the debugger on GlassFish's own port, on
     * localhost only, and the IIOP and JMS ports as Embedded's own
     * {@code domain.xml} has them.
     */
    public static EmbeddedPorts standard() {
        return new EmbeddedPorts(8080, 8181, "localhost:9009", 0, 0, 0, 0, 0);
    }

    /** All ports given, the debugger's on localhost. */
    public static EmbeddedPorts of(int http, int https, int debug, int iiop, int iiopSsl, int iiopMutualAuth,
            int jms, int jmsRmi) {
        return new EmbeddedPorts(http, https, "localhost:" + debug, iiop, iiopSsl, iiopMutualAuth, jms, jmsRmi);
    }

    public int http() {
        return http;
    }

    public int https() {
        return https;
    }

    /** Where a debugger attaches, as {@code host:port}. */
    public String debugAddress() {
        return debugAddress;
    }

    public int iiop() {
        return iiop;
    }

    public int iiopSsl() {
        return iiopSsl;
    }

    public int iiopMutualAuth() {
        return iiopMutualAuth;
    }

    public int jms() {
        return jms;
    }

    public int jmsRmi() {
        return jmsRmi;
    }

    /** Whether the IIOP and JMS ports are set, rather than left as Embedded's own {@code domain.xml} has them. */
    public boolean movesOthers() {
        return iiop != 0;
    }

    /** The ports a child opens when it starts: HTTP, and unless it runs the {@code jar}, HTTPS. */
    public List<Integer> listening(boolean jar) {
        return jar ? List.of(http) : List.of(http, https);
    }
}
