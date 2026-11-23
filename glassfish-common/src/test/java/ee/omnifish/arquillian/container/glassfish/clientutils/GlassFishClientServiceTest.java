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
package ee.omnifish.arquillian.container.glassfish.clientutils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.junit.jupiter.api.Test;

import ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration;
import ee.omnifish.glassfish.admin.rest.AdminConnection;
import ee.omnifish.glassfish.admin.rest.AdminRestClient;
import ee.omnifish.glassfish.admin.rest.DeployOptions;
import ee.omnifish.glassfish.admin.rest.GlassFishClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlassFishClientServiceTest {

    /**
     * Regression: a failed request after the deployment looked like a failed
     * deployment, so the caller skipped the undeploy, and the application
     * stayed deployed.
     */
    @Test
    void aFailureAfterTheDeploymentSaysTheApplicationIsDeployed() {
        StubbedClient requests = new StubbedClient(false);

        DeploymentException e = assertThrows(DeploymentException.class, () -> deploy(requests));

        assertTrue(e.getMessage().contains("app was deployed"), e.getMessage());
        assertEquals(List.of("deploy", "servlets"), requests.calls);
    }

    /** It fails with GlassFish's answer, which tells the caller that nothing is deployed. */
    @Test
    void aRejectedDeploymentIsNoDeploymentException() {
        StubbedClient requests = new StubbedClient(true);

        assertThrows(GlassFishClientException.class, () -> deploy(requests));

        assertEquals(List.of("deploy"), requests.calls);
    }

    private static void deploy(AdminRestClient requests) throws DeploymentException {
        new GlassFishClientService(new CommonGlassFishConfiguration(), requests)
                .doDeploy("app", new ByteArrayInputStream(new byte[0]), "app.war", new DeployOptions());
    }

    /** GlassFish's answers, without HTTP: the deployment succeeds unless rejected, and every lookup fails. */
    private static final class StubbedClient extends AdminRestClient {

        final List<String> calls = new ArrayList<>();
        private final boolean rejectDeployment;

        StubbedClient(boolean rejectDeployment) {
            super(AdminConnection.local(4848));
            this.rejectDeployment = rejectDeployment;
        }

        @Override
        public String deploy(InputStream archive, String fileName, DeployOptions options) {
            calls.add("deploy");
            if (rejectDeployment) {
                throw new GlassFishClientException("While deploying application app --exit_code: FAILURE");
            }

            return "app";
        }

        @Override
        public List<Servlet> servlets(String name) {
            calls.add("servlets");
            throw new GlassFishClientException("While getting /applications/application/app/list-sub-components"
                    + " -- [status: SERVER_ERROR reason: Internal Server Error]");
        }
    }
}
