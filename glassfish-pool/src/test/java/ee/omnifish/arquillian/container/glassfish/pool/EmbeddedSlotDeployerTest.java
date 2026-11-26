/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedProcesses;

/** Deployments to a slot for GlassFish Embedded, with a stand-in for its JAR ({@link FakeEmbedded}). */
class EmbeddedSlotDeployerTest {

    @TempDir
    Path tmp;

    private Path stage;
    private SlotLease lease;
    private EmbeddedSlotDeployer deployer;

    @BeforeEach
    void leaseSlot() throws Exception {
        stage = Files.createDirectories(tmp.resolve("dist"));
        Path slotDir = Files.createDirectories(tmp.resolve("pool/slot-1"));
        SlotPorts ports = SlotPorts.embedded(freePort(), freePort(), 0, freePort(), freePort(), freePort(), freePort(),
                freePort());
        lease = new SlotLease(1, ports, PoolLocks.tryLock(slotDir.resolve("lock")), null, slotDir);
        PoolConfig config = new PoolConfig(tmp.resolve("pool"), null, 0, freePort(), PoolConfig.DEFAULT_PORT_STRIDE,
                SlotSetup.NONE, false, false, true);
        deployer = new EmbeddedSlotDeployer(lease,
                EmbeddedGlassFish.jar(stage, FakeEmbedded.jar(tmp.resolve("fake")), null, null), config,
                new GlassFishPoolConfiguration());
    }

    @AfterEach
    void releaseSlot() {
        deployer.close();
        lease.close();
    }

    /** Its servlets come at the context root GlassFish gave it, as the Servlet protocol and the URLs need them. */
    @Test
    void aDeployedWarsServletsAreAtItsContextRoot() throws Exception {
        ProtocolMetaData metaData = deployer.deploy(war("shop.war"));

        HTTPContext context = metaData.getContexts(HTTPContext.class).iterator().next();
        assertThat(context.getHost(), equalTo("localhost"));
        assertThat(context.getPort(), equalTo(lease.ports().httpPort()));
        assertThat(context.getServletByName("StandIn").getContextRoot(), equalTo("/shop"));
        assertThat(Files.readString(lease.slotDir().resolve(EmbeddedSlotDeployer.LOG_FILE)),
                containsString("[name=shop"));
    }

    /**
     * The slot runs one archive at a time. A second one's deployment fails
     * before it disturbs the first's child, and the undeploy Arquillian calls
     * after that failure ends nothing. Once the first is undeployed, the
     * second deploys.
     */
    @Test
    void aSecondArchiveIsRefusedWhileTheFirstsChildRuns() throws Exception {
        WebArchive first = war("first.war");
        WebArchive second = war("second.war");
        deployer.deploy(first);
        List<ProcessHandle> running = EmbeddedProcesses.serving(stage, lease.slotDir());
        assertThat(running, hasSize(1));
        ProcessHandle firstChild = running.get(0);

        DeploymentException refused = assertThrows(DeploymentException.class, () -> deployer.deploy(second));
        assertThat(refused.getMessage(), containsString("runs another archive"));
        assertThat(firstChild.isAlive(), is(true));

        deployer.undeploy(second);
        assertThat(firstChild.isAlive(), is(true));

        deployer.undeploy(first);
        assertThat(firstChild.isAlive(), is(false));
        ProtocolMetaData metaData = deployer.deploy(second);
        assertThat(metaData.getContexts(HTTPContext.class).iterator().next().getServletByName("StandIn")
                .getContextRoot(), equalTo("/second"));
    }

    /** No undeploy may follow, so the failed start ends its child, and the slot takes the next archive. */
    @Test
    void aFailedStartEndsItsChildAndReleasesTheSlot() throws Exception {
        DeploymentException failed = assertThrows(DeploymentException.class, () -> deployer.deploy(war("broken.war")));

        assertThat(failed.getMessage(), containsString("GlassFish Embedded ended with exit code 1 before it started"));
        assertThat(failed.getMessage(), containsString("SEVERE: the archive is broken"));
        assertThat(lease.child(), nullValue());
        assertThat(EmbeddedProcesses.serving(stage, lease.slotDir()), hasSize(0));
        deployer.deploy(war("shop.war"));
    }

    /** As a child its test JVM left running before down ended it: the deployment fails, naming the port. */
    @Test
    void aDeploymentFailsWhenSomethingAnswersOnTheSlotsPort() throws Exception {
        try (ServerSocket taken = new ServerSocket(lease.ports().httpPort(), 50, InetAddress.getLoopbackAddress())) {
            DeploymentException failed = assertThrows(DeploymentException.class, () -> deployer.deploy(war("a.war")));

            assertThat(failed.getMessage(), containsString("Something already answers on port "
                    + lease.ports().httpPort() + " of pool slot 1"));
            assertThat(lease.child(), nullValue());
        }
    }

    @Test
    void aSlotsChildIsClaimedByOneArchiveAtATime() {
        assertThat(lease.claimChild("first"), is(true));
        assertThat(lease.claimChild("second"), is(false));

        lease.releaseChild("second");
        assertThat(lease.child(), equalTo("first"));

        lease.releaseChild("first");
        assertThat(lease.claimChild("second"), is(true));
    }

    /** GlassFish names an EAR after its application.xml, and anything else after its file. */
    @Test
    void theApplicationIsNamedAsGlassFishWouldNameIt() throws IOException {
        Path named = ear(tmp.resolve("named.ear"), "<application xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" "
                + "version=\"10\"><application-name>shop-app</application-name></application>");
        Path unnamed = ear(tmp.resolve("unnamed.ear"), null);

        assertThat(EmbeddedSlotDeployer.applicationName("named.ear", named), equalTo("shop-app"));
        assertThat(EmbeddedSlotDeployer.applicationName("unnamed.ear", unnamed), equalTo("unnamed"));
        assertThat(EmbeddedSlotDeployer.applicationName("shop.war", tmp.resolve("shop.war")), equalTo("shop"));
    }

    @Test
    void eachServletIsAtItsModulesContextRoot() {
        Map<String, List<String>> servlets = new LinkedHashMap<>();
        servlets.put("/alpha", List.of("AlphaServlet", "ArquillianServletRunnerEE9"));
        servlets.put("/beta-root", List.of("BetaServlet"));

        HTTPContext context = EmbeddedSlotDeployer.metaData(14850, true, servlets).getContexts(HTTPContext.class)
                .iterator().next();

        assertThat(context.getScheme(), equalTo("https"));
        assertThat(context.getServletByName("ArquillianServletRunnerEE9").getContextRoot(), equalTo("/alpha"));
        assertThat(context.getServletByName("BetaServlet").getContextRoot(), equalTo("/beta-root"));
        assertThat(context.getServlets().stream().map(Servlet::getName).collect(Collectors.toList()),
                containsInAnyOrder("AlphaServlet", "ArquillianServletRunnerEE9", "BetaServlet"));
    }

    private static WebArchive war(String name) {
        return ShrinkWrap.create(WebArchive.class, name).addAsWebResource(new StringAsset(name), "index.html");
    }

    /** An EAR with {@code applicationXml} as its descriptor, unless that's {@code null}. */
    private static Path ear(Path file, String applicationXml) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(applicationXml != null ? "META-INF/application.xml" : "web.war"));
            out.write((applicationXml != null ? applicationXml : "war").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
