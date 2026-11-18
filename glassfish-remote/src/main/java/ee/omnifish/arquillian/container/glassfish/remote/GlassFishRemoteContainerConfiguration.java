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
package ee.omnifish.arquillian.container.glassfish.remote;

import ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration;

/**
 * The remote container's configuration. {@code httpPort} and
 * {@code httpsPort} don't change the ports GlassFish listens on, as they do
 * for the managed container: they're the ports the tests reach it on, such as
 * ports a Docker container maps. Unset, the tests use the ports GlassFish
 * reports.
 */
public class GlassFishRemoteContainerConfiguration extends CommonGlassFishConfiguration {

    private Integer httpPort = port(System.getProperty("glassfish.httpPort"));
    private Integer httpsPort = port(System.getProperty("glassfish.httpsPort"));

    public Integer getHttpPort() {
        return httpPort;
    }

    /**
     * @param httpPort The port the tests reach GlassFish's HTTP listener on, instead of the port GlassFish
     *        reports. By default unset
     */
    public void setHttpPort(Integer httpPort) {
        this.httpPort = httpPort;
    }

    public Integer getHttpsPort() {
        return httpsPort;
    }

    /**
     * @param httpsPort The port the tests reach GlassFish's HTTPS listener on, instead of the port GlassFish
     *        reports. By default unset
     */
    public void setHttpsPort(Integer httpsPort) {
        this.httpsPort = httpsPort;
    }

    private static Integer port(String value) {
        return (value == null || value.isBlank()) ? null : Integer.valueOf(value.trim());
    }
}
