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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import ee.omnifish.arquillian.container.glassfish.CommonGlassFishManager;

/**
 * Test-JVM-side container: leases a pre-started slot at {@link #setup} time,
 * writes the leased ports into the inherited config, then deploys via the
 * standard {@link CommonGlassFishManager} (REST against the slot's DAS).
 *
 * <p>Replaces the {@code -javaagent} + system-property side-channel approach
 * the Faces TCK used: leasing happens through the Arquillian container
 * lifecycle, so no premain hook and no surefire {@code argLine} plumbing.
 *
 * <h2>Why {@link #start} reads pool layout from system properties</h2>
 * Pool slots run on different ports (admin/http/https are offset per slot via
 * {@code adminBase} + {@code portStride}), and the source install path is a
 * build-time concern, not a per-test-class concern. These values must be set
 * once for the build and inherited by every test JVM — arquillian.xml is the
 * wrong layer because it would force every consumer to repeat the same values
 * in every container element. Surefire/failsafe forwards them as system
 * properties ({@link PoolConfig#SYS_SOURCE}, {@link PoolConfig#SYS_ADMIN_BASE},
 * {@link PoolConfig#SYS_PORT_STRIDE}); arquillian.xml only carries the
 * test-JVM concerns ({@code poolDir}, {@code leaseTimeoutSeconds}). If the
 * sysprops are absent the leaser still works against an existing pool — it
 * just can't grow new slots, which is fine for fixed-size pre-provisioned
 * pools and only matters when {@code -TN} > {@code pool.size}.
 */
public class GlassFishPoolDeployableContainer implements DeployableContainer<GlassFishPoolConfiguration> {

    private static final Logger LOG = Logger.getLogger(GlassFishPoolDeployableContainer.class.getName());

    private GlassFishPoolConfiguration configuration;
    private CommonGlassFishManager<GlassFishPoolConfiguration> manager;
    private SlotLease lease;

    @Override
    public Class<GlassFishPoolConfiguration> getConfigurationClass() {
        return GlassFishPoolConfiguration.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 5.0");
    }

    @Override
    public void setup(GlassFishPoolConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        this.configuration = configuration;
    }

    @Override
    public void start() throws LifecycleException {
        // Lease at start() rather than setup() because Arquillian calls
        // setup() on every container in arquillian.xml during context init,
        // even those not selected; lease only when the container is being
        // started for actual use.
        Path poolDir = Paths.get(configuration.getPoolDir());
        // Source / port layout come from system properties (forwarded by
        // surefire/failsafe) — see class Javadoc for the rationale.
        PoolConfig poolConfig = new PoolConfig(
                poolDir,
                pathOrNull(System.getProperty(PoolConfig.SYS_SOURCE)),
                0,
                Integer.getInteger(PoolConfig.SYS_ADMIN_BASE, PoolConfig.DEFAULT_ADMIN_BASE),
                Integer.getInteger(PoolConfig.SYS_PORT_STRIDE, PoolConfig.DEFAULT_PORT_STRIDE));
        SlotLeaser leaser = new SlotLeaser(poolConfig, configuration.getLeaseTimeoutSeconds());
        try {
            lease = leaser.lease();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LifecycleException("Could not lease a pool slot", e);
        }
        SlotPorts ports = lease.ports();
        configuration.setAdminHost("localhost");
        configuration.setAdminPort(ports.adminPort());
        configuration.setHttpPort(ports.httpPort());
        configuration.setHttpsPort(ports.httpsPort());
        configuration.setGlassFishHome(ports.glassFishHome());

        LOG.info("Leased pool slot " + lease.slotIndex()
                + " (adminPort=" + ports.adminPort() + ")");

        manager = new CommonGlassFishManager<>(configuration);
        manager.start();
    }

    @Override
    public void stop() throws LifecycleException {
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Releasing slot lease " + lease.slotIndex() + " failed", e);
        } finally {
            lease = null;
            manager = null;
        }
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        return manager.deploy(archive);
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        manager.undeploy(archive);
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("Not implemented");
    }

    private static Path pathOrNull(String s) {
        return (s == null || s.isEmpty()) ? null : Paths.get(s);
    }
}
