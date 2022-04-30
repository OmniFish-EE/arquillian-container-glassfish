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
// Portions Copyright [2021] [OmniFaces and/or its affiliates]
package org.omnifaces.arquillian.container.glassfish;

import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static java.util.logging.Level.FINE;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.glassfish.jersey.server.ContainerException;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.omnifaces.arquillian.container.glassfish.clientutils.GlassFishClient;
import org.omnifaces.arquillian.container.glassfish.clientutils.GlassFishClientException;
import org.omnifaces.arquillian.container.glassfish.clientutils.GlassFishClientService;

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

    private static final Logger log = Logger.getLogger(CommonGlassFishManager.class.getName());

    private static final String DELETE_OPERATION = "__deleteoperation";
    private static final Lock deployLock;
    private static final boolean prependDeploySequence;

    private final C configuration;
    private final GlassFishClient glassFishClient;
    private static final AtomicInteger deploySequence = new AtomicInteger();

    static {
        prependDeploySequence = Boolean.getBoolean("org.omnifaces.arquillian.prependDeploySequence");
        deployLock = Boolean.getBoolean("org.omnifaces.arquillian.deployLock") ? new ReentrantLock() : new NoOpLock();
        if (deployLock instanceof ReentrantLock) {
            log.info("Serializing Deployments and Undeployments (org.omnifaces.arquillian.deployLock)");
        }
    }

    public CommonGlassFishManager(C configuration) {
        this.configuration = configuration;

        // Start up the GlassFishClient service layer
        glassFishClient = new GlassFishClientService(configuration);
    }

    public void start() throws LifecycleException {
        try {
            glassFishClient.startUp();
        } catch (GlassFishClientException e) {
            log.severe(e.getMessage());
            throw new LifecycleException(e.getMessage());
        }
    }

    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (archive == null) {
            throw new IllegalArgumentException("archive must not be null");
        }

        final String archiveName = archive.getName();
        final String deploymentName = createDeploymentName(prependDeploySequence ?
                String.format("r%d-%s", deploySequence.incrementAndGet(), archiveName) : archiveName);

        log.log(FINE, "Deploying {0}", new Object[] { deploymentName });

        final ProtocolMetaData protocolMetaData = new ProtocolMetaData();

        try (InputStream deployment = archive.as(ZipExporter.class).exportAsInputStream()) {
            // Build up the POST form to send to GlassFish
            final FormDataMultiPart form = new FormDataMultiPart();
            form.bodyPart(new StreamDataBodyPart("id", deployment, archiveName));

            addDeployFormFields(deploymentName, form);

            // Do Deploy the application on the remote GlassFish
            HTTPContext httpContext;
            deployLock.lock();
            try {
                httpContext = glassFishClient.doDeploy(createDeploymentName(archiveName), form);
            } finally {
                deployLock.unlock();
            }
            protocolMetaData.addContext(httpContext);
        } catch (GlassFishClientException | IOException | ContainerException e) {
            throw new DeploymentException("Could not deploy " + archiveName, e);
        }

        return protocolMetaData;
    }

    public void undeploy(Archive<?> archive) throws DeploymentException {

        if (archive == null) {
            throw new IllegalArgumentException("archive must not be null");
        }

        String deploymentName = createDeploymentName(archive.getName());

        try {
            // Build up the POST form to send to GlassFish
            FormDataMultiPart form = new FormDataMultiPart();
            form.field("target", configuration.getTarget(), TEXT_PLAIN_TYPE);
            form.field("operation", DELETE_OPERATION, TEXT_PLAIN_TYPE);

            deployLock.lock();
            try {
                glassFishClient.doUndeploy(deploymentName, form);
            } finally {
                deployLock.unlock();
            }
        } catch (GlassFishClientException | ContainerException e) {
            throw new DeploymentException("Could not undeploy " + archive.getName(), e);
        }
    }

    public boolean isDASRunning() {
        return glassFishClient.isDASRunning();
    }

    private String createDeploymentName(String archiveName) {
        String correctedName = archiveName;
        if (correctedName.startsWith("/")) {
            correctedName = correctedName.substring(1);
        }

        if (correctedName.contains(".")) {
            correctedName = correctedName.substring(0, correctedName.lastIndexOf("."));
        }

        return correctedName;
    }

    private void addDeployFormFields(String name, FormDataMultiPart deployform) {

        // Add the name field, the name is the archive filename without extension
        deployform.field("name", name, TEXT_PLAIN_TYPE);

        // Add the target field (the default is "server" - Admin Server)
        deployform.field("target", configuration.getTarget(), TEXT_PLAIN_TYPE);

        // Add the libraries field (optional)
        if (configuration.getLibraries() != null) {
            deployform.field("libraries", configuration.getLibraries(), TEXT_PLAIN_TYPE);
        }

        // Add the properties field (optional)
        if (configuration.getProperties() != null) {
            deployform.field("properties", configuration.getProperties(), TEXT_PLAIN_TYPE);
        }

        // Add the type field (optional, the only valid value is "osgi", other values are ommited)
        if (configuration.getType() != null && "osgi".equals(configuration.getType())) {
            deployform.field("type", configuration.getType(), TEXT_PLAIN_TYPE);
        }
    }
}
