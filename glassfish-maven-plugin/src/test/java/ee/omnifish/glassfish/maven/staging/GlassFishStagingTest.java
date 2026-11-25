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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
    void perOverlayApplyIsHonoured(@TempDir Path tmp) throws IOException {
        Path modules = tmp.resolve("modules");
        Path appliedJar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("applied.jar"),
                "applied");
        OverlayCoord applied = new OverlayCoord("g", "applied", "1", "jar", null, "applied.jar");
        applied.setApply(true);
        OverlayCoord leftOut = new OverlayCoord("g", "left-out", "1", "jar", null, "left-out.jar");
        leftOut.setApply(false);
        StageSpec spec = new StageSpec(tmp.resolve("stage"), null, true, null, modules, List.of(applied, leftOut),
                false);

        GlassFishStaging.stage(spec,
                coord -> "applied".equals(coord.getArtifactId()) ? appliedJar : NO_ARTIFACTS.resolve(coord));

        assertThat(Files.readString(modules.resolve("applied.jar")), equalTo("applied"));
        assertThat(Files.exists(modules.resolve("left-out.jar")), is(false));
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

    // --- an owned stage directory, staged afresh when its inputs change ---

    @Test
    void unchangedOverlaysAndCertificatesLeaveTheStagedInstallAlone(@TempDir Path tmp) throws Exception {
        Path zip = zipWithTrustStore(tmp);
        Path jar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("faces.jar"), "build 1");
        StageSpec spec = ownedSpec(tmp.resolve("stage"), List.of(faces()), certs(trusted("idp", "trusted-a.pem")));
        GlassFishStaging.stage(spec, facesOr(jar, zip));
        Path leftover = Files.createFile(tmp.resolve("stage/glassfish9/leftover.txt"));

        GlassFishStaging.stage(spec, facesOr(jar, zip));

        assertThat(Files.exists(leftover), is(true));
    }

    /** What {@code mvn install} of a SNAPSHOT overlay does: same GAV and path, new content. */
    @Test
    void aRebuiltOverlayStagesAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, "glassfish9/glassfish/modules/shipped.jar");
        Path jar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("faces.jar"), "build 1");
        StageSpec spec = ownedSpec(tmp.resolve("stage"), List.of(faces()), CertImport.NONE);
        GlassFishStaging.stage(spec, facesOr(jar, zip));
        Path leftover = Files.createFile(tmp.resolve("stage/glassfish9/leftover.txt"));

        Files.writeString(jar, "build 2");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        GlassFishStaging.stage(spec, facesOr(jar, zip));

        assertThat(Files.exists(leftover), is(false));
        assertThat(Files.readString(tmp.resolve("stage/glassfish9/glassfish/modules/faces.jar")), equalTo("build 2"));
    }

    @Test
    void aRemovedOverlayIsGoneAfterStagingAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, "glassfish9/glassfish/modules/shipped.jar");
        Path jar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("faces.jar"), "build 1");
        GlassFishStaging.stage(ownedSpec(tmp.resolve("stage"), List.of(faces()), CertImport.NONE), facesOr(jar, zip));

        GlassFishStaging.stage(ownedSpec(tmp.resolve("stage"), List.of(), CertImport.NONE), facesOr(jar, zip));

        assertThat(Files.exists(tmp.resolve("stage/glassfish9/glassfish/modules/faces.jar")), is(false));
        assertThat(Files.exists(tmp.resolve("stage/glassfish9/glassfish/modules/shipped.jar")), is(true));
    }

    @Test
    void anOverlaySwitchedOffWithApplyIsGoneAfterStagingAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, "glassfish9/glassfish/modules/shipped.jar");
        Path jar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("faces.jar"), "build 1");
        GlassFishStaging.stage(ownedSpec(tmp.resolve("stage"), List.of(faces()), CertImport.NONE), facesOr(jar, zip));
        OverlayCoord switchedOff = faces();
        switchedOff.setApply(false);

        GlassFishStaging.stage(ownedSpec(tmp.resolve("stage"), List.of(switchedOff), CertImport.NONE),
                facesOr(jar, zip));

        assertThat(Files.exists(tmp.resolve("stage/glassfish9/glassfish/modules/faces.jar")), is(false));
        assertThat(Files.exists(tmp.resolve("stage/glassfish9/glassfish/modules/shipped.jar")), is(true));
    }

    /** A domain glassfish:start left running is the user's, so staging doesn't wipe the install under it. */
    @Test
    void anInstallItsDomainRunsFromIsNotStagedAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, "glassfish9/glassfish/modules/shipped.jar");
        Path jar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("faces.jar"), "build 1");
        StageSpec spec = ownedSpec(tmp.resolve("stage"), List.of(faces()), CertImport.NONE);
        GlassFishStaging.stage(spec, facesOr(jar, zip));
        Path leftover = Files.createFile(tmp.resolve("stage/glassfish9/leftover.txt"));
        Process server = FakeDomain.startServer(tmp.resolve("stage/glassfish9"));
        try {
            Files.writeString(jar, "build 2");
            Files.setLastModifiedTime(jar, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));
            IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, facesOr(jar, zip)));

            assertThat(e.getMessage(), containsString("glassfish:stop"));
            assertThat(Files.exists(leftover), is(true));
        } finally {
            server.destroyForcibly();
        }
    }

    /**
     * Another distribution unpacks to another directory, while the domain
     * glassfish:start started runs from the one staged before. The marker
     * records that one, and staging checks it.
     */
    @Test
    void anInstallItsDomainRunsFromIsNotReplacedByAnotherDistribution(@TempDir Path tmp) throws Exception {
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(new StageSpec(stage, distribution("7.1.0"), false, stage.resolve("glassfish7"),
                null, List.of(), false), coord -> zip(tmp, "glassfish7/glassfish/modules/shipped.jar"));
        assertThat(GlassFishStaging.stagedHome(stage), equalTo(stage.resolve("glassfish7").toAbsolutePath()));
        Process server = FakeDomain.startServer(stage.resolve("glassfish7"));
        try {
            IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(
                    new StageSpec(stage, distribution("8.0.5"), false, stage.resolve("glassfish8"), null, List.of(),
                            false), coord -> zip(tmp.resolve("other"), "glassfish8/glassfish/modules/shipped.jar")));

            assertThat(e.getMessage(), containsString(stage.resolve("glassfish7").toAbsolutePath().toString()));
            assertThat(Files.isDirectory(stage.resolve("glassfish7")), is(true));
        } finally {
            server.destroyForcibly();
        }
    }

    /** Plugin 2.2.2 didn't record the install in the marker; a run that finds it staged adds it. */
    @Test
    void aMarkerWithoutTheInstallGetsIt(@TempDir Path tmp) throws Exception {
        Path stage = tmp.resolve("stage");
        StageSpec spec = new StageSpec(stage, distribution("7.1.0"), false, stage.resolve("glassfish7"), null,
                List.of(), false);
        Path zip = zip(tmp, "glassfish7/glassfish/modules/shipped.jar");
        GlassFishStaging.stage(spec, coord -> zip);
        forgetHome(stage);
        Path leftover = Files.writeString(stage.resolve("glassfish7/leftover.txt"), "kept");

        GlassFishStaging.stage(spec, coord -> zip);

        assertThat(GlassFishStaging.stagedHome(stage), equalTo(stage.resolve("glassfish7").toAbsolutePath()));
        assertThat(Files.exists(leftover), is(true));
    }

    /** Also with a marker plugin 2.2.2 wrote, which doesn't say which install the domain runs from. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Java doesn't read other processes' command lines there")
    void anInstallItsDomainRunsFromIsNotReplacedAlsoWhenTheMarkerDoesNotRecordIt(@TempDir Path tmp)
            throws Exception {
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(new StageSpec(stage, distribution("7.1.0"), false, stage.resolve("glassfish7"),
                null, List.of(), false), coord -> zip(tmp, "glassfish7/glassfish/modules/shipped.jar"));
        forgetHome(stage);
        Process server = FakeDomain.startServer(stage.resolve("glassfish7"));
        try {
            IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(
                    new StageSpec(stage, distribution("8.0.5"), false, stage.resolve("glassfish8"), null, List.of(),
                            false), coord -> zip(tmp.resolve("other"), "glassfish8/glassfish/modules/shipped.jar")));

            assertThat(e.getMessage(), containsString(stage.resolve("glassfish7").toAbsolutePath().toString()));
            assertThat(Files.isDirectory(stage.resolve("glassfish7")), is(true));
        } finally {
            server.destroyForcibly();
        }
    }

    /** As after a server was killed, which leaves its pid file behind. */
    @Test
    void aStalePidFileDoesNotKeepTheInstallFromBeingStagedAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, "glassfish9/glassfish/modules/shipped.jar");
        Path jar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("faces.jar"), "build 1");
        StageSpec spec = ownedSpec(tmp.resolve("stage"), List.of(faces()), CertImport.NONE);
        GlassFishStaging.stage(spec, facesOr(jar, zip));
        Process ended = new ProcessBuilder("true").start();
        ended.waitFor();
        runsFrom(tmp.resolve("stage/glassfish9"), ended.pid());

        Files.writeString(jar, "build 2");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        GlassFishStaging.stage(spec, facesOr(jar, zip));

        assertThat(Files.readString(tmp.resolve("stage/glassfish9/glassfish/modules/faces.jar")), equalTo("build 2"));
    }

    @Test
    void aCertificateReplacedAtTheSamePathStagesAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zipWithTrustStore(tmp);
        Path cert = Files.copy(fixture("trusted-a.pem"), tmp.resolve("idp.pem"));
        StageSpec spec = ownedSpec(tmp.resolve("stage"), List.of(), certs(new TrustedCert("idp", cert.toFile())));
        GlassFishStaging.stage(spec, coord -> zip);
        Path leftover = Files.createFile(tmp.resolve("stage/glassfish9/leftover.txt"));

        Files.copy(fixture("trusted-b.pem"), cert, StandardCopyOption.REPLACE_EXISTING);
        GlassFishStaging.stage(spec, coord -> zip);

        assertThat(Files.exists(leftover), is(false));
        assertThat(load(stagedTrustStore(tmp), "changeit").getCertificate("idp"), equalTo(certificate("trusted-b.pem")));
    }

    /** The import only adds and replaces entries, so only staging afresh drops a certificate. */
    @Test
    void aCertificateRemovedFromTheListIsGoneAfterStagingAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zipWithTrustStore(tmp);
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(ownedSpec(stage, List.of(),
                certs(trusted("idp", "trusted-a.pem"), trusted("other", "trusted-b.pem"))), coord -> zip);

        GlassFishStaging.stage(ownedSpec(stage, List.of(), certs(trusted("idp", "trusted-a.pem"))), coord -> zip);

        KeyStore store = load(stagedTrustStore(tmp), "changeit");
        assertThat(store.containsAlias("idp"), is(true));
        assertThat(store.containsAlias("other"), is(false));
    }

    /**
     * A marker without a fingerprint, as an interrupted staging leaves it, or
     * an older version of the plugin, means the install may be incomplete.
     */
    @Test
    void aStagingThatDidntFinishIsRedone(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/glassfish/bin/asadmin");
        Path stage = Files.createDirectories(tmp.resolve("stage"));
        Files.writeString(stage.resolve(".staging-glassfish.properties"),
                "distribution=org.glassfish.main.distributions\\:glassfish\\:9.0.0\\:zip\n");
        Path halfWritten = Files.writeString(Files.createDirectories(stage.resolve("glassfish9/half")).resolve("a.txt"), "");

        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), false, null, null, List.of(), false),
                coord -> zip);

        assertThat(Files.exists(halfWritten), is(false));
        assertThat(Files.isRegularFile(stage.resolve("glassfish9/glassfish/bin/asadmin")), is(true));
    }

    /**
     * The marker claims the stage directory before the unpack, and gets its
     * fingerprint only at the end, so a staging that broke off halfway is
     * redone, rather than skipped, or taken for a directory it didn't stage.
     */
    @Test
    void aStagingThatBrokeOffHalfwayIsRedone(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, "glassfish9/glassfish/modules/shipped.jar");
        Path jar = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("faces.jar"), "build 1");
        StageSpec spec = ownedSpec(tmp.resolve("stage"), List.of(faces()), CertImport.NONE);
        // The overlay resolves for the fingerprint, then fails when it's copied.
        AtomicInteger facesResolved = new AtomicInteger();
        assertThrows(IOException.class, () -> GlassFishStaging.stage(spec, coord -> {
            if ("faces".equals(coord.getArtifactId()) && facesResolved.incrementAndGet() > 1) {
                throw new IOException("repository gone");
            }
            return facesOr(jar, zip).resolve(coord);
        }));

        GlassFishStaging.stage(spec, facesOr(jar, zip));

        assertThat(Files.readString(tmp.resolve("stage/glassfish9/glassfish/modules/faces.jar")), equalTo("build 1"));
    }

    /**
     * With unpackSkip, overlays and certificates may go into an install
     * staged here before, which its fingerprint then no longer describes.
     */
    @Test
    void anInstallChangedWithUnpackSkipIsStagedAfreshAfterwards(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, "glassfish9/glassfish/modules/shipped.jar");
        Path jarA = Files.writeString(Files.createDirectories(tmp.resolve("repo")).resolve("a.jar"), "A");
        Path jarB = Files.writeString(tmp.resolve("repo/b.jar"), "B");
        OverlayCoord a = new OverlayCoord("g", "a", "1", "jar", null, "a.jar");
        OverlayCoord b = new OverlayCoord("g", "b", "1", "jar", null, "b.jar");
        ArtifactResolver resolver = coord -> "a".equals(coord.getArtifactId()) ? jarA
                : "b".equals(coord.getArtifactId()) ? jarB : zip;
        Path stage = tmp.resolve("stage");
        Path modules = stage.resolve("glassfish9/glassfish/modules");
        GlassFishStaging.stage(ownedSpec(stage, List.of(a), CertImport.NONE), resolver);
        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), true, stage.resolve("glassfish9"), modules,
                List.of(b), false), resolver);

        GlassFishStaging.stage(ownedSpec(stage, List.of(a), CertImport.NONE), resolver);

        assertThat(Files.exists(modules.resolve("b.jar")), is(false));
        assertThat(Files.exists(modules.resolve("a.jar")), is(true));
    }

    /** The default truststore is found from the install root, so another root needs the import too. */
    @Test
    void anotherInstallRootStagesAfresh(@TempDir Path tmp) throws Exception {
        Path zip = zipWithTrustStores(tmp, "first", "second");
        Path stage = tmp.resolve("stage");
        CertImport certImport = certs(trusted("idp", "trusted-a.pem"));
        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), false, stage.resolve("first"), null,
                List.of(), false, certImport), coord -> zip);

        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), false, stage.resolve("second"), null,
                List.of(), false, certImport), coord -> zip);

        Path secondTrustStore = stage.resolve("second").resolve(DOMAIN_CONFIG).resolve("cacerts.p12");
        assertThat(load(secondTrustStore, "changeit").containsAlias("idp"), is(true));
    }

    /** The stage directory may point at an install this plugin didn't unpack. */
    @Test
    void aNonEmptyStageDirWithoutAMarkerIsLeftAlone(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp, "glassfish9/glassfish/bin/asadmin");
        Path stage = Files.createDirectories(tmp.resolve("stage"));
        Path notOurs = Files.writeString(stage.resolve("not-ours.txt"), "mine");

        IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(
                new StageSpec(stage, distribution("9.0.0"), false, null, null, List.of(), false), coord -> zip));

        assertThat(e.getMessage(), containsString("didn't stage it"));
        assertThat(Files.readString(notOurs), equalTo("mine"));
        assertThat(Files.exists(stage.resolve("glassfish9")), is(false));
    }

    /** Unpacking over leftovers would mix two installs. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "makes a directory read-only through POSIX permissions")
    void aFileThatCantBeDeletedFailsStagingAfresh(@TempDir Path tmp) throws IOException {
        Path oldZip = zip(tmp.resolve("old"), "glassfish9/locked/file.txt");
        Path newZip = zip(tmp.resolve("new"), "glassfish9/new.txt");
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), false, null, null, List.of(), false),
                coord -> oldZip);
        Path locked = stage.resolve("glassfish9/locked");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(locked), "root can delete it anyway");

            IOException e = assertThrows(IOException.class, () -> GlassFishStaging.stage(
                    new StageSpec(stage, distribution("9.0.1"), false, null, null, List.of(), false), coord -> newZip));

            assertThat(e.getMessage(), containsString("file.txt"));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // --- GlassFish Embedded's JAR ---

    @Test
    void anEmbeddedJarIsCopiedNotUnpacked(@TempDir Path tmp) throws IOException {
        Path jar = zip(tmp, "org/glassfish/runnablejar/UberMain.class");
        Path stage = tmp.resolve("stage");

        GlassFishStaging.stage(embeddedSpec(stage, CertImport.NONE), coord -> jar);

        Path staged = stage.resolve("glassfish-embedded-all-9.0.0.jar");
        assertThat(GlassFishStaging.stagedJar(stage, embedded()), equalTo(staged));
        assertThat(Files.readAllBytes(staged), equalTo(Files.readAllBytes(jar)));
        assertThat(Files.exists(stage.resolve("org")), is(false));
    }

    @Test
    void aRebuiltEmbeddedJarIsCopiedAgain(@TempDir Path tmp) throws IOException {
        Path jar = zip(tmp, "build-1");
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(embeddedSpec(stage, CertImport.NONE), coord -> jar);
        Path leftover = Files.createFile(stage.resolve("leftover.txt"));

        GlassFishStaging.stage(embeddedSpec(stage, CertImport.NONE), coord -> jar);
        assertThat(Files.exists(leftover), is(true));

        zip(tmp, "build-2");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        GlassFishStaging.stage(embeddedSpec(stage, CertImport.NONE), coord -> jar);

        assertThat(Files.readAllBytes(stage.resolve("glassfish-embedded-all-9.0.0.jar")),
                equalTo(Files.readAllBytes(jar)));
        assertThat(Files.exists(leftover), is(false));
    }

    /** Its domain has no truststore: the certificates join the JDK's trust anchors, in a store next to the JAR. */
    @Test
    void anEmbeddedJarsCertificatesJoinTheJdksTrustAnchors(@TempDir Path tmp) throws Exception {
        Path jar = zip(tmp, "org/glassfish/runnablejar/UberMain.class");
        Path stage = tmp.resolve("stage");
        StageSpec spec = embeddedSpec(stage, certs(trusted("idp", "trusted-a.pem")));

        GlassFishStaging.stage(spec, coord -> jar);

        Path trustStore = stage.resolve(GlassFishStaging.EMBEDDED_TRUST_STORE);
        KeyStore store = load(trustStore, "changeit");
        KeyStore jdk = KeyStore.getInstance(Paths.get(System.getProperty("java.home"), "lib", "security", "cacerts")
                .toFile(), (char[]) null);
        assertThat(store.getType(), equalTo("PKCS12"));
        assertThat(store.getCertificate("idp"), equalTo(certificate("trusted-a.pem")));
        assertThat(store.size(), equalTo(jdk.size() + 1));

        FileTime written = Files.getLastModifiedTime(trustStore);
        GlassFishStaging.stage(spec, coord -> jar);
        assertThat(Files.getLastModifiedTime(trustStore), equalTo(written));
    }

    @Test
    void switchingFromAnEmbeddedJarToAZipStagesAfresh(@TempDir Path tmp) throws IOException {
        Path jar = zip(tmp.resolve("jar"), "org/glassfish/runnablejar/UberMain.class");
        Path zip = zip(tmp.resolve("zip"), "glassfish9/glassfish/bin/asadmin");
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(embeddedSpec(stage, CertImport.NONE), coord -> jar);

        GlassFishStaging.stage(new StageSpec(stage, distribution("9.0.0"), false, stage.resolve("glassfish9"), null,
                List.of(), false), coord -> zip);

        assertThat(Files.exists(stage.resolve("glassfish-embedded-all-9.0.0.jar")), is(false));
        assertThat(Files.isRegularFile(stage.resolve("glassfish9/glassfish/bin/asadmin")), is(true));
    }

    /** A glassfish:run in another Maven runs GlassFish Embedded from the stage directory, so staging leaves it be. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Java doesn't say what a process runs there")
    void aJarAnEmbeddedChildRunsFromIsNotStagedAfresh(@TempDir Path tmp) throws Exception {
        Path jar = zip(tmp, "build-1");
        Path stage = tmp.resolve("stage");
        GlassFishStaging.stage(embeddedSpec(stage, CertImport.NONE), coord -> jar);
        Process child = FakeDomain.startEmbeddedChild(stage.resolve("glassfish-embedded-all-9.0.0.jar"));
        try {
            zip(tmp, "build-2");
            Files.setLastModifiedTime(jar, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));
            IOException e = assertThrows(IOException.class,
                    () -> GlassFishStaging.stage(embeddedSpec(stage, CertImport.NONE), coord -> jar));

            assertThat(e.getMessage(), containsString("GlassFish Embedded runs from it"));
            assertThat(new String(Files.readAllBytes(stage.resolve("glassfish-embedded-all-9.0.0.jar"))),
                    containsString("build-1"));
        } finally {
            child.destroyForcibly().waitFor();
        }
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

    private static DistributionCoord embedded() {
        return Edition.EMBEDDED.distribution("9.0.0", null, false);
    }

    /** A staging run that copies GlassFish Embedded 9.0.0's JAR into {@code stage}. */
    private static StageSpec embeddedSpec(Path stage, CertImport certImport) {
        return new StageSpec(stage, embedded(), false, null, null, List.of(), false, certImport);
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

    /** A staging run that unpacks GlassFish 9.0.0 into {@code stage}, which staging then owns. */
    /** Write the pid file GlassFish keeps while {@code domain1} of {@code home} runs. */
    private static void runsFrom(Path home, long pid) throws IOException {
        Path config = Files.createDirectories(home.resolve(DOMAIN_CONFIG));
        Files.writeString(config.resolve("pid"), String.valueOf(pid));
    }

    private static StageSpec ownedSpec(Path stage, List<OverlayCoord> overlays, CertImport certImport) {
        Path home = stage.resolve("glassfish9");
        return new StageSpec(stage, distribution("9.0.0"), false, home, home.resolve("glassfish/modules"), overlays,
                false, certImport);
    }

    private static OverlayCoord faces() {
        return new OverlayCoord("org.glassfish", "faces", "5.0.0-SNAPSHOT", "jar", null, "faces.jar");
    }

    /** Resolves the {@link #faces} overlay to {@code jar}, and anything else to {@code zip}. */
    private static ArtifactResolver facesOr(Path jar, Path zip) {
        return coord -> "faces".equals(coord.getArtifactId()) ? jar : zip;
    }

    /** A distribution zip whose domain has a truststore, like the real one. */
    private static Path zipWithTrustStore(Path tmp) throws Exception {
        return zipWithTrustStores(tmp, "glassfish9");
    }

    /** A distribution zip with an install under each of {@code roots}, each with a domain truststore. */
    private static Path zipWithTrustStores(Path tmp, String... roots) throws Exception {
        Path root = tmp.resolve("unzipped");
        for (String installRoot : roots) {
            trustStore(Files.createDirectories(root.resolve(installRoot).resolve(DOMAIN_CONFIG)).resolve("cacerts.p12"),
                    "PKCS12", "changeit");
        }
        Path zip = tmp.resolve("with-truststore.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
             var files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                out.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return zip;
    }

    private static Path stagedTrustStore(Path tmp) {
        return tmp.resolve("stage/glassfish9").resolve(DOMAIN_CONFIG).resolve("cacerts.p12");
    }

    /** Rewrite the marker in {@code stage} as plugin 2.2.2 wrote it: without the install. */
    private static void forgetHome(Path stage) throws IOException {
        Path marker = stage.resolve(".staging-glassfish.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            props.load(in);
        }
        props.remove("home");
        try (OutputStream out = Files.newOutputStream(marker)) {
            props.store(out, null);
        }
    }
}
