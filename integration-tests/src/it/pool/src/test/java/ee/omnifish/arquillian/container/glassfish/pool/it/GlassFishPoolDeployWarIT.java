/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.stream.Collectors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

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
                .addClasses(GreeterServlet.class, HttpsClientServlet.class);
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

    /**
     * An app in the slot trusts the certificate staging imported: its HTTPS
     * call to a server presenting that certificate succeeds.
     */
    @Test
    @RunAsClient
    public void deployedAppTrustsTheImportedCertificate(@ArquillianResource URL baseURL) throws Exception {
        assertThat(fetchOverHttps(baseURL, "trusted.p12"), equalTo("hello over TLS"));
    }

    /** A certificate nobody imported fails the handshake, so the test above passes for the right reason. */
    @Test
    @RunAsClient
    public void deployedAppRejectsACertificateNobodyImported(@ArquillianResource URL baseURL) throws Exception {
        assertThat(fetchOverHttps(baseURL, "untrusted.p12"),
                startsWith("failed: javax.net.ssl.SSLHandshakeException"));
    }

    /**
     * Start an HTTPS server on the loopback address with the key pair in
     * {@code keyStore}, and have the deployed app fetch its page.
     */
    private static String fetchOverHttps(URL baseURL, String keyStore) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext(keyStore)));
        server.createContext("/", exchange -> {
            byte[] body = "hello over TLS".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            // The certificates name 127.0.0.1, so hostname verification passes too.
            String page = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
            URL fetch = new URL(baseURL, "fetch?url=" + URLEncoder.encode(page, StandardCharsets.UTF_8));
            try (InputStream in = fetch.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            server.stop(0);
        }
    }

    private static SSLContext sslContext(String keyStore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = GlassFishPoolDeployWarIT.class.getResourceAsStream("/https/" + keyStore)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static void assertSetUp(Path poolDir, int slot) throws Exception {
        Path config = poolDir.resolve("slot-" + slot).resolve("glassfish/glassfish/domains/domain1/config");
        Path domainXml = config.resolve("domain.xml");
        assertThat(DomainXmlEditor.jvmOptions(domainXml), hasItems("-Dpool.it.sysprop=present", "-Xmx1g"));
        assertThat(systemProperty(domainXml, "pool.it.postboot"), equalTo("applied"));
        assertThat(systemProperty(domainXml, "pool.it.poststart"), equalTo("applied after start"));
        // Imported into the staged install, which the slot was cloned from.
        KeyStore trustStore = KeyStore.getInstance(config.resolve("cacerts.p12").toFile(), "changeit".toCharArray());
        assertThat(trustStore.containsAlias("pool-it"), equalTo(true));
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
