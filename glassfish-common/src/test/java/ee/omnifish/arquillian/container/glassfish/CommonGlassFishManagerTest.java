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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;

import ee.omnifish.arquillian.container.glassfish.clientutils.GlassFishClient;
import ee.omnifish.arquillian.container.glassfish.clientutils.GlassFishClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommonGlassFishManagerTest {

    /** GlassFish would answer 404 to the undeploy, which the client logs as a warning. */
    @Test
    void aFailedDeploymentIsNotUndeployed() throws Exception {
        RecordingClient client = new RecordingClient(new GlassFishClientException("deployment failed"));
        CommonGlassFishManager<?> manager = new CommonGlassFishManager<>(new CommonGlassFishConfiguration(), client);
        WebArchive archive = archive("broken.war");
        assertThrows(DeploymentException.class, () -> manager.deploy(archive));

        manager.undeploy(archive);

        assertEquals(List.of("deploy broken"), client.calls);
    }

    @Test
    void aDeployedArchiveIsUndeployed() throws Exception {
        RecordingClient client = new RecordingClient(null);
        CommonGlassFishManager<?> manager = new CommonGlassFishManager<>(new CommonGlassFishConfiguration(), client);
        WebArchive archive = archive("working.war");
        manager.deploy(archive);

        manager.undeploy(archive);

        assertEquals(List.of("deploy working", "undeploy working"), client.calls);
    }

    /** A war with one file: ShrinkWrap doesn't export an empty one. */
    private static WebArchive archive(String name) {
        return ShrinkWrap.create(WebArchive.class, name).add(EmptyAsset.INSTANCE, "index.html");
    }

    /** Records the deploys and undeploys asked of it, and fails deploys with {@code deployFailure}, if set. */
    private static final class RecordingClient implements GlassFishClient {

        final List<String> calls = new ArrayList<>();
        private final RuntimeException deployFailure;

        RecordingClient(RuntimeException deployFailure) {
            this.deployFailure = deployFailure;
        }

        @Override
        public void startUp() {
        }

        @Override
        public HTTPContext doDeploy(String name, FormDataMultiPart form) {
            calls.add("deploy " + name);
            if (deployFailure != null) {
                throw deployFailure;
            }

            return new HTTPContext("localhost", 8080);
        }

        @Override
        public Map<String, Object> doUndeploy(String name, FormDataMultiPart form) {
            calls.add("undeploy " + name);
            return Map.of();
        }

        @Override
        public boolean isDASRunning() {
            return true;
        }
    }
}
