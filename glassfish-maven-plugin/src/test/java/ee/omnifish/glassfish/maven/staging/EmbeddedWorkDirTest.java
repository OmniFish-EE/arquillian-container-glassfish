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
package ee.omnifish.glassfish.maven.staging;

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

    /** Stands in for the pid of the Maven that runs the child. */
    private static final long OWNER = Long.MAX_VALUE;

    @TempDir
    Path tmp;

    @Test
    void theJarsChildGetsACopyOfTheArchive() throws IOException {
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive, jar())) {
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
                EmbeddedGlassFish.jar(tmp, tmp.resolve("glassfish-embedded-all-8.0.5.jar"), trustStore, "secret"))) {
            assertThat(names(workDir.dir()), containsInAnyOrder("shop.war", EmbeddedWorkDir.TRUST_STORE));
            assertThat(load(workDir.dir().resolve(EmbeddedWorkDir.TRUST_STORE), "changeit").getCertificate("idp"),
                    equalTo(certificate()));
        }
    }

    @Test
    void anInstallsChildGetsADomainDirectory() throws Exception {
        Path home = install(tmp.resolve("glassfish8"), true);
        Path trustStore = trustStore(tmp.resolve("custom.p12"), "secret");
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive,
                EmbeddedGlassFish.install(tmp, home, trustStore, "secret"))) {
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
        Path home = install(tmp.resolve("glassfish7"), false);
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");

        IOException e = assertThrows(IOException.class, () -> EmbeddedWorkDir.create(tmp.resolve("work"), OWNER,
                archive, EmbeddedGlassFish.install(tmp, home, home.resolve("unused.p12"), "changeit")));

        assertThat(e.getMessage(), containsString("has no org/glassfish/embed/domain.xml"));
    }

    /** GlassFish's deploy command splits a file name where it has a space. */
    @Test
    void anArchiveNameWithASpaceCantBeDeployed() throws IOException {
        Path archive = Files.writeString(tmp.resolve("my shop.war"), "war");

        IOException e = assertThrows(IOException.class,
                () -> EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive, jar()));

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

        try (EmbeddedWorkDir workDir = EmbeddedWorkDir.create(work, OWNER, archive, jar())) {
            assertThat(Files.exists(stale), is(false));
            assertThat(Files.exists(live), is(true));
            assertThat(Files.exists(other), is(true));
            assertThat(Files.exists(own), is(false));
        }
    }

    @Test
    void whileTheChildRunsTheStageDirectoryNamesIt() throws IOException {
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");
        EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive, jar());

        workDir.runs(ProcessHandle.current());
        assertThat(EmbeddedProcesses.runningFrom(tmp).isPresent(), is(true));

        workDir.close();
        assertThat(Files.exists(tmp.resolve(EmbeddedProcesses.PID_FILE)), is(false));
    }

    @Test
    void closingRemovesTheDirectory() throws IOException {
        Path archive = Files.writeString(tmp.resolve("shop.war"), "war");
        EmbeddedWorkDir workDir = EmbeddedWorkDir.create(tmp.resolve("work"), OWNER, archive, jar());

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

    /** An install with domain1's keystore, and a kernel.jar that has Embedded's domain.xml when {@code embedded}. */
    private static Path install(Path home, boolean embedded) throws IOException {
        Path config = Files.createDirectories(home.resolve(GlassFishStaging.DOMAIN_CONFIG));
        Files.writeString(config.resolve("keystore.p12"), "key of s1as");
        Path modules = Files.createDirectories(home.resolve("glassfish/modules"));
        try (ZipOutputStream kernel = new ZipOutputStream(Files.newOutputStream(modules.resolve("kernel.jar")))) {
            kernel.putNextEntry(new ZipEntry(embedded ? EmbeddedWorkDir.EMBEDDED_DOMAIN_XML : "org/glassfish/x.class"));
            kernel.write("<domain embedded=\"true\"/>".getBytes());
            kernel.closeEntry();
        }
        return home;
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
