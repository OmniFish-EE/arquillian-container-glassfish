/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.maven.staging.CertImport;
import ee.omnifish.glassfish.maven.staging.DistributionCoord;
import ee.omnifish.glassfish.maven.staging.OverlayCoord;
import ee.omnifish.glassfish.maven.staging.StageSpec;
import ee.omnifish.glassfish.maven.staging.StagingCommands;
import ee.omnifish.glassfish.maven.staging.TrustedCert;

class StagerTest {

    private static final String HOME = "<home>";

    @TempDir
    Path tmp;

    private final RecordingLog log = new RecordingLog();

    @Test
    void derivesHomeFromZipRootAndOverlaysIntoIt() throws Exception {
        Path zip = zip("glassfish9/glassfish/bin/asadmin");
        File stageDir = tmp.resolve("dist").toFile();

        StageSpec spec = new Stager(coord -> zip, log)
                .spec(distribution("9.0.0"), stageDir, false, null, null, null, false, CertImport.NONE, HOME);

        assertEquals(stageDir.toPath().resolve("glassfish9"), spec.home());
        assertEquals(spec.home().resolve("glassfish/modules"), spec.overlayTargetDir());
    }

    @Test
    void explicitHomeWins() throws Exception {
        File home = tmp.resolve("opt/glassfish9").toFile();

        StageSpec spec = new Stager(coord -> {
            throw new AssertionError("resolved " + coord.toGav());
        }, log).spec(distribution("9.0.0"), tmp.toFile(), false, home, null, null, false, CertImport.NONE, HOME);

        assertEquals(home.toPath(), spec.home());
    }

    @Test
    void distributionWithoutVersionFails() {
        Stager stager = new Stager(coord -> zip("glassfish9/x"), log);

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> stager.spec(new DistributionCoord(), tmp.toFile(), false, null, null, null, false, CertImport.NONE, HOME));

        assertThat(e.getMessage(), containsString("requires a <version>"));
    }

    @Test
    void zipWithSeveralRootsLeavesHomeUnset() throws Exception {
        Path zip = zip("glassfish9/bin/asadmin", "extras/readme.txt");

        StageSpec spec = new Stager(coord -> zip, log)
                .spec(distribution("9.0.0"), tmp.toFile(), false, null, null, null, false, CertImport.NONE, HOME);

        assertNull(spec.home());
        assertThat(log.warnings.get(0), containsString("set <home>"));
    }

    @Test
    void overlaysWithoutTargetNameTheCallersHomeParameter() {
        Stager stager = new Stager(coord -> zip("glassfish9/x"), log);
        List<OverlayCoord> overlays = List.of(new OverlayCoord("g", "a", "1", "jar", null, "a.jar"));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> stager.spec(null, tmp.toFile(), true, null, overlays, null, false, CertImport.NONE, "<poolSource> (gf.pool.source)"));

        assertThat(e.getMessage(), containsString("<overlayTargetDir> or <poolSource> (gf.pool.source)"));
    }

    /** Even when they agree, and also with overlaySkip, since the pom is wrong either way. */
    @Test
    void anOverlaySetsSkipOrApplyNotBoth() {
        Stager stager = new Stager(coord -> zip("glassfish9/x"), log);
        OverlayCoord overlay = new OverlayCoord("org.glassfish.soteria", "soteria", "1", "jar", null, "soteria.jar");
        overlay.setSkip(false);
        overlay.setApply(true);

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> stager.spec(null, tmp.toFile(), true, null, List.of(overlay), null, true, CertImport.NONE, HOME));

        assertThat(e.getMessage(), containsString("Overlay org.glassfish.soteria:soteria"));
        assertThat(e.getMessage(), containsString("sets both <skip> and <apply>; set one of them"));
    }

    @Test
    void certImportReachesTheSpec() throws Exception {
        File home = tmp.resolve("opt/glassfish9").toFile();
        CertImport certImport = new CertImport(List.of(new TrustedCert("idp", tmp.resolve("idp.pem").toFile())),
                null, null, false);

        StageSpec spec = new Stager(coord -> {
            throw new AssertionError("resolved " + coord.toGav());
        }, log).spec(null, tmp.toFile(), true, home, null, null, false, certImport, HOME);

        assertEquals(certImport, spec.certImport());
        assertTrue(spec.hasWork());
    }

    @Test
    void trustedCertsWithoutTruststoreNameTheCallersHomeParameter() {
        Stager stager = new Stager(coord -> zip("glassfish9/x"), log);
        CertImport certImport = new CertImport(List.of(new TrustedCert("idp", tmp.resolve("idp.pem").toFile())),
                null, null, false);

        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> stager.spec(
                null, tmp.toFile(), true, null, null, null, false, certImport, "<poolSource> (gf.pool.source)"));

        assertThat(e.getMessage(), containsString("<trustStore> or <poolSource> (gf.pool.source)"));
    }

    @Test
    void trustedCertNeedsAnAliasAndAFile() {
        Stager stager = new Stager(coord -> zip("glassfish9/x"), log);
        CertImport certImport = new CertImport(List.of(new TrustedCert(null, tmp.resolve("idp.pem").toFile())),
                null, null, false);

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> stager.spec(null, tmp.toFile(), true, tmp.toFile(), null, null, false, certImport, HOME));

        assertThat(e.getMessage(), containsString("needs an <alias> and a <file>"));
    }

    @Test
    void commandsReachTheSpec() throws Exception {
        Path zip = zip("glassfish9/glassfish/bin/asadmin");
        StagingCommands commands = new StagingCommands("create-file-user --groups g u", true);

        StageSpec spec = new Stager(coord -> zip, log).spec(distribution("9.0.0"), tmp.toFile(), false, null, null,
                null, false, CertImport.NONE, commands, HOME);

        assertEquals(commands, spec.commands());
    }

    @Test
    void aCommandWithAnUnclosedQuoteFails() {
        Stager stager = new Stager(coord -> zip("glassfish9/glassfish/bin/asadmin"), log);
        StagingCommands commands = new StagingCommands("set a=\"b", true);

        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> stager.spec(
                distribution("9.0.0"), tmp.toFile(), false, null, null, null, false, CertImport.NONE, commands, HOME));

        assertThat(e.getMessage(), containsString("Unclosed"));
    }

    @Test
    void commandsWithoutAnInstallRootNameTheCallersHomeParameter() {
        Stager stager = new Stager(coord -> zip("glassfish9/bin/asadmin", "extras/readme.txt"), log);
        StagingCommands commands = new StagingCommands("set a=b", true);

        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> stager.spec(
                distribution("9.0.0"), tmp.toFile(), false, null, null, null, false, CertImport.NONE, commands,
                "<poolSource> (gf.pool.source)"));

        assertThat(e.getMessage(), containsString("set <poolSource> (gf.pool.source)"));
    }

    private static DistributionCoord distribution(String version) {
        DistributionCoord dist = new DistributionCoord();
        dist.setVersion(version);
        return dist;
    }

    private Path zip(String... entries) throws IOException {
        Path zip = tmp.resolve("dist.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.closeEntry();
            }
        }
        return zip;
    }
}
