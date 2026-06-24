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
package ee.omnifish.arquillian.container.glassfish.pool;

import org.jboss.arquillian.container.spi.ConfigurationException;

import ee.omnifish.arquillian.container.glassfish.CommonGlassFishConfiguration;

/**
 * Test-JVM-side configuration for the pool container. Extends the common
 * GlassFish config (REST deploy connection: adminHost/adminPort/auth/etc.)
 * and adds the few fields the pool needs — including http/https ports and
 * glassFishHome, which a leased slot will fill in.
 *
 * <p>The pool-specific fields:
 * <ul>
 *   <li>{@code poolDir} — where the provisioner laid out the slots. Must
 *       match what the build passed to {@code mvn glassfish-pool:up}.</li>
 *   <li>{@code leaseTimeoutSeconds} — how long to wait for an idle slot
 *       before failing the test JVM.</li>
 *   <li>{@code restartOnRelease} — when {@code true}, the GlassFish domain
 *       backing the leased slot is restarted (via {@code asadmin restart-domain})
 *       on container stop, before the slot lock is released. Use this when
 *       tests leak JVM-scoped state (datasource pools, classloader pins,
 *       ThreadLocals, EclipseLink session caches) that undeploy alone does
 *       not clear. Trades suite duration for inter-test isolation — defaults
 *       to {@code false}. Strongly recommended to also forward
 *       {@code gf.pool.source} to the test JVM when enabled, so the lease
 *       loop can re-provision a slot whose restart fails.</li>
 * </ul>
 *
 * <p>{@code httpPort}/{@code httpsPort}/{@code glassFishHome} are populated
 * at lease time from the slot's published {@link SlotPorts}; values set here
 * (or inherited from system properties) are overwritten.
 *
 * <p>Doesn't extend {@code GlassFishManagedContainerConfiguration} on
 * purpose: the managed module's {@code LoadableExtension} would also be
 * picked up by Arquillian's SPI scan if the managed jar were on the test
 * classpath, registering a second {@code DeployableContainer} and confusing
 * container selection. Keeping the pool independent of managed avoids that.
 */
public class GlassFishPoolConfiguration extends CommonGlassFishConfiguration {

    private static final long DEFAULT_LEASE_TIMEOUT_SECONDS = 600L;

    private String poolDir = System.getProperty(PoolConfig.SYS_POOL_DIR);
    private long leaseTimeoutSeconds = Long.parseLong(
            System.getProperty("gf.pool.leaseTimeoutSeconds", String.valueOf(DEFAULT_LEASE_TIMEOUT_SECONDS)));
    private boolean restartOnRelease = Boolean.getBoolean("gf.pool.restartOnRelease");

    private int httpPort = Integer.parseInt(System.getProperty("glassfish.httpPort", "8080"));
    private int httpsPort = Integer.parseInt(System.getProperty("glassfish.httpsPort", "8181"));
    private String glassFishHome = System.getProperty("glassfish.home");

    public String getPoolDir() {
        return poolDir;
    }

    public void setPoolDir(String poolDir) {
        this.poolDir = poolDir;
    }

    public long getLeaseTimeoutSeconds() {
        return leaseTimeoutSeconds;
    }

    public void setLeaseTimeoutSeconds(long leaseTimeoutSeconds) {
        this.leaseTimeoutSeconds = leaseTimeoutSeconds;
    }

    public boolean isRestartOnRelease() {
        return restartOnRelease;
    }

    public void setRestartOnRelease(boolean restartOnRelease) {
        this.restartOnRelease = restartOnRelease;
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

    public String getGlassFishHome() {
        return glassFishHome;
    }

    public void setGlassFishHome(String glassFishHome) {
        this.glassFishHome = glassFishHome;
    }

    @Override
    public void validate() throws ConfigurationException {
        // Parses the inherited postBootCommands/systemProperties into their
        // lists; without this the pool would silently ignore both.
        super.validate();

        if (poolDir == null || poolDir.isEmpty()) {
            throw new ConfigurationException(
                    "poolDir is required (set <property name=\"poolDir\"> in arquillian.xml or "
                    + "system property " + PoolConfig.SYS_POOL_DIR + ")");
        }
    }
}
