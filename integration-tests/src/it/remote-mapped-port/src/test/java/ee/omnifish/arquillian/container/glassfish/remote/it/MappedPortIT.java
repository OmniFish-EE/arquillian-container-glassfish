/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.remote.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;

import jakarta.inject.Inject;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * GlassFish reports 8080 as its HTTP port, and the remote container uses
 * {@code glassfish.httpPort}, the second listener's port, instead.
 */
@ExtendWith(ArquillianExtension.class)
public class MappedPortIT {

    @Inject
    Greeter greeter;

    @Deployment
    public static WebArchive deployment() {
        return ShrinkWrap.create(WebArchive.class, "hello.war").addClass(Greeter.class);
    }

    @Test
    @RunAsClient
    public void theApplicationIsReachedOnTheOverriddenPort(@ArquillianResource URL url) {
        assertEquals(Integer.getInteger("glassfish.httpPort"), url.getPort());
    }

    /** Arquillian runs this inside the server, with a request to the overridden port. */
    @Test
    public void aTestRunsInsideTheServer() {
        assertEquals("Hello, mapped port", greeter.greet("mapped port"));
    }
}
