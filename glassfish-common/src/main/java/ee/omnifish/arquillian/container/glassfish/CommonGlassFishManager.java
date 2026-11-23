/*
 * Copyright (c) [2017-2021] Payara Foundation and/or its affiliates. All rights reserved.
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
// Portions Copyright [2025, 2026] [OmniFish and/or its affiliates]
// Portions Copyright [2021] [OmniFaces and/or its affiliates]
package ee.omnifish.arquillian.container.glassfish;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;

import ee.omnifish.arquillian.container.glassfish.clientutils.GlassFishClient;
import ee.omnifish.arquillian.container.glassfish.clientutils.GlassFishClientService;
import ee.omnifish.glassfish.admin.rest.DeployOptions;
import ee.omnifish.glassfish.admin.rest.GlassFishClientException;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;

/**
 * A class to aid in deployment and undeployment of archives involving a GlassFish container.
 * This class encapsulates the operations involving the GlassFishClient class.
 * Extracted from the GlassFish 3.1 remote container.
 *
 * @param <C>
 *     A class of type {@link CommonGlassFishConfiguration}
 *
 * @author Vineet Reynolds
 */
public class CommonGlassFishManager<C extends CommonGlassFishConfiguration> {

    private static final Logger LOG = System.getLogger(CommonGlassFishManager.class.getName());

    private static final Lock deployLock;
    private static final boolean prependDeploySequence;

    private final C configuration;
    private final GlassFishClient glassFishClient;
    private static final AtomicInteger deploySequence = new AtomicInteger();
    private final ThreadLocal<Map<String, String>> deploymentNameTL = ThreadLocal.withInitial(HashMap::new);

    static {
        prependDeploySequence = Boolean.getBoolean("ee.omnifish.arquillian.prependDeploySequence");
        boolean lockDeployment = Boolean.getBoolean("ee.omnifish.arquillian.deployLock");
        deployLock = lockDeployment ? new ReentrantLock() : new NoOpLock();
        if (deployLock instanceof ReentrantLock) {
            LOG.log(INFO, "Serializing Deployments and Undeployments (ee.omnifish.arquillian.deployLock)");
        }
    }

    public CommonGlassFishManager(C configuration) {
        // Start up the GlassFishClient service layer
        this(configuration, new GlassFishClientService(configuration));
    }

    /** With {@code glassFishClient} instead of a {@link GlassFishClientService}. Package-private for unit tests. */
    CommonGlassFishManager(C configuration, GlassFishClient glassFishClient) {
        this.configuration = configuration;
        this.glassFishClient = glassFishClient;
    }

    public void start() throws LifecycleException {
        try {
            glassFishClient.startUp();
        } catch (GlassFishClientException e) {
            throw new LifecycleException(e.getMessage(), e);
        }
    }

    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (archive == null) {
            throw new IllegalArgumentException("archive must not be null");
        }

        final String archiveName = archive.getName();
        final String deploymentName = rememberArchive(archive);
        LOG.log(INFO, "Deploying {0} as {1}", archiveName, deploymentName);

        try (InputStream deployment = archive.as(ZipExporter.class).exportAsInputStream()) {
            // Do Deploy the application on the remote GlassFish
            HTTPContext httpContext;
            deployLock.lock();
            try {
                httpContext = glassFishClient.doDeploy(deploymentName, deployment, archiveName,
                        deployOptions(deploymentName));
            } finally {
                deployLock.unlock();
            }
            final ProtocolMetaData protocolMetaData = new ProtocolMetaData();
            protocolMetaData.addContext(httpContext);
            return protocolMetaData;
        } catch (GlassFishClientException | IOException e) {
            // The deployment failed, so there's nothing for undeploy to remove. A failure after the
            // application deployed comes as a DeploymentException, which keeps the archive.
            forgetArchive(archive);
            throw new DeploymentException("Could not deploy " + archiveName + " as " + deploymentName, e);
        }
    }

    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (archive == null) {
            throw new IllegalArgumentException("archive must not be null");
        }

        String deploymentName = forgetArchive(archive);
        if (deploymentName == null) {
            // Its deployment failed, or never happened, so GlassFish would only answer 404.
            LOG.log(DEBUG, "Not undeploying {0}, which isn't deployed", archive.getName());
            return;
        }

        LOG.log(INFO, "Undeploying {0}", deploymentName);

        try {
            deployLock.lock();
            try {
                glassFishClient.doUndeploy(deploymentName);
            } finally {
                deployLock.unlock();
            }
        } catch (GlassFishClientException e) {
            throw new DeploymentException("Could not undeploy " + archive.getName(), e);
        }
    }

    public boolean isDASRunning() {
        return glassFishClient.isDASRunning();
    }

    private DeployOptions deployOptions(String name) {
        // The target is the client's, and the libraries and properties are optional
        DeployOptions options = new DeployOptions()
                .libraries(configuration.getLibraries())
                .properties(configuration.getProperties())
                .availabilityEnabled(configuration.isAvailabilityEnabled());

        // Add the name field, the name is the archive filename without extension
        // We add this conditionally, since when setting it the name can not be set
        // in application.xml anymore.
        if (configuration.isAddDeployName() || prependDeploySequence) {
            options.name(name);
        }

        // Add the type field (optional, the only valid value is "osgi", other values are ommited)
        if ("osgi".equals(configuration.getType())) {
            options.type(configuration.getType());
        }

        return options;
    }


    private String rememberArchive(final Archive<?> archive) {
        final String deploymentName = createDeploymentName(archive.getName());
        deploymentNameTL.get().put(archive.getId(), deploymentName);
        return deploymentName;
    }

    private String forgetArchive(final Archive<?> archive) {
        final Map<String, String> map = deploymentNameTL.get();
        final String deploymentName = map.remove(archive.getId());
        if (map.isEmpty()) {
            deploymentNameTL.remove();
        }
        return deploymentName;
    }

    private String createDeploymentName(String archiveName) {
        String correctedName = archiveName;
        if (correctedName.startsWith("/")) {
            correctedName = correctedName.substring(1);
        }
        if (correctedName.contains(".")) {
            correctedName = correctedName.substring(0, correctedName.lastIndexOf("."));
        }
        if (prependDeploySequence) {
            return String.format("r%d-%s", deploySequence.incrementAndGet(), correctedName);
        }
        return correctedName;
    }
}
