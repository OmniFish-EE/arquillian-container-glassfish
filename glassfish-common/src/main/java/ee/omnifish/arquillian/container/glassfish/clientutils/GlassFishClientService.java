/*
 * Copyright (c) 2017-2021 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Portions Copyright [2024, 2026] [OmniFish and/or its affiliates]
// Portions Copyright [2021] [OmniFaces and/or its affiliates]
package ee.omnifish.arquillian.container.glassfish.clientutils;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;

import ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration;
import ee.omnifish.glassfish.admin.rest.AdminConnection;
import ee.omnifish.glassfish.admin.rest.AdminRestClient;
import ee.omnifish.glassfish.admin.rest.DeployOptions;
import ee.omnifish.glassfish.admin.rest.GlassFishClientException;
import ee.omnifish.glassfish.admin.rest.NodeAddress;

import static java.util.logging.Level.INFO;

/**
 * The {@link GlassFishClient} of the Arquillian Containers: an
 * {@link AdminRestClient} for the configured DAS, which also builds
 * Arquillian's {@link HTTPContext} of what it deploys.
 *
 * @author Z.Paulovics
 */
public class GlassFishClientService implements GlassFishClient {

    private static final Logger log = Logger.getLogger(GlassFishClientService.class.getName());

    private static final String GLASSFISH_HOME_PROPERTY = "com.sun.aas.productRoot";

    private final CommonGlassFishConfiguration configuration;
    private final AdminRestClient client;
    private final Map<String, String> deploymentAliases = new ConcurrentHashMap<>();

    private NodeAddress nodeAddress;

    public GlassFishClientService(CommonGlassFishConfiguration configuration) {
        this(configuration, new AdminRestClient(connection(configuration)));
    }

    /** With {@code client} instead of one for the configured DAS. Package-private for unit tests. */
    GlassFishClientService(CommonGlassFishConfiguration configuration, AdminRestClient client) {
        this.configuration = configuration;
        this.client = client;
    }

    private static AdminConnection connection(CommonGlassFishConfiguration configuration) {
        return new AdminConnection(configuration.getAdminHost(), configuration.getAdminPort(),
                configuration.isAdminHttps(), configuration.isAuthorisation() ? configuration.getAdminUser() : null,
                configuration.getAdminPassword(), configuration.isIgnoreCertificates(), configuration.getTarget());
    }

    @Override
    public void startUp() throws GlassFishClientException {
        nodeAddress = client.startUp();

        // Sets the location of the installed GlassFish server, if not already specified and
        // if available.
        if (System.getProperty(GLASSFISH_HOME_PROPERTY) == null) {
            String glassFishHome = client.serverSystemProperties().get(GLASSFISH_HOME_PROPERTY);
            if (glassFishHome != null) {
                System.setProperty(GLASSFISH_HOME_PROPERTY, glassFishHome);
            }
        }
    }

    @Override
    public HTTPContext doDeploy(String name, InputStream archive, String fileName, DeployOptions options)
            throws DeploymentException {
        // Deploy the application on the GlassFish server
        String registeredName = registerDeployedName(name, client.deploy(archive, fileName, options));
        try {
            return httpContext(registeredName);
        } catch (GlassFishClientException e) {
            // GlassFish accepted the deployment, so the application stays deployed until it's undeployed.
            throw new DeploymentException(name + " was deployed, but reading what it contains failed", e);
        }
    }

    /** The HTTPContext of the deployed application {@code name}, with its servlets, read with further requests. */
    private HTTPContext httpContext(String name) {
        List<AdminRestClient.Servlet> servlets = client.servlets(name);

        // Build up the HTTPContext object using the nodeAddress information
        HTTPContext httpContext = null;
        if (configuration.isHttpsPortAsDefault()) {
            httpContext = new HTTPContext(nodeAddress.getHost(), nodeAddress.getHttpsPort(), true);
        } else {
            httpContext = new HTTPContext(nodeAddress.getHost(), nodeAddress.getHttpPort(), false);
        }

        for (AdminRestClient.Servlet servlet : servlets) {
            httpContext.add(new Servlet(servlet.name(), servlet.contextRoot()));
        }

        return httpContext;
    }

    private String registerDeployedName(String name, String deployedName) {
        if (deployedName == null || deployedName.equals(name)) {
            return name;
        }

        log.log(INFO, "Deployment {0} resulted in application with different name {1}",
                new Object[]{ name, deployedName });

        deploymentAliases.put(name, deployedName);

        return deployedName;

    }

    private String unregisterDeployedName(String name) {
        String alias = deploymentAliases.remove(name);
        return alias != null ? alias : name;
    }

    @Override
    public void doUndeploy(String name) {
        String deploymentName = unregisterDeployedName(name);
        log.log(INFO, "Undeploying {0} as {1}", new Object[] { name, deploymentName });
        client.undeploy(deploymentName);
    }

    @Override
    public boolean isDASRunning() {
        return client.isDASRunning();
    }
}
