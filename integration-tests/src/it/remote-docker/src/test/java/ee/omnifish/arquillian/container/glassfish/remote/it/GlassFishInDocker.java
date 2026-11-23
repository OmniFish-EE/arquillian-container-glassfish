/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.remote.it;

import java.time.Duration;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs GlassFish in Docker, from the image in {@code glassfish.image}, with
 * its admin and HTTP ports mapped to random ones, and points the remote
 * container at them. A test class that uses it is skipped where Docker isn't
 * available.
 *
 * <p>Register it before {@code ArquillianExtension}: the remote container
 * reads its configuration when Arquillian starts, so the system properties
 * have to be set by then.
 */
public class GlassFishInDocker implements ExecutionCondition, BeforeAllCallback, AfterAllCallback {

    private static GenericContainer<?> glassfish;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return DockerClientFactory.instance().isDockerAvailable()
                ? ConditionEvaluationResult.enabled("Docker is available")
                : ConditionEvaluationResult.disabled("Docker isn't available");
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        glassfish = 
            new GenericContainer<>(DockerImageName.parse(System.getProperty("glassfish.image")))
                .withExposedPorts(4848, 8080)
                // The admin user admin/admin, and secure admin with a self-signed certificate. The GlassFish 7
                // image has them built in, and ignores this password, which it already has. The GlassFish 8
                // image creates its domain on the first start, with secure admin only when a password is set.
                // Without secure admin, GlassFish refuses admin requests from outside the container.
                .withEnv("AS_ADMIN_PASSWORD", "admin")
                .waitingFor(
                    Wait.forHttps("/management/domain").forPort(4848).allowInsecure()
                        .withBasicCredentials("admin", "admin")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(5)));
        
        glassfish.start();
        
        // Configuration to be picked up by the GlassFish Remote Arquillian Container: 

        System.setProperty("glassfish.adminHost", glassfish.getHost());
        System.setProperty("glassfish.adminPort", String.valueOf(glassfish.getMappedPort(4848)));
        System.setProperty("glassfish.adminHttps", "true");
        System.setProperty("glassfish.ignoreCertificates", "true");
        System.setProperty("glassfish.authorisation", "true");
        System.setProperty("glassfish.adminUser", "admin");
        System.setProperty("glassfish.adminPassword", "admin");
        
        // The core of this test:
        //
        // GlassFish reports http port 8080, which only exists inside the container.
        // By setting glassfish.httpPort to the port Docker mapped 8080 to
        // ee.omnifish.arquillian.container.glassfish.remote.GlassFishDeployableContainer can pick this mapped port
        // up and return it to Arquillian core, which then uses it for the injectable ArquillianResource URL.
        
        System.setProperty("glassfish.httpPort", String.valueOf(glassfish.getMappedPort(8080)));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (glassfish != null) {
            glassfish.stop();
            glassfish = null;
        }
    }
}
