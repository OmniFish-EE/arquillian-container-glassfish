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
import java.net.URISyntaxException;
import java.nio.file.Files;
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
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.ModuleOptions;
import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands;
import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;
import ee.omnifish.glassfish.admin.ServerLogTailer;

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
 *
 * <p>When the pool runs GlassFish Embedded, as {@code up} describes it in the
 * pool directory ({@link EmbeddedPool}), the leased slot is a block of ports,
 * and each deployment starts a child of its own on them, with the archive;
 * see {@link EmbeddedSlotDeployer}. There's no admin connection, no server
 * log to follow, and nothing to restart.
 *
 * <p>With {@code <embeddedMode>in-process</embeddedMode>}, the test JVM hosts
 * GlassFish Embedded itself, on the leased slot's ports, from {@code start()}
 * to {@code stop()}, and deploys to it while it runs; see {@link HostedSlot}.
 * GlassFish has to be on the test JVM's class path, after the project's
 * dependencies, and the JVM needs GlassFish's module options: the pool
 * plugin's extension adds both to Failsafe.
 */
public class GlassFishPoolDeployableContainer implements DeployableContainer<GlassFishPoolConfiguration> {

    private static final Logger LOG = Logger.getLogger(GlassFishPoolDeployableContainer.class.getName());

    private static final Duration POSTBOOT_TIMEOUT = Duration.ofSeconds(60);

    /** Server settings the managed Arquillian Container reads, which pool slots get from the pool plugin. */
    private static final String MAX_HEAP_SIZE = "glassfish.maxHeapSize";
    private static final String ENABLE_ASSERTIONS = "glassfish.enableAssertions";

    private static final AtomicBoolean POST_BOOT_HINT_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean POST_START_WARNED = new AtomicBoolean();
    private static final AtomicBoolean SETTINGS_CHECKED = new AtomicBoolean();
    private static final AtomicBoolean RESTART_SKIP_LOGGED = new AtomicBoolean();

    /** The class through which a test JVM hosts GlassFish, which only GlassFish's class path has. */
    private static final String RUNTIME_CLASS = "org.glassfish.embeddable.GlassFishRuntime";

