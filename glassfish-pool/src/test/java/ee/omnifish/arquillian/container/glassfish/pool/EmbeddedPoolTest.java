/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;

class EmbeddedPoolTest {

    @TempDir
    Path tmp;

    /** The truststore goes along as a copy with GlassFish's master password, so the build's goes no further. */
    @Test
    void theJarIsDescribedWithACopyOfItsTruststore() throws Exception {
        Path pool = tmp.resolve("pool");
        Path stage = tmp.resolve("dist");
        Path jar = stage.resolve("glassfish-embedded-all-8.0.5.jar");

        EmbeddedPool.write(pool, EmbeddedGlassFish.jar(stage, jar, trustStore(tmp.resolve("custom.p12"), "secret"),
                "secret"), "embedded");

        EmbeddedGlassFish read = EmbeddedPool.read(pool).orElseThrow();
        assertThat(read.isJar(), is(true));
        assertThat(read.jar(), equalTo(jar.toAbsolutePath()));
        assertThat(read.stageDir(), equalTo(stage.toAbsolutePath()));
        assertThat(read.trustStore(), equalTo(pool.resolve(EmbeddedPool.TRUST_STORE).toAbsolutePath()));
        assertThat(read.trustStorePassword(), equalTo("changeit"));
        assertThat(KeyStore.getInstance(read.trustStore().toFile(), "changeit".toCharArray()).getCertificate("idp"),
                equalTo(certificate()));
        assertThat(Files.exists(pool.resolve(EmbeddedPool.FILE + ".tmp")), is(false));
    }

    @Test
    void anInstallWithoutATruststoreIsDescribedWithout() throws Exception {
        Path pool = tmp.resolve("pool");
        Path home = tmp.resolve("dist/glassfish8");
        Files.createDirectories(pool);
        Files.writeString(pool.resolve(EmbeddedPool.TRUST_STORE), "of an earlier description");

        EmbeddedPool.write(pool, EmbeddedGlassFish.install(tmp.resolve("dist"), home, null, null), "embedded-web");

        EmbeddedGlassFish read = EmbeddedPool.read(pool).orElseThrow();
        assertThat(read.isJar(), is(false));
        assertThat(read.home(), equalTo(home.toAbsolutePath()));
        assertThat(read.trustStore(), nullValue());
        assertThat(Files.exists(pool.resolve(EmbeddedPool.TRUST_STORE)), is(false));
    }

    /** The test JVMs learn from the pool directory that its slots run GlassFish Embedded; without it, domains. */
    @Test
    void theDescriptionMakesThePoolEmbedded() throws Exception {
        Path pool = tmp.resolve("pool");
        assertThat(PoolConfig.forTestJvm(pool).embedded(), is(false));

        EmbeddedPool.write(pool, EmbeddedGlassFish.jar(tmp, tmp.resolve("embedded.jar"), null, null), "embedded");
        assertThat(PoolConfig.forTestJvm(pool).embedded(), is(true));

        EmbeddedPool.remove(pool);
        assertThat(PoolConfig.forTestJvm(pool).embedded(), is(false));
        assertThat(EmbeddedPool.read(pool).isPresent(), is(false));
    }

    /** A truststore that {@code password} opens, with a certificate as {@code idp}. */
    private static Path trustStore(Path file, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("idp", certificate());
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, password.toCharArray());
        }
        return file;
    }

    private static Certificate certificate() throws Exception {
        Path pem = Paths.get(EmbeddedPoolTest.class.getResource("/certs/trusted-a.pem").toURI());
        try (InputStream in = Files.newInputStream(pem)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }
}
