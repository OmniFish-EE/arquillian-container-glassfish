/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import static ee.omnifish.arquillian.container.glassfish.pool.inprocess.it.Http.get;
import static ee.omnifish.arquillian.container.glassfish.pool.inprocess.it.Http.read;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * What the GlassFish a test JVM hosts on its slot gets: the slot setup, as
 * system properties of the test JVM and commands after the start, and the
 * trusted certificate, through the TLS context GlassFish makes the JVM's default.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
public class HostedSlotIT {

    @Deployment(testable = false)
    public static WebArchive deployment() {
        return ShrinkWrap.create(WebArchive.class, "setup.war")
                .addClasses(SetupServlet.class, HttpsClientServlet.class);
    }

    /** As system properties, and through commands after the start; a quoted value keeps its spaces. */
    @Test
    public void theSlotSetupReachesGlassFish(@ArquillianResource URL base) throws Exception {
        assertThat(get(base, "setup?property=pool.it.sysprop"), equalTo("present"));
        assertThat(get(base, "setup?property=pool.it.postboot"), equalTo("applied"));
        assertThat(get(base, "setup?property=pool.it.poststart"), equalTo("applied after start"));
    }

    @Test
    public void glassFishTrustsTheImportedCertificate(@ArquillianResource URL base) throws Exception {
        assertThat(fetchOverHttps(base, "trusted.p12"), equalTo("hello over TLS"));
    }

    /** A certificate nobody imported fails the handshake, so the test above passes for the right reason. */
    @Test
    public void glassFishRejectsACertificateNobodyImported(@ArquillianResource URL base) throws Exception {
        assertThat(fetchOverHttps(base, "untrusted.p12"), startsWith("failed: javax.net.ssl.SSLHandshakeException"));
    }

    /**
     * With an overlay, the test JVM runs the static shell of the Full ZIP, with
     * domain1's key, so it serves HTTPS too, on the port after the slot's
     * HTTP port. The JAR has no server key.
     */
    @Test
    public void theStaticShellServesHttpsOnTheSlotsPort(@ArquillianResource URL base) throws Exception {
        assumeTrue(Boolean.getBoolean("pool.it.overlay"), "Only the static shell serves HTTPS");

        URL https = new URL("https", base.getHost(), base.getPort() + 1, base.getPath() + "setup?property="
                + "pool.it.sysprop");
        HttpsURLConnection connection = (HttpsURLConnection) https.openConnection();
        // GlassFish's own key, which nothing trusts.
        connection.setSSLSocketFactory(trustingAll().getSocketFactory());
        connection.setHostnameVerifier((host, session) -> true);

        assertThat(read(connection), equalTo("present"));
    }

    /**
     * Start an HTTPS server on the loopback address with the key pair in
     * {@code keyStore}, and have the deployed app fetch its page.
     */
    private static String fetchOverHttps(URL base, String keyStore) throws Exception {
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
            return get(base, "fetch?url=" + URLEncoder.encode(page, StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    private static SSLContext sslContext(String keyStore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = HostedSlotIT.class.getResourceAsStream("/https/" + keyStore)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext trustingAll() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { trustAll }, null);
        return context;
    }
}
