/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.remote.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The Arquillian remote container deploys a hello world servlet to GlassFish in Docker.
 * GlassFish in Docker will be started by GlassFishInDocker.
 * 
 * <p>
 * The Arquillian remote container picks up {@code glassfish.httpPort}, which overrides
 * the port 8080 GlassFish itself reports, and Arquillian uses whatthe remote container
 * returns for constructing the "@ArquillianResource URL".
 * 
 * <p>
 * Note that GlassFish IS actually running on 8080, but this is internally inside Docker.
 * In order for this test to reach GlassFish it needs the port Docker mapped this to.

 */
@ExtendWith({ GlassFishInDocker.class, ArquillianExtension.class })
public class DockerMappedPortIT {

    @Deployment(testable = false)
    public static WebArchive deployment() {
        return 
            ShrinkWrap.create(WebArchive.class, "hello.war")
                      .addClass(HelloServlet.class);
    }

    @Test
    @RunAsClient
    public void theServletAnswersOnTheMappedPort(@ArquillianResource URL url) throws Exception {
        assertEquals(Integer.getInteger("glassfish.httpPort"), url.getPort());

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url + "hello")).build(), 
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Hello from GlassFish in Docker", response.body());
    }
}
