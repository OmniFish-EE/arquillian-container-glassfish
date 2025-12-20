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
// Portions Copyright [2023, 2025] [OmniFish and/or its affiliates]
// Portions Copyright [2021, 2022] [OmniFaces and/or its affiliates]
package ee.omnifish.arquillian.container.glassfish.managed;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import org.jboss.arquillian.container.spi.ConfigurationException;

import ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration;

import static ee.omnifish.arquillian.container.glassfish.clientutils.GlassFishClient.ADMINSERVER;
import static org.jboss.arquillian.container.spi.client.deployment.Validate.configurationDirectoryExists;
import static org.jboss.arquillian.container.spi.client.deployment.Validate.notNull;

/**
 * Configuration for Managed GlassFish containers.
 *
 * @author <a href="http://community.jboss.org/people/dan.j.allen">Dan Allen</a>
 * @author <a href="http://community.jboss.org/people/LightGuard">Jason Porter</a>
 * @author Vineet Reynolds
 */
public class GlassFishManagedContainerConfiguration extends CommonGlassFishConfiguration {

    private static final String GLASSFISH_HOME_PROPERTY = "glassfish.home";

    private String glassFishHome = System.getProperty(GLASSFISH_HOME_PROPERTY);

    private boolean outputToConsole = Boolean.valueOf(System.getProperty("glassfish.outputToConsole", "true"));
    private boolean allowConnectingToRunningServer = Boolean.valueOf(System.getProperty("glassfish.allowConnectingToRunningServer", "false"));
    private boolean keepServerRunning = Boolean.valueOf(System.getProperty("glassfish.keepServerRunning", "false"));
    private boolean keepDeployment = Boolean.valueOf(System.getProperty("glassfish.keepDeployment", "false"));
    private boolean enableDerby = Boolean.valueOf(System.getProperty("glassfish.enableDerby", "false"));
    private String derbyDatabaseName = System.getProperty("glassfish.derbyDatabaseName");
    private String derbySQLFile = System.getProperty("glassfish.derbySQLFile");
    private String derbyUser = System.getProperty("glassfish.derbyUser");
    private String derbyPasswordFile = System.getProperty("glassfish.derbyPasswordFile");
    private String maxHeapSize = System.getProperty("glassfish.maxHeapSize");
    private String enableAssertions = System.getProperty("glassfish.enableAssertions");
    private int httpPort = Integer.valueOf(System.getProperty("glassfish.httpPort", "8080"));
    private int httpsPort = Integer.valueOf(System.getProperty("glassfish.httpsPort", "8181"));

    private Path asadmin;

    public String getGlassFishHome() {
        return glassFishHome;
    }

    /**
     * This setter is used to set value from arquillian.xml
     *
     * @param glassFishHome The local GlassFish installation directory
     */
    public void setGlassFishHome(String glassFishHome) {
        this.glassFishHome = glassFishHome;
    }

    /**
     * @return path to the asadmin command
     */
    public Path getAsadmin() {
        return asadmin;
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

    /**
     * @return the derbyDatabaseName
     */
    public String getDerbyDatabaseName() {
        return derbyDatabaseName;
    }

    /**
     * @param derbyDatabaseName the derbyDatabaseName to set
     */
    public void setDerbyDatabaseName(String derbyDatabaseName) {
        this.derbyDatabaseName = derbyDatabaseName;
    }

    /**
     * @return the derbySQLFile
     */
    public String getDerbySQLFile() {
        return derbySQLFile;
    }

    /**
     * @param derbySQLFile the derbySQLFile to set
     */
    public void setDerbySQLFile(String derbySQLFile) {
        this.derbySQLFile = derbySQLFile;
    }

    /**
     * @return the derbyUser
     */
    public String getDerbyUser() {
        return derbyUser;
    }

    /**
     * @param derbyUser the derbyUser to set
     */
    public void setDerbyUser(String derbyUser) {
        this.derbyUser = derbyUser;
    }

    /**
     * @return the derbyPasswordFile
     */
    public String getDerbyPasswordFile() {
        return derbyPasswordFile;
    }

    /**
     * @param derbyPasswordFile the derbyPasswordFile to set
     */
    public void setDerbyPasswordFile(String derbyPasswordFile) {
        this.derbyPasswordFile = derbyPasswordFile;
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
        notNull(getGlassFishHome(), String.format(
            "The arquillian.xml property glassfishHome must be specified or the %s system property must be set",
            GLASSFISH_HOME_PROPERTY));
        configurationDirectoryExists(getGlassFishHome() + "/glassfish", getGlassFishHome() + " is not a valid GlassFish installation");

        if (getDomain() != null) {
            configurationDirectoryExists(getGlassFishHome() + "/glassfish/domains/" + getDomain(), "Invalid domain: " + getDomain());
        }

        asadmin = prepareAsadmin(glassFishHome);
        super.validate();
    }

    private static Path prepareAsadmin(String glassFishHome) {
        String extension = System.getProperty("os.name").toLowerCase().contains("win") ? ".bat" : "";
        Path asadmin = Path.of(glassFishHome).resolve(Path.of("glassfish", "bin", "asadmin" + extension)).toAbsolutePath();
        if (Files.isExecutable(asadmin)) {
            return asadmin;
        }
        try {
            FileStore fileStore = Files.getFileStore(asadmin);
            boolean isPosixSupported = fileStore.supportsFileAttributeView(PosixFileAttributeView.class);
            if (isPosixSupported) {
                Files.setPosixFilePermissions(asadmin, PosixFilePermissions.fromString("rwx------"));
                return asadmin;
            }
            throw new Error("The asadmin file is not executable and the file system does not support"
                + " POSIX file permissions to set it: " + asadmin);
        } catch (IOException e) {
            throw new Error("The asadmin file is not executable and we failed to change it: " + asadmin, e);
        }
    }
}
