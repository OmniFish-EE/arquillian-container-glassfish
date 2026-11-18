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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;

import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.junit.jupiter.api.Test;

class HttpContextPortsTest {

    /** Also a port that isn't 8080: without an override, GlassFish knows best. */
    @Test
    void withoutOverridesTheReportedPortsStay() {
        ProtocolMetaData metaData = metaData(new HTTPContext("localhost", 28080, false));

        assertSame(metaData, HttpContextPorts.override(metaData, null, null));
    }

    @Test
    void anHttpContextGetsTheHttpPort() {
        ProtocolMetaData overridden = HttpContextPorts.override(
                metaData(new HTTPContext("localhost", 8080, false)), 32781, 32782);

        HTTPContext context = overridden.getContext(HTTPContext.class);
        assertEquals(32781, context.getPort());
        assertEquals("http", context.getScheme());
    }

    @Test
    void anHttpsContextGetsTheHttpsPort() {
        ProtocolMetaData overridden = HttpContextPorts.override(
                metaData(new HTTPContext("localhost", 8181, true)), 32781, 32782);

        HTTPContext context = overridden.getContext(HTTPContext.class);
        assertEquals(32782, context.getPort());
        assertEquals("https", context.getScheme());
    }

    /** Overriding only the other scheme's port leaves this one alone. */
    @Test
    void aContextWithoutAnOverrideForItsSchemeStays() {
        HTTPContext https = new HTTPContext("localhost", 8181, true);

        ProtocolMetaData overridden = HttpContextPorts.override(metaData(https), 32781, null);

        assertSame(https, overridden.getContext(HTTPContext.class));
    }

    /** The servlets are what the Arquillian protocol calls, so they have to come along, on the new port. */
    @Test
    void theServletsMoveToTheNewPort() {
        HTTPContext reported = new HTTPContext("docker-host", 8080, false);
        reported.add(new Servlet("ArquillianServletRunnerEE9", "/app"));

        HTTPContext context = HttpContextPorts.override(metaData(reported), 32781, null).getContext(HTTPContext.class);

        assertEquals("docker-host", context.getHost());
        Servlet servlet = context.getServletByName("ArquillianServletRunnerEE9");
        assertEquals(URI.create("http://docker-host:32781/app/"), servlet.getBaseURI());
    }

    @Test
    void otherContextsStay() {
        Object other = new Object();
        ProtocolMetaData metaData = new ProtocolMetaData().addContext(other)
                .addContext(new HTTPContext("localhost", 8080, false));

        ProtocolMetaData overridden = HttpContextPorts.override(metaData, 32781, null);

        assertSame(other, overridden.getContexts().get(0));
        assertEquals(32781, overridden.getContext(HTTPContext.class).getPort());
    }

    private static ProtocolMetaData metaData(HTTPContext context) {
        return new ProtocolMetaData().addContext(context);
    }
}
