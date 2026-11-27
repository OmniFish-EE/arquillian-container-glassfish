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
package ee.omnifish.glassfish.admin.embedded;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddedWorkDirTest {

    private static final EmbeddedPorts STANDARD = EmbeddedPorts.standard();

    /** Slot 1 of a pool with the default ports. */
    private static final EmbeddedPorts SLOT = EmbeddedPorts.of(14849, 14850, 14857, 14851, 14852, 14853, 14854,
            14855);

    /** The part of Embedded's own domain.xml that has the IIOP and JMS ports. */
    private static final String DOMAIN_XML = "<domain><servers><server name=\"server\" config-ref=\"server-config\"/>"
            + "</servers><configs><config name=\"server-config\"><iiop-service>"
            + "<iiop-listener address=\"0.0.0.0\" id=\"orb-listener-1\" port=\"3700\"/>"
            + "<iiop-listener security-enabled=\"true\" address=\"0.0.0.0\" id=\"SSL\" port=\"3820\"/>"
            + "<iiop-listener security-enabled=\"true\" address=\"0.0.0.0\" id=\"SSL_MUTUALAUTH\" port=\"3920\"/>"
            + "</iiop-service><jms-service type=\"EMBEDDED\" default-jms-host=\"default_JMS_host\">"
            + "<jms-host name=\"default_JMS_host\" port=\"7676\"/></jms-service></config></configs></domain>";

    /** Stands in for the pid of the Maven that runs the child. */
    private static final long OWNER = Long.MAX_VALUE;

    @TempDir
    Path tmp;

    @Test
    void theJarsChildGetsACopyOfTheArchive() throws IOException {
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive, jar(),
                STANDARD)) {
            assertThat(workDir.dir(), equalTo(tmp.resolve("work/run-" + OWNER)));
            assertThat(workDir.archiveName(), equalTo("shop.war"));
            assertThat(names(workDir.dir()), containsInAnyOrder("shop.war"));
            assertThat(Files.readString(workDir.dir().resolve("shop.war")), equalTo("war"));
        }
    }

    /** GlassFish opens its truststore with its master password, so the child gets a copy that has it. */
    @Test
    void theJarsChildGetsACopyOfItsTruststore() throws Exception {
        Path trustStore = trustStore(tmp.resolve("custom.p12"), "secret");
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive,
                EmbeddedGlassFish.jar(tmp, tmp.resolve("glassfish-embedded-all-8.0.5.jar"), trustStore, "secret"),
                STANDARD)) {
            assertThat(names(workDir.dir()), containsInAnyOrder("shop.war", EmbeddedWorkDir.TRUST_STORE));
            assertThat(load(workDir.dir().resolve(EmbeddedWorkDir.TRUST_STORE), "changeit").getCertificate("idp"),
                    equalTo(certificate()));
        }
    }

    @Test
    void anInstallsChildGetsADomainDirectory() throws Exception {
        Path home = install(tmp.resolve("glassfish8"), "<domain embedded=\"true\"/>");
        Path trustStore = trustStore(tmp.resolve("custom.p12"), "secret");
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive,
                EmbeddedGlassFish.install(tmp, home, trustStore, "secret"), STANDARD)) {
            Path domain = workDir.dir().resolve(EmbeddedWorkDir.DOMAIN_DIR);
            assertThat(names(domain.resolve("config")), containsInAnyOrder("domain.xml", "keystore.p12",
                    "cacerts.p12"));
            assertThat(Files.readString(domain.resolve("config/domain.xml")), equalTo("<domain embedded=\"true\"/>"));
            assertThat(Files.readString(domain.resolve("config/keystore.p12")), equalTo("key of s1as"));
            assertThat(load(domain.resolve("config/cacerts.p12"), "changeit").getCertificate("idp"),
                    equalTo(certificate()));
            assertThat(Files.isDirectory(domain.resolve("docroot")), is(true));
        }
    }

    @Test
    void aStaticShellWithoutEmbeddedsDomainCantRun() throws IOException {
        Path home = install(tmp.resolve("glassfish7"), null);
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        IOException e = assertThrows(IOException.class, () -> EmbeddedWorkDir.create(tmp.resolve("work"), OWNER,
                archive, EmbeddedGlassFish.install(tmp, home, home.resolve("unused.p12"), "changeit"), STANDARD));

        assertThat(e.getMessage(), containsString("has no org/glassfish/embed/domain.xml"));
    }

    /** GlassFish's deploy command splits a file name where it has a space. */
    @Test
    void anArchiveNameWithASpaceCantBeDeployed() throws IOException {
        Path archive = Files.writeString(tmp.resolve("my shop.war"), "war");

        IOException e = assertThrows(IOException.class,
                () -> EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive, jar(), STANDARD));

        assertThat(e.getMessage(), containsString("<finalName>"));
    }

    @Test
    void theWorkingDirectoriesOfMavensThatEndedAreRemoved() throws Exception {
        Path work = tmp.resolve("work");
        Process ended = new ProcessBuilder(javaExecutable(), "-version").start();
        ended.waitFor();
        Path stale = Files.createDirectories(work.resolve("run-" + ended.pid()));
        Path live = Files.createDirectories(work.resolve("run-" + ProcessHandle.current().pid()));
        Path other = Files.createDirectories(work.resolve("run-other"));
        Path own = Files.createDirectories(work.resolve("run-" + OWNER)).resolve("leftover.txt");
        Files.writeString(own, "of a run killed before");
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(work, OWNER, archive, jar(), STANDARD)) {
            assertThat(Files.exists(stale), is(false));
            assertThat(Files.exists(live), is(true));
            assertThat(Files.exists(other), is(true));
            assertThat(Files.exists(own), is(false));
        }
    }

    /** A pool slot's child gets its IIOP and JMS ports in its domain directory's domain.xml. */
    @Test
    void anInstallsChildOnASlotHasItsPortsInItsDomainXml() throws Exception {
        Path home = install(tmp.resolve("glassfish8"), DOMAIN_XML);
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive,
                EmbeddedGlassFish.install(tmp, home, trustStore(tmp.resolve("custom.p12"), "secret"), "secret"),
                SLOT)) {
            String domainXml = Files.readString(workDir.dir().resolve("domain/config/domain.xml"));
            assertThat(domainXml, containsString("id=\"orb-listener-1\" port=\"14851\""));
            assertThat(domainXml, containsString("id=\"SSL\" port=\"14852\""));
            assertThat(domainXml, containsString("id=\"SSL_MUTUALAUTH\" port=\"14853\""));
            assertThat(domainXml, containsString("name=\"default_JMS_host\" port=\"14854\""));
        }
    }

    /** So does the JAR's, which then gets a domain directory, from its own domain.xml, and keeps its truststore. */
    @Test
    void theJarsChildOnASlotGetsADomainDirectory() throws Exception {
        Path jar = jar(tmp.resolve("glassfish-embedded-all-8.0.5.jar"), DOMAIN_XML);
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive,
                EmbeddedGlassFish.jar(tmp, jar, trustStore(tmp.resolve("custom.p12"), "secret"), "secret"), SLOT)) {
            Path domain = workDir.dir().resolve(EmbeddedWorkDir.DOMAIN_DIR);
            assertThat(names(workDir.dir()), containsInAnyOrder("shop.war", EmbeddedWorkDir.TRUST_STORE,
                    EmbeddedWorkDir.DOMAIN_DIR));
            assertThat(names(domain.resolve("config")), containsInAnyOrder("domain.xml"));
            assertThat(Files.readString(domain.resolve("config/domain.xml")),
                    containsString("id=\"orb-listener-1\" port=\"14851\""));
            assertThat(Files.isDirectory(domain.resolve("docroot")), is(true));
        }
    }

    @Test
    void closingRemovesTheDirectory() throws IOException {
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");
        EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive, jar(), STANDARD);

        workDir.close();

        assertThat(Files.exists(workDir.dir()), is(false));
        assertThat(Files.isDirectory(tmp.resolve("work")), is(true));
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
        Path pem = Paths.get(EmbeddedWorkDirTest.class.getResource("/certs/trusted-a.pem").toURI());
        try (InputStream in = Files.newInputStream(pem)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static KeyStore load(Path trustStore, String password) throws Exception {
        return KeyStore.getInstance(trustStore.toFile(), password.toCharArray());
    }

    private EmbeddedGlassFish jar() {
        return EmbeddedGlassFish.jar(tmp, tmp.resolve("glassfish-embedded-all-8.0.5.jar"), null, null);
    }

    /** An install with domain1's keystore, and a kernel.jar with Embedded's {@code domainXml}, unless {@code null}. */
    private static Path install(Path home, String domainXml) throws IOException {
        Path config = Files.createDirectories(home.resolve(EmbeddedGlassFish.DOMAIN_CONFIG));
        Files.writeString(config.resolve("keystore.p12"), "key of s1as");
        Path modules = Files.createDirectories(home.resolve("glassfish/modules"));
        jar(modules.resolve("kernel.jar"), domainXml);
        return home;
    }

    /** A JAR with Embedded's {@code domainXml}, unless {@code null}. */
    private static Path jar(Path file, String domainXml) throws IOException {
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(file))) {
            jar.putNextEntry(new ZipEntry((domainXml != null) ? EmbeddedWorkDir.EMBEDDED_DOMAIN_XML
                    : "org/glassfish/x.class"));
            jar.write(((domainXml != null) ? domainXml : "class").getBytes());
            jar.closeEntry();
        }
        return file;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(entry -> entry.getFileName().toString()).collect(Collectors.toList());
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
