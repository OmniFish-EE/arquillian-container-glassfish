/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.maven.staging.GlassFishStaging.ArtifactResolver;

class GlassFishStagingTest {

    private static final Path DOMAIN_CONFIG = Paths.get("glassfish", "domains", "domain1", "config");

    private static final ArtifactResolver NO_ARTIFACTS = coord -> {
        throw new AssertionError("resolved " + coord.toGav());
    };

    @Test
    void perOverlaySkipIsHonoured(@TempDir Path tmp) throws IOException {
        Path stage = tmp.resolve("stage");
        Path modules = tmp.resolve("modules");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        Path copiedJar = repo.resolve("copied.jar");
        Path skippedJar = repo.resolve("skipped.jar");
        Files.writeString(copiedJar, "copied");
        Files.writeString(skippedJar, "skipped");

        OverlayCoord copied = new OverlayCoord("g", "copied", "1", "jar", null, "copied.jar");
        OverlayCoord skipped = new OverlayCoord("g", "skipped", "1", "jar", null, "skipped.jar");
        skipped.setSkip(true);

        StageSpec spec = new StageSpec(stage, null, true, null, modules,
                List.of(copied, skipped), false);

        GlassFishStaging.stage(spec, coord -> "copied".equals(coord.getArtifactId()) ? copiedJar : skippedJar);

        assertThat(Files.exists(modules.resolve("copied.jar")), is(true));
        assertThat(Files.exists(modules.resolve("skipped.jar")), is(false));
        assertThat(Files.readString(modules.resolve("copied.jar")), equalTo("copied"));
    }

    @Test
    void singleRootDirectoryWithDirectoryEntries(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/", "glassfish9/bin/", "glassfish9/bin/asadmin", "glassfish9/glassfish/");

        assertThat(GlassFishStaging.singleRootDirectory(zip), equalTo("glassfish9"));
    }

    @Test
    void singleRootDirectoryWithoutDirectoryEntries(@TempDir Path tmp) throws IOException {
        // Zips need not carry explicit directory entries; the root is inferred from file paths.
        Path zip = zip(tmp, "payara6/bin/asadmin", "payara6/glassfish/domains/domain1/config/domain.xml");

        assertThat(GlassFishStaging.singleRootDirectory(zip), equalTo("payara6"));
    }

    @Test
    void singleRootDirectoryIsNullForSeveralRoots(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/bin/asadmin", "extras/readme.txt");

        assertThat(GlassFishStaging.singleRootDirectory(zip), is(nullValue()));
    }

    @Test
    void singleRootDirectoryIsNullForFileAtRoot(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/bin/asadmin", "LICENSE.txt");

        assertThat(GlassFishStaging.singleRootDirectory(zip), is(nullValue()));
    }

    @Test
    void distributionUnpacksUnderDerivedRoot(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/glassfish/bin/asadmin");
        Path stage = tmp.resolve("stage");
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion("9.0.0");

        GlassFishStaging.stage(new StageSpec(stage, dist, false, null, null, List.of(), false), coord -> zip);

        Path root = stage.resolve(GlassFishStaging.singleRootDirectory(zip));
        assertThat(Files.isRegularFile(root.resolve("glassfish/bin/asadmin")), is(true));
    }

    @Test
    void rerunWithSameDistributionSkipsUnpack(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/glassfish/bin/asadmin");
        Path stage = tmp.resolve("stage");
        StageSpec spec = new StageSpec(stage, distribution("9.0.0"), false, null, null, List.of(), false);
        GlassFishStaging.stage(spec, coord -> zip);
        Path domainState = Files.createFile(stage.resolve("glassfish9/glassfish/domain-state.txt"));

        GlassFishStaging.stage(spec, coord -> zip);

        // A wipe + re-unpack would have removed it
        assertThat(Files.exists(domainState), is(true));
    }

