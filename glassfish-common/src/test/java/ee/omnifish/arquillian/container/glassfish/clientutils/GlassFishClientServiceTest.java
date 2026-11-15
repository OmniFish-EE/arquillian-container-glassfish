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

import jakarta.ws.rs.ProcessingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.junit.jupiter.api.Test;

import ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration;

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
        StubbedRequests requests = new StubbedRequests(false);

        DeploymentException e = assertThrows(DeploymentException.class,
                () -> client(requests).doDeploy("app", new FormDataMultiPart()));

        assertTrue(e.getMessage().contains("app was deployed"), e.getMessage());
        assertEquals(List.of("POST", "GET"), requests.methods);
    }

    /** It fails with GlassFish's answer, which tells the caller that nothing is deployed. */
    @Test
    void aRejectedDeploymentIsNoDeploymentException() {
        StubbedRequests requests = new StubbedRequests(true);

        assertThrows(ProcessingException.class, () -> client(requests).doDeploy("app", new FormDataMultiPart()));

        assertEquals(List.of("POST"), requests.methods);
    }

    private static GlassFishClientService client(GlassFishClientUtil requests) {
        return new GlassFishClientService(new CommonGlassFishConfiguration()) {
            @Override
            GlassFishClientUtil getClientUtil() {
                return requests;
            }
        };
    }

    /** GlassFish's answers, without HTTP: the deploy request succeeds unless rejected, and every GET fails. */
    private static final class StubbedRequests extends GlassFishClientUtil {

        final List<String> methods = new ArrayList<>();
        private final boolean rejectDeployment;

        StubbedRequests(boolean rejectDeployment) {
            super(new CommonGlassFishConfiguration(), "http://localhost:4848/management/domain");
            this.rejectDeployment = rejectDeployment;
        }

        @Override
        public Map<String, Object> POSTMultiPartRequest(String operation, String additionalResourceUrl,
                                                        FormDataMultiPart form) {
            methods.add("POST");
            if (rejectDeployment) {
                throw new ProcessingException("While " + operation + " --exit_code: FAILURE");
            }

            return Map.of("exit_code", "SUCCESS");
        }

        @Override
        public Map<String, Object> GETRequest(String additionalResourceUrl) {
            methods.add("GET");
            throw new ProcessingException("While getting " + additionalResourceUrl
                    + " -- [status: SERVER_ERROR reason: Internal Server Error]");
        }
    }
}
