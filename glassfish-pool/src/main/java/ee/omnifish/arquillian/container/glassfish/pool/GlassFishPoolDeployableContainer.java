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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
import ee.omnifish.glassfish.admin.AsAdmin;
import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;

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
 *
 * <p>The same goes for the {@link SlotSetup}, the settings and commands every
 * slot gets: {@code up} forwards them, so slots created here, when the pool
 * grows or a dead slot is recycled, get the same setup as those {@code up}
 * started.
 */
public class GlassFishPoolDeployableContainer implements DeployableContainer<GlassFishPoolConfiguration> {

    private static final Logger LOG = Logger.getLogger(GlassFishPoolDeployableContainer.class.getName());

    private static final Duration POSTBOOT_TIMEOUT = Duration.ofSeconds(60);

    /** Server settings the managed Arquillian Container reads, which pool slots get from the pool plugin. */
    private static final String MAX_HEAP_SIZE = "glassfish.maxHeapSize";
    private static final String ENABLE_ASSERTIONS = "glassfish.enableAssertions";

    private static final AtomicBoolean POST_BOOT_HINT_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SETTINGS_CHECKED = new AtomicBoolean();

    private GlassFishPoolConfiguration configuration;
    private PoolConfig poolConfig;
    private CommonGlassFishManager<GlassFishPoolConfiguration> manager;
    private LeaseHandle handle;

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
        // Source, port layout and slot setup come from system properties
        // (forwarded by surefire/failsafe) — see class Javadoc for the rationale.
        poolConfig = PoolConfig.forTestJvm(poolDir);
        SlotLeaser leaser = new SlotLeaser(poolConfig, configuration.getLeaseTimeoutSeconds());
        String slotGroup = configuration.getSlotGroup();
        boolean shared = slotGroup != null && !slotGroup.isEmpty();
        try {
            // Shared: sibling containers in this JVM (same group + poolDir) reuse
            // one slot — one GlassFish, many container views. See SharedSlotRegistry.
            handle = shared
                    ? SharedSlotRegistry.acquire(new SharedSlotRegistry.Key(slotGroup, poolDir), leaser::lease)
                    : new LeaseHandle.Direct(leaser.lease());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LifecycleException("Could not lease a pool slot", e);
        } catch (CommandFailedException e) {
            throw new LifecycleException("Could not set up a pool slot: " + e.getMessage(), e);
        }
        try {
            SlotPorts ports = handle.ports();
            configuration.setAdminHost("localhost");
            configuration.setAdminPort(ports.adminPort());
            configuration.setHttpPort(ports.httpPort());
            configuration.setHttpsPort(ports.httpsPort());
            configuration.setGlassFishHome(ports.glassFishHome());

            LOG.info("Leased pool slot " + handle.slotIndex()
                    + " (adminPort=" + ports.adminPort() + ")"
                    + (shared ? " [shared group: " + slotGroup + "]" : ""));

            manager = new CommonGlassFishManager<>(configuration);
            manager.start();

            warnAboutMissingSettings(ports);
            runPostBootCommands(ports);
        } catch (RuntimeException | LifecycleException e) {
            // Arquillian does not call stop() for a container whose start()
            // threw, so roll back the lease here — otherwise a shared handle
            // leaks a registry refcount (and the slot lock) for the JVM lifetime.
            releaseHandle();
            throw e;
        }
    }

    /** Release the held lease and close the slot iff this was its last holder. */
    private void releaseHandle() {
        SlotLease toFinalize = handle.release();
        if (toFinalize != null) {
            toFinalize.close();
        }
        handle = null;
        manager = null;
    }

    /**
     * Run {@code glassfish.postBootCommands}, which the pool keeps running on
     * every lease, as it always has. Logs once per JVM where the commands
     * belong now.
     */
    private void runPostBootCommands(SlotPorts ports) {
        List<String> commands = configuration.getPostBootCommandList();
        if (!commands.isEmpty() && POST_BOOT_HINT_LOGGED.compareAndSet(false, true)) {
            LOG.info("glassfish.postBootCommands runs on every lease of a pool slot. To run commands once per "
                    + "slot, or after every slot start, move them to the pool plugin's <postBootCommands> or "
                    + "<postStartCommands>.");
        }
        // --port targets the leased slot's DAS: post-boot subcommands like
        // create-file-user connect to a running DAS, which sits on a per-slot
        // offset port rather than the default 4848.
        AsAdmin asadmin = new AsAdmin(Paths.get(ports.glassFishHome()), ports.adminPort());
        PostBootCommands.execute(commands,
                (command, args) -> asadmin.run(POSTBOOT_TIMEOUT, command, args.toArray(String[]::new)));
    }

    /**
     * Warn once per JVM when the test JVM sets server settings, the way the
     * managed Arquillian Container takes them, that the leased slot's
     * {@code domain.xml} lacks: pool slots get them from the pool plugin.
     */
    private void warnAboutMissingSettings(SlotPorts ports) {
        List<String> wanted = new ArrayList<>();
        for (String property : configuration.getSystemProperyList()) {
            wanted.add("-D" + property);
        }
        wanted.addAll(DomainXmlEditor.heapAndAssertionOptions(
                System.getProperty(MAX_HEAP_SIZE), System.getProperty(ENABLE_ASSERTIONS)).values());
        if (wanted.isEmpty() || !SETTINGS_CHECKED.compareAndSet(false, true)) {
            return;
        }

        Path domainXml = PoolProvisioner.domainXml(Paths.get(ports.glassFishHome()));
        try {
            wanted.removeAll(DomainXmlEditor.jvmOptions(domainXml));
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not read " + domainXml, e);
            return;
        }
        if (!wanted.isEmpty()) {
            LOG.warning("Pool slot " + handle.slotIndex() + " lacks the server settings " + wanted
                    + " that this test JVM sets through glassfish.systemProperties, " + MAX_HEAP_SIZE + " or "
                    + ENABLE_ASSERTIONS + ". Pool slots get them from the pool plugin's <systemProperties>, "
                    + "<maxHeapSize> and <enableAssertions>.");
        }
    }

    @Override
    public void stop() throws LifecycleException {
        if (handle == null) {
            return;
        }
        // For a shared slot only the LAST holder finalizes it; earlier holders
        // get null here and leave the slot up for their siblings.
        SlotLease toFinalize = handle.release();
        if (toFinalize != null) {
            // Restart while the slot lock is still held: a free lock plus a port
            // momentarily down would let a concurrent leaser classify the slot as
            // dead and recycle it (wiping the GF install we're restarting).
            if (configuration.isRestartOnRelease()) {
                restartLeasedDomain(toFinalize);
            }
            try {
                toFinalize.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Releasing slot lease " + toFinalize.slotIndex() + " failed", e);
            }
        }
        handle = null;
        manager = null;
    }

    /**
     * Restart the slot and run its post-start commands. A failure leaves the
     * slot stopped and without {@code ports.properties}, so it is re-created
     * (from {@code gf.pool.source}, if forwarded) before it is leased again.
     * The test class has already finished, so its result stands.
     */
    private void restartLeasedDomain(SlotLease lease) {
        try {
            new PoolProvisioner(poolConfig).restartSlot(lease.slotIndex(), lease.ports());
            LOG.info("Restarted GlassFish on slot " + lease.slotIndex()
                    + " (adminPort=" + lease.ports().adminPort() + ")");
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Restarting slot " + lease.slotIndex()
                    + " failed; releasing lease anyway — the slot will be re-created before its next lease",
                    e);
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
}