    @Test
    void rebuiltZipReplacesStagedOne(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/old.txt");
        Path stage = tmp.resolve("stage");
        StageSpec spec = new StageSpec(stage, distribution("9.0.0-SNAPSHOT"), false, null, null, List.of(), false);
        GlassFishStaging.stage(spec, coord -> zip);

        // What `mvn install` of the SNAPSHOT does: same GAV and path, newer file
        zip(tmp, "glassfish9/new.txt");
        Files.setLastModifiedTime(zip, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        GlassFishStaging.stage(spec, coord -> zip);

        assertThat(Files.exists(stage.resolve("glassfish9/old.txt")), is(false));
        assertThat(Files.isRegularFile(stage.resolve("glassfish9/new.txt")), is(true));
    }

    @Test
    void otherDistributionReplacesStagedOne(@TempDir Path tmp) throws IOException {
        Path oldZip = zip(tmp.resolve("old"), "glassfish9/old.txt");
        Path newZip = zip(tmp.resolve("new"), "glassfish9/new.txt");
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), false, null, null, List.of(), false),
                coord -> oldZip);

        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.1"), false, null, null, List.of(), false),
                coord -> newZip);

        assertThat(Files.exists(stage.resolve("glassfish9/old.txt")), is(false));
        assertThat(Files.isRegularFile(stage.resolve("glassfish9/new.txt")), is(true));
    }

    // --- trusted certificates ---

    @Test
    void importsTrustedCertsIntoTheDomainTruststore(@TempDir Path tmp) throws Exception {
        Path home = install(tmp, "cacerts.p12", "PKCS12");

        GlassFishStaging.stage(certSpec(tmp, home, certs(trusted("idp", "trusted-a.pem"))), NO_ARTIFACTS);

        KeyStore store = load(home.resolve(DOMAIN_CONFIG).resolve("cacerts.p12"), "changeit");
        assertThat(store.getType(), equalTo("PKCS12"));
        assertThat(store.getCertificate("idp"), equalTo(certificate("trusted-a.pem")));
        assertThat(store.containsAlias("existing"), is(true));
    }

    /** Older GlassFish versions ship cacerts.jks; the store keeps its type. */
    @Test
    void fallsBackToAJksTruststore(@TempDir Path tmp) throws Exception {
        Path home = install(tmp, "cacerts.jks", "JKS");

        GlassFishStaging.stage(certSpec(tmp, home, certs(trusted("idp", "trusted-a.der"))), NO_ARTIFACTS);

        KeyStore store = load(home.resolve(DOMAIN_CONFIG).resolve("cacerts.jks"), "changeit");
        assertThat(store.getType(), equalTo("JKS"));
        // The DER file holds the same certificate as the PEM one.
        assertThat(store.getCertificate("idp"), equalTo(certificate("trusted-a.pem")));
    }

    /** Staging runs on every build, and the import with it. */
    @Test
    void leavesATrustedCertAloneAndReplacesAChangedOne(@TempDir Path tmp) throws Exception {
        Path home = install(tmp, "cacerts.p12", "PKCS12");
        Path trustStore = home.resolve(DOMAIN_CONFIG).resolve("cacerts.p12");
        GlassFishStaging.stage(certSpec(tmp, home, certs(trusted("idp", "trusted-a.pem"))), NO_ARTIFACTS);
        byte[] written = Files.readAllBytes(trustStore);

        GlassFishStaging.stage(certSpec(tmp, home, certs(trusted("idp", "trusted-a.pem"))), NO_ARTIFACTS);

        // A PKCS12 store is written with fresh salts, so writing it again would change its bytes.
        assertThat(Files.readAllBytes(trustStore), equalTo(written));

        GlassFishStaging.stage(certSpec(tmp, home, certs(trusted("idp", "trusted-b.pem"))), NO_ARTIFACTS);

        assertThat(load(trustStore, "changeit").getCertificate("idp"), equalTo(certificate("trusted-b.pem")));
    }

    /**
     * The truststore is replaced, not rewritten in place, so whoever copies it
     * meanwhile reads the old file or the new one. A second name for the old
     * file shows the difference.
     */
    @Test
    void replacesTheTruststoreRatherThanRewritingIt(@TempDir Path tmp) throws Exception {
        Path home = install(tmp, "cacerts.p12", "PKCS12");
        Path trustStore = home.resolve(DOMAIN_CONFIG).resolve("cacerts.p12");
        Path oldName = tmp.resolve("old-cacerts.p12");
        try {
            Files.createLink(oldName, trustStore);
        } catch (UnsupportedOperationException | IOException e) {
            // No hardlinks on this filesystem.
            return;
        }

        GlassFishStaging.stage(certSpec(tmp, home, certs(trusted("idp", "trusted-a.pem"))), NO_ARTIFACTS);

        assertThat(load(trustStore, "changeit").containsAlias("idp"), is(true));
        assertThat(load(oldName, "changeit").containsAlias("idp"), is(false));
    }

    @Test
    void importsIntoANamedTruststore(@TempDir Path tmp) throws Exception {
        Path trustStore = trustStore(tmp.resolve("custom.p12"), "PKCS12", "secret");
        CertImport certImport = new CertImport(List.of(trusted("idp", "trusted-a.pem")), trustStore, "secret", false);

        GlassFishStaging.stage(certSpec(tmp, null, certImport), NO_ARTIFACTS);

        assertThat(load(trustStore, "secret").getCertificate("idp"), equalTo(certificate("trusted-a.pem")));
    }

    @Test
    void certsSkipImportsNothing(@TempDir Path tmp) throws Exception {
        Path home = install(tmp, "cacerts.p12", "PKCS12");
        Path trustStore = home.resolve(DOMAIN_CONFIG).resolve("cacerts.p12");
        byte[] staged = Files.readAllBytes(trustStore);
        CertImport certImport = new CertImport(List.of(trusted("idp", "trusted-a.pem")), null, null, true);

        GlassFishStaging.stage(certSpec(tmp, home, certImport), NO_ARTIFACTS);

        assertThat(Files.readAllBytes(trustStore), equalTo(staged));
    }

    @Test
    void aWrongPasswordNamesTheTruststore(@TempDir Path tmp) throws Exception {
        Path home = install(tmp, "cacerts.p12", "PKCS12");
        CertImport certImport = new CertImport(List.of(trusted("idp", "trusted-a.pem")), null, "wrong", false);

        IOException e = assertThrows(IOException.class,
                () -> GlassFishStaging.stage(certSpec(tmp, home, certImport), NO_ARTIFACTS));

        assertThat(e.getMessage(), containsString("cacerts.p12"));
    }

    @Test
    void aFileWithoutACertificateIsNamed(@TempDir Path tmp) throws Exception {
        Path home = install(tmp, "cacerts.p12", "PKCS12");
        Path notACert = Files.writeString(tmp.resolve("not-a.cert"), "hello");

        IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(
                certSpec(tmp, home, certs(new TrustedCert("idp", notACert.toFile()))), NO_ARTIFACTS));

        assertThat(e.getMessage(), containsString("not-a.cert"));
    }

    @Test
    void aDomainWithoutATruststoreIsNamed(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("glassfish9");
        Files.createDirectories(home.resolve(DOMAIN_CONFIG));

        IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(
                certSpec(tmp, home, certs(trusted("idp", "trusted-a.pem"))), NO_ARTIFACTS));

        assertThat(e.getMessage(), containsString("cacerts.p12 or cacerts.jks"));
    }

    private static DistributionCoord distribution(String version) {
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion(version);
        return dist;
    }

    private static Path zip(Path dir, String... entries) throws IOException {
        Files.createDirectories(dir);
        Path zip = dir.resolve("dist.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    out.write(entry.getBytes());
                }
                out.closeEntry();
            }
        }
        return zip;
    }

    /** An install whose domain has a truststore of the given file name and type. */
    private static Path install(Path tmp, String trustStoreName, String type) throws Exception {
        Path home = tmp.resolve("glassfish9");
        Path config = Files.createDirectories(home.resolve(DOMAIN_CONFIG));
        trustStore(config.resolve(trustStoreName), type, "changeit");
        return home;
    }

    /** A truststore holding one certificate, as {@code existing}. */
    private static Path trustStore(Path file, String type, String password) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, null);
        store.setCertificateEntry("existing", certificate("trusted-b.pem"));
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, password.toCharArray());
        }
        return file;
    }

    private static StageSpec certSpec(Path tmp, Path home, CertImport certImport) {
        return new StageSpec(tmp.resolve("stage"), null, true, home, null, List.of(), false, certImport);
    }

    private static CertImport certs(TrustedCert... certs) {
        return new CertImport(List.of(certs), null, null, false);
    }

    private static TrustedCert trusted(String alias, String fixture) throws Exception {
        return new TrustedCert(alias, fixture(fixture).toFile());
    }

    private static Certificate certificate(String fixture) throws Exception {
        try (InputStream in = Files.newInputStream(fixture(fixture))) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static Path fixture(String name) throws Exception {
        return Paths.get(GlassFishStagingTest.class.getResource("/certs/" + name).toURI());
    }

    private static KeyStore load(Path trustStore, String password) throws Exception {
        return KeyStore.getInstance(trustStore.toFile(), password.toCharArray());
    }
}
