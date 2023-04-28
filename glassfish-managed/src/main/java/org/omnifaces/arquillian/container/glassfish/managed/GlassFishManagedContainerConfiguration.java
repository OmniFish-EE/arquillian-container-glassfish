/*
 * Copyright (c) 2017 Payara Foundation and/or its affiliates. All rights reserved.
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
// Portions Copyright [2023] [OmniFish and/or its affiliates]
// Portions Copyright [2021,2022] [OmniFaces and/or its affiliates]
package org.omnifaces.arquillian.container.glassfish.managed;

import static org.jboss.arquillian.container.spi.client.deployment.Validate.configurationDirectoryExists;
import static org.jboss.arquillian.container.spi.client.deployment.Validate.notNull;
import static org.omnifaces.arquillian.container.glassfish.clientutils.GlassFishClient.ADMINSERVER;

import java.io.File;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.omnifaces.arquillian.container.glassfish.CommonGlassFishConfiguration;

/**
 * Configuration for Managed GlassFish containers.
 *
 * @author <a href="http://community.jboss.org/people/dan.j.allen">Dan Allen</a>
 * @author <a href="http://community.jboss.org/people/LightGuard">Jason Porter</a>
 * @author Vineet Reynolds
 */
public class GlassFishManagedContainerConfiguration extends CommonGlassFishConfiguration {
    private final String GLASSFISH_HOME_PROPERTY = "glassfish.home";

    private String glassFishHome = System.getProperty(GLASSFISH_HOME_PROPERTY);

    private boolean outputToConsole = Boolean.valueOf(System.getProperty("glassfish.outputToConsole", "true"));
    private boolean allowConnectingToRunningServer = Boolean.valueOf(System.getProperty("glassfish.allowConnectingToRunningServer", "false"));
    private boolean keepServerRunning = Boolean.valueOf(System.getProperty("glassfish.keepServerRunning", "false"));
    private boolean keepDeployment = Boolean.valueOf(System.getProperty("glassfish.keepDeployment", "false"));
    private boolean enableDerby = Boolean.valueOf(System.getProperty("glassfish.enableDerby", "false"));
    private String maxHeapSize = System.getProperty("glassfish.maxHeapSize");
    private String enableAssertions = System.getProperty("glassfish.enableAssertions");
    private int httpPort = Integer.valueOf(System.getProperty("glassfish.httpPort", "8080"));
    private int httpsPort = Integer.valueOf(System.getProperty("glassfish.httpsPort", "8181"));

    public String getGlassFishHome() {
        return glassFishHome;
    }

    /**
     * @param glashFishHome The local GlassFish installation directory
     */
    public void setGlassFishHome(String glashFishHome) {
        this.glassFishHome = glashFishHome;
    }

    public boolean isOutputToConsole() {
        return outputToConsole;
    }

    /**
     * @param outputToConsole Show the output of the admin commands on the console. By default enabled
     */
    public void setOutputToConsole(boolean outputToConsole) {
        this.outputToConsole = outputToConsole;
    }

    public File getAdminCliJar() {
        return new File(getGlassFishHome() + "/glassfish/modules/admin-cli.jar");
    }

    public boolean isAllowConnectingToRunningServer() {
        return allowConnectingToRunningServer;
    }

    /**
     * @param allowConnectingToRunningServer Allow Arquillian to use an already running GlassFish instance.
     */
    public void setAllowConnectingToRunningServer(boolean allowConnectingToRunningServer) {
        this.allowConnectingToRunningServer = allowConnectingToRunningServer;
    }

    /**
     * @return the keepServerRunning
     */
    public boolean isKeepServerRunning() {
        return keepServerRunning;
    }

    /**
     * @param keepServerRunning starts the server if needed, but it keeps it running after tests
     */
    public void setKeepServerRunning(boolean keepServerRunning) {
        this.keepServerRunning = keepServerRunning;
    }

    /**
     * @return the keepDeployment
     */
    public boolean isKeepDeployment() {
        return keepDeployment;
    }

    /**
     * @param keepDeployment the keepDeployment to set
     */
    public void setKeepDeployment(boolean keepDeployment) {
        this.keepDeployment = keepDeployment;
    }

    public boolean isEnableDerby() {
        return enableDerby;
    }

    /**
     * @param enableDerby Flag to start/stop the registered Derby server using standard Derby port
     */
    public void setEnableDerby(boolean enableDerby) {
        this.enableDerby = enableDerby;
    }

    public String getMaxHeapSize() {
        return maxHeapSize;
    }

    public void setMaxHeapSize(String maxHeapSize) {
        this.maxHeapSize = maxHeapSize;
    }

    public String getEnableAssertions() {
        return enableAssertions;
    }

    public void setEnableAssertions(String enableAssertions) {
        this.enableAssertions = enableAssertions;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getHttpsPort() {
        return httpsPort;
    }

    public void setHttpsPort(int httpsPort) {
        this.httpsPort = httpsPort;
    }

    public String getDomainXmlPath() {
        return getGlassFishHome() + "/glassfish/domains/" + (getDomain() == null? "domain1" : getDomain()) + "/config/domain.xml";
    }

    @Override
    public String getTarget() {
        return ADMINSERVER;
    }

    /**
     * Validates if current configuration is valid, that is if all required properties are set and have correct values
     */
    @Override
    public void validate() throws ConfigurationException {
        notNull(getGlassFishHome(),
                String.format("The arquillian.xml property glassfishHome must be specified or " + "the %s system property must be set",
                        GLASSFISH_HOME_PROPERTY));
        configurationDirectoryExists(getGlassFishHome() + "/glassfish", getGlassFishHome() + " is not a valid GlassFish installation");

        if (!getAdminCliJar().isFile()) {
            throw new IllegalArgumentException("Could not locate admin-cli.jar module in GlassFish installation: " + getGlassFishHome());
        }

        if (getDomain() != null) {
            configurationDirectoryExists(getGlassFishHome() + "/glassfish/domains/" + getDomain(), "Invalid domain: " + getDomain());
        }

        super.validate();
    }
}