    private GlassFishPoolConfiguration configuration;
    private PoolConfig poolConfig;
    private CommonGlassFishManager<GlassFishPoolConfiguration> manager;
    private EmbeddedSlotDeployer embedded;
    private HostedSlot hosted;
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
                    ? SharedSlotRegistry.acquire(new SharedSlotRegistry.Key(slotGroup, poolDir),
                            () -> followServerLog(leaser.lease()))
                    : new LeaseHandle.Direct(followServerLog(leaser.lease()));
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
            if (ports.isEmbedded()) {
                startEmbedded(poolDir, ports, shared ? slotGroup : null);
                return;
            }
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
            warnAboutPostStartCommands();
            runPostBootCommands(ports);
        } catch (RuntimeException | LifecycleException e) {
            // Arquillian does not call stop() for a container whose start()
            // threw, so roll back the lease here — otherwise a shared handle
            // leaks a registry refcount (and the slot lock) for the JVM lifetime.
            releaseHandle();
            throw e;
        }
    }

    /**
     * Deploy to the leased slot for GlassFish Embedded: a child per
     * deployment, on its ports, from the GlassFish the pool directory names.
     */
    private void startEmbedded(Path poolDir, SlotPorts ports, String slotGroup) throws LifecycleException {
        EmbeddedGlassFish glassfish;
        boolean inProcess;
        try {
            glassfish = EmbeddedPool.read(poolDir).orElseThrow(() -> new IOException(poolDir
                    + " has a slot for GlassFish Embedded, but no " + EmbeddedPool.FILE + " that says which"));
            inProcess = EmbeddedPool.isInProcess(poolDir);
        } catch (IOException e) {
            throw new LifecycleException("Could not read the pool's GlassFish Embedded: " + e.getMessage(), e);
        }
        if (glassfish.isJar() && configuration.isHttpsPortAsDefault()) {
            throw new LifecycleException("httpsPortAsDefault needs HTTPS, which GlassFish Embedded's JAR doesn't "
                    + "serve without a server key. With an overlay, the pool runs the static shell of a ZIP, with "
                    + "domain1's key.");
        }
        if (!inProcess && (configuration.getInstanceRoot() != null || configuration.getConfigurationXml() != null)) {
            throw new LifecycleException("instanceRoot and configurationXml are for GlassFish Embedded in the test "
                    + "JVM, with the pool plugin's <embeddedMode>in-process</embeddedMode>");
        }

        configuration.setHttpPort(ports.httpPort());
        configuration.setHttpsPort(ports.httpsPort());
        LOG.info("Leased pool slot " + handle.slotIndex() + " for GlassFish Embedded (http=" + ports.httpPort() + ")"
                + (inProcess ? ", in this test JVM" : "") + (slotGroup != null ? " [shared group: " + slotGroup + "]"
                : ""));
        if (!inProcess) {
            embedded = new EmbeddedSlotDeployer(handle.lease(), glassfish, poolConfig, configuration);
            return;
        }

        requireHostable(glassfish, poolDir);
        try {
            hosted = handle.lease().hostedSlot(() -> HostedSlot.start(handle.lease(), glassfish, poolConfig,
                    configuration));
        } catch (IOException e) {
            throw new LifecycleException("Could not start GlassFish Embedded in this test JVM, on pool slot "
                    + handle.slotIndex() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Fail unless this test JVM can host the pool's GlassFish: it's on the
     * class path, and nothing else masks it there, and the JVM has GlassFish's
     * module options. With {@code <extensions>true</extensions>}, the pool
     * plugin adds both to Failsafe.
     */
    private static void requireHostable(EmbeddedGlassFish glassfish, Path poolDir) throws LifecycleException {
        ClassLoader loader = GlassFishPoolDeployableContainer.class.getClassLoader();
        Path source;
        try {
            Class<?> runtime = Class.forName(RUNTIME_CLASS, false, loader);
            source = Paths.get(runtime.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new LifecycleException("GlassFish Embedded isn't on this test JVM's class path. With "
                    + "<extensions>true</extensions>, the pool plugin adds it to failsafe's additionalClasspathElements; "
                    + "without, add " + EmbeddedPool.classPathJar(poolDir) + " there.", e);
        } catch (URISyntaxException | RuntimeException e) {
            throw new LifecycleException("Could not tell where this test JVM's GlassFish Embedded comes from: "
                    + e.getMessage(), e);
        }

        try {
            if (glassfish.classPath().stream().noneMatch(entry -> isSameFile(entry, source))) {
                throw new LifecycleException("This test JVM runs GlassFish from " + source + ", which masks the "
                        + "GlassFish Embedded the pool staged, " + glassfish.launcher() + ", and its overlays. Remove "
                        + "that dependency from the tests, or exclude it with failsafe's classpathDependencyExcludes.");
            }
            List<String> missing = ModuleOptions.missing(glassfish.moduleOptions(), loader.getUnnamedModule());
            if (!missing.isEmpty()) {
                throw new LifecycleException("This test JVM lacks the module options GlassFish Embedded needs. With "
                        + "<extensions>true</extensions>, the pool plugin adds them to failsafe's argLine; without, add "
                        + String.join(" ", missing));
            }
        } catch (IOException e) {
            throw new LifecycleException("Could not read the pool's GlassFish Embedded: " + e.getMessage(), e);
        }
    }

    private static boolean isSameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    /**
     * With {@code serverLogToConsole}, show what the slot's server writes to
     * its log while {@code lease} holds it. Only the lease that leases the
     * slot does this, so containers that share it see each line once. A
     * slot for GlassFish Embedded has no server between deployments; its
     * children's output is shown as they run.
     */
    private SlotLease followServerLog(SlotLease lease) {
        if (configuration.isServerLogToConsole() && !lease.ports().isEmbedded()) {
            String prefix = ServerLogTailer.prefix("glassfish:slot-" + lease.slotIndex(), lease.slotIndex() - 1,
                    configuration.isServerLogColor());
            lease.followServerLog(line -> System.out.println(prefix + line));
        }

        return lease;
    }

    /** Release the held lease and close the slot iff this was its last holder. */
    private void releaseHandle() {
        if (embedded != null) {
            embedded.close();
            embedded = null;
        }
        hosted = null;
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
     * Warn once per JVM when the test JVM sets {@code glassfish.postStartCommands}:
     * the managed Arquillian Container runs them after every start, but a
     * lease isn't a start.
     */
    private void warnAboutPostStartCommands() {
        if (!configuration.getPostStartCommandList().isEmpty() && POST_START_WARNED.compareAndSet(false, true)) {
            LOG.warning("The pool's Arquillian Container doesn't run glassfish.postStartCommands, since a lease "
                    + "isn't a start. To run commands after every slot start, move them to the pool plugin's "
                    + "<postStartCommands>.");
        }
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
        if (embedded != null) {
            // Children this container's deployments left running, as without their undeploy.
            embedded.close();
            embedded = null;
        }
        // Closing the lease shuts down the GlassFish this test JVM hosts, once its last holder releases it.
        hosted = null;
        // For a shared slot only the LAST holder finalizes it; earlier holders
        // get null here and leave the slot up for their siblings.
        SlotLease toFinalize = handle.release();
        if (toFinalize != null) {
            // Restart while the slot lock is still held: a free lock plus a port
            // momentarily down would let a concurrent leaser classify the slot as
            // dead and recycle it (wiping the GF install we're restarting).
            if (configuration.isRestartOnRelease() && toFinalize.ports().isEmbedded()) {
                if (RESTART_SKIP_LOGGED.compareAndSet(false, true)) {
                    LOG.info("restartOnRelease has nothing to restart on GlassFish Embedded: each deployment "
                            + "starts a child of its own, or the test JVM's GlassFish stops with the container");
                }
            } else if (configuration.isRestartOnRelease()) {
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
        if (hosted != null) {
            return hosted.deploy(archive, configuration);
        }
        return (embedded != null) ? embedded.deploy(archive) : manager.deploy(archive);
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (hosted != null) {
            hosted.undeploy(archive);
        } else if (embedded != null) {
            embedded.undeploy(archive);
        } else {
            manager.undeploy(archive);
        }
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
