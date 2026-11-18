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
package ee.omnifish.arquillian.container.glassfish;

import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;

/**
 * Replaces the ports in the {@link HTTPContext}s of a deployment, for a
 * GlassFish that the tests reach on other ports than the ones it listens on,
 * such as one in a container whose ports are mapped. The host, the scheme and
 * the servlets stay as they are.
 */
public final class HttpContextPorts {

    private HttpContextPorts() {
    }

    /**
     * {@code metaData} with {@code httpPort} in its HTTP contexts and
     * {@code httpsPort} in its HTTPS ones. A {@code null} port leaves the
     * contexts of its scheme on the port GlassFish reported.
     */
    public static ProtocolMetaData override(ProtocolMetaData metaData, Integer httpPort, Integer httpsPort) {
        if (httpPort == null && httpsPort == null) {
            return metaData;
        }

        ProtocolMetaData overridden = new ProtocolMetaData();
        for (Object context : metaData.getContexts()) {
            overridden.addContext(context instanceof HTTPContext
                    ? withPort((HTTPContext) context, httpPort, httpsPort)
                    : context);
        }

        return overridden;
    }

    private static HTTPContext withPort(HTTPContext context, Integer httpPort, Integer httpsPort) {
        boolean secure = "https".equals(context.getScheme());
        Integer port = secure ? httpsPort : httpPort;
        if (port == null) {
            return context;
        }

        HTTPContext moved = new HTTPContext(context.getName(), context.getHost(), port, secure);
        for (Servlet servlet : context.getServlets()) {
            moved.add(new Servlet(servlet.getName(), servlet.getContextRoot()));
        }

        return moved;
    }
}
