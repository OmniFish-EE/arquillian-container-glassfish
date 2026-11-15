/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import ee.omnifish.arquillian.container.glassfish.DomainXmlEditor;
import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;
import ee.omnifish.arquillian.container.glassfish.pool.SlotLease;
import ee.omnifish.arquillian.container.glassfish.pool.SlotLeaser;
import ee.omnifish.arquillian.container.glassfish.pool.SlotSetup;

/**
 * End-to-end smoke test for the pool container: provisions a 1-slot pool
 * via {@code glassfish-pool:up} (driven by the surrounding pom's plugin
 * binding), leases the slot through the Arquillian extension, deploys a
 * war, hits a servlet, undeploys.
 */
@ExtendWith(ArquillianExtension.class)
public class GlassFishPoolDeployWarIT {

    @Deployment(testable = false)
    public static WebArchive deployment() {
        return ShrinkWrap.create(WebArchive.class, "pool-greet.war")
                .addClass(GreeterServlet.class);
    }

    @Test
    @RunAsClient
    public void servletRespondsThroughLeasedSlot(@ArquillianResource URL baseURL) throws Exception {
        URL endpoint = new URL(baseURL, "greet");
        HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
        conn.setRequestMethod("GET");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String body = r.lines().collect(Collectors.joining());
            assertThat(body, containsString("Hello from pool slot"));
        }
    }

    /** The pom forwards nothing itself; these come from glassfish-pool:up via the extension. */
    @Test
    @RunAsClient
    public void poolLayoutIsForwardedToTestJvm() {
        assertThat(System.getProperty("gf.pool.dir"), endsWith("pool"));
        assertThat(System.getProperty("gf.pool.source"), containsString("dist"));
        assertThat(System.getProperty("gf.pool.adminBase"), equalTo("14848"));
        assertThat(System.getProperty("gf.pool.portStride"), equalTo("100"));
    }

    /** The pom forwards none of these either; up publishes the slot setup with the layout. */
    @Test
    @RunAsClient
    public void slotSetupIsForwardedToTestJvm() {
        assertThat(System.getProperty("gf.pool.systemProperties"), equalTo("pool.it.sysprop=present"));
        assertThat(System.getProperty("gf.pool.maxHeapSize"), equalTo("1g"));
        assertThat(System.getProperty("gf.pool.postBootCommands"),
                equalTo("create-system-properties pool.it.postboot=applied"));
        assertThat(System.getProperty("gf.pool.postStartCommands"),
                equalTo("create-system-properties \"pool.it.poststart=applied after start\""));
        assertThat(System.getProperty("gf.pool.failOnCommandError"), equalTo("true"));
        // Not configured, so up doesn't publish it, and the forwarded reference arrives empty.
        assertThat(System.getProperty("gf.pool.enableAssertions", ""), equalTo(""));
    }

    /**
     * The slot up started has the settings and the commands' effects, and so
     * does a slot this test JVM creates by growing the pool, which used to
     * get none of them.
     */
    @Test
    @RunAsClient
    public void firstAndGrownSlotsGetTheSetup() throws Exception {
        Path poolDir = Paths.get(System.getProperty("gf.pool.dir"));
        assertSetUp(poolDir, 1);

        // This test class's container holds slot 1, so the lease grows the pool.
        PoolConfig config = new PoolConfig(poolDir, Paths.get(System.getProperty("gf.pool.source")), 0,
                Integer.getInteger("gf.pool.adminBase"), Integer.getInteger("gf.pool.portStride"),
                SlotSetup.fromSystemProperties(), false, false);
        try (SlotLease grown = new SlotLeaser(config, 300).lease()) {
            assertThat(grown.slotIndex(), equalTo(2));
            assertSetUp(poolDir, 2);
        }
    }

    private static void assertSetUp(Path poolDir, int slot) throws Exception {
        Path domainXml = poolDir.resolve("slot-" + slot)
                .resolve("glassfish/glassfish/domains/domain1/config/domain.xml");
        assertThat(DomainXmlEditor.jvmOptions(domainXml), hasItems("-Dpool.it.sysprop=present", "-Xmx1g"));
        assertThat(systemProperty(domainXml, "pool.it.postboot"), equalTo("applied"));
        assertThat(systemProperty(domainXml, "pool.it.poststart"), equalTo("applied after start"));
    }

    /** The value of a {@code create-system-properties} property in {@code domainXml}, or {@code null}. */
    private static String systemProperty(Path domainXml, String name) throws Exception {
        NodeList properties = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(domainXml.toFile()).getElementsByTagName("system-property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (name.equals(property.getAttribute("name"))) {
                return property.getAttribute("value");
            }
        }
        return null;
    }
}
