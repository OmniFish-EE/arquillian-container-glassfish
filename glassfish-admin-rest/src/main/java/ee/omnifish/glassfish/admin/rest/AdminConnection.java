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
package ee.omnifish.glassfish.admin.rest;

/**
 * Where a GlassFish DAS answers, how to log in to it, and the target that
 * deployments go to: the DAS itself ({@value AdminRestClient#ADMINSERVER}),
 * a standalone instance, or a cluster.
 */
public final class AdminConnection {

    private final String host;
    private final int port;
    private final boolean https;
    private final String user;
    private final String password;
    private final boolean ignoreCertificates;
    private final String target;

    /**
     * @param user the admin user, or {@code null} to send no credentials, as
     *        for GlassFish's default admin, which has no password
     * @param ignoreCertificates whether to trust any certificate, and any
     *        host name, over HTTPS
     */
    public AdminConnection(String host, int port, boolean https, String user, String password,
                           boolean ignoreCertificates, String target) {
        this.host = host;
        this.port = port;
        this.https = https;
        this.user = user;
        this.password = password;
        this.ignoreCertificates = ignoreCertificates;
        this.target = target;
    }

    /** The DAS on this machine, on {@code port}, over HTTP, without credentials, deploying to itself. */
    public static AdminConnection local(int port) {
        return new AdminConnection("localhost", port, false, null, null, false, AdminRestClient.ADMINSERVER);
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean https() {
        return https;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public boolean ignoreCertificates() {
        return ignoreCertificates;
    }

    public String target() {
        return target;
    }
}
