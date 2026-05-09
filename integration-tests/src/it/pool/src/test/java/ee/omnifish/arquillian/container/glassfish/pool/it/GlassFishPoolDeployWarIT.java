/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end smoke test for the pool container: provisions a 1-slot pool
 * via {@code glassfish-pool:up} (driven by the surrounding pom's plugin
 * binding), leases the slot through the Arquillian extension, deploys a
 * war, hits a servlet, undeploys.
 */
@ExtendWith(ArquillianExtension.class)
public class GlassFishPoolDeployWarIT {

    @Deployment(testable = false)
    public static WebArchive deployment() {
        return ShrinkWrap.create(WebArchive.class, "pool-greet.war")
                .addClass(GreeterServlet.class);
    }

    @Test
    @RunAsClient
    public void servletRespondsThroughLeasedSlot(@ArquillianResource URL baseURL) throws Exception {
        URL endpoint = new URL(baseURL, "greet");
        HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
        conn.setRequestMethod("GET");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String body = r.lines().collect(Collectors.joining());
            assertThat(body, containsString("Hello from pool slot"));
        }
    }
}
