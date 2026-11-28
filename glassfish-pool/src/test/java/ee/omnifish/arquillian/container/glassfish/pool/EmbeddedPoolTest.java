/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        try (Stream<Path> files = Files.list(pool)) {
            assertThat(files.noneMatch(file -> file.toString().endsWith(".tmp")), is(true));
        }
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

    /**
     * With in-process, the test JVMs host GlassFish themselves, from the class
     * path that a JAR in the stage directory names, which the extension adds
     * to failsafe's.
     */
    @Test
    void theDescriptionSaysWhetherTheTestJvmsHostGlassFish() throws Exception {
        Path pool = tmp.resolve("pool");
        Path stage = Files.createDirectories(tmp.resolve("dist"));
        EmbeddedGlassFish glassfish = EmbeddedGlassFish.jar(stage, stage.resolve("glassfish-embedded-web-8.0.5.jar"),
                null, null);
        assertThat(EmbeddedPool.isInProcess(pool), is(false));

        EmbeddedPool.write(pool, glassfish, "embedded-web", EmbeddedPool.IN_PROCESS);
        assertThat(EmbeddedPool.isInProcess(pool), is(true));
        try (JarFile classPathJar = new JarFile(glassfish.classPathJar().toFile())) {
            assertThat(classPathJar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH),
                    equalTo("glassfish-embedded-web-8.0.5.jar"));
        }

        EmbeddedPool.write(pool, glassfish, "embedded-web");
        assertThat(EmbeddedPool.isInProcess(pool), is(false));
    }

    /**
     * Two JVMs that publish the description at once, as the
     * {@code glassfish-pool:run}s of two modules that share a pool do, both
     * succeed, and leave a whole description, with a whole truststore.
     */
    @Test
    void twoJvmsPublishAtOnce() throws Exception {
        Path pool = tmp.resolve("pool");
        Path trustStore = trustStore(tmp.resolve("custom.p12"), "secret");
        Path go = tmp.resolve("go");
        List<Process> writers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            writers.add(writer(pool, trustStore, go));
        }
        Files.createFile(go);

        for (Process writer : writers) {
            assertThat(writer.waitFor(120, TimeUnit.SECONDS), is(true));
            assertThat(new String(writer.getErrorStream().readAllBytes(), StandardCharsets.UTF_8),
                    writer.exitValue(), equalTo(0));
        }
        EmbeddedGlassFish read = EmbeddedPool.read(pool).orElseThrow();
        assertThat(KeyStore.getInstance(read.trustStore().toFile(), "changeit".toCharArray()).getCertificate("idp"),
                equalTo(certificate()));
    }

    /** An {@link EmbeddedPoolWriter} in a JVM of its own, once it's ready. */
    private static Process writer(Path pool, Path trustStore, Path go) throws Exception {
        String classPath = Stream.of(EmbeddedPoolWriter.class, EmbeddedPool.class, EmbeddedGlassFish.class)
                .map(type -> {
                    try {
                        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (java.net.URISyntaxException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.joining(File.pathSeparator));
        Process writer = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath, EmbeddedPoolWriter.class.getName(), pool.toString(), trustStore.toString(),
                "secret", "50", go.toString()).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(writer.getInputStream(),
                StandardCharsets.UTF_8));
        assertThat(out.readLine(), equalTo("ready"));
        return writer;
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
