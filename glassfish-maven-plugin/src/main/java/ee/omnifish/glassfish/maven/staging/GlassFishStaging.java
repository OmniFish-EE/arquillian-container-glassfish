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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolve and unpack a GlassFish distribution zip, copy overlay artifacts into
 * the staged install, and import trusted certificates into its truststore.
 * Shared by {@code glassfish:provision} and the pool plugin's
 * {@code up}/{@code provision} goals.
 *
 * <p>Artifact lookup is delegated to a caller-supplied {@link ArtifactResolver},
 * which keeps this class free of Maven types; the mojos wire Aether in. It
 * logs through SLF4J, so its lines show up as regular Maven output.
 *
 * <p>Concurrency: {@link #stage} runs one caller at a time per stage
 * directory, so modules of a {@code -T} build that share one unpack it once.
 * The marker check makes the later runs no-ops; the unpack itself is not safe
 * to interleave.
 */
public final class GlassFishStaging {

    private static final Logger LOG = LoggerFactory.getLogger(GlassFishStaging.class);

    private static final String MARKER_FILE = ".staging-glassfish.properties";
    private static final String MARKER_KEY_DIST = "distribution";

    /** Where a domain's truststore lives, relative to the install root. */
    private static final Path DOMAIN_CONFIG = Paths.get("glassfish", "domains", "domain1", "config");

    /** The domain truststores to look for: current versions ship the first, older ones (6, 7.0.0) the second. */
    private static final List<String> DOMAIN_TRUST_STORES = List.of("cacerts.p12", "cacerts.jks");

    private static final ConcurrentMap<Path, Object> STAGE_DIR_LOCKS = new ConcurrentHashMap<>();

    /** Resolves a GAV to a local file (typically the artifact under {@code ~/.m2/repository}). */
    @FunctionalInterface
    public interface ArtifactResolver {
        Path resolve(ArtifactCoord coord) throws IOException;
    }

    private GlassFishStaging() {
    }

    /**
     * Run the staging steps. Either side is a no-op when its skip flag is set
     * or its inputs are empty. The unpack step is marker-guarded: it skips
     * when the staged install already records the requested dist GAV, and
     * wipes + re-unpacks when the GAV differs or the zip is newer than the
     * marker (a rebuilt or re-downloaded SNAPSHOT keeps its GAV). Overlays
     * are always re-copied (cheap; lets {@code -Dmojarra.version=…} take
     * effect without a wipe). Certificates are imported after them, on every
     * run too, into the domain's truststore unless the spec names another;
     * see {@link #importCerts}.
     */
    public static void stage(StageSpec spec, ArtifactResolver resolver) throws IOException {
        Path lockKey = spec.stageDir().toAbsolutePath().normalize();
        synchronized (STAGE_DIR_LOCKS.computeIfAbsent(lockKey, dir -> new Object())) {
            if (!spec.unpackSkip() && spec.distribution() != null) {
                unpackDistribution(spec, resolver);
            }
            if (!spec.overlaySkip() && !spec.overlays().isEmpty()) {
                copyOverlays(spec, resolver);
            }
            if (spec.certImport().hasWork()) {
                importCerts(spec.certImport(), spec.home());
            }
        }
    }

    private static void unpackDistribution(StageSpec spec, ArtifactResolver resolver) throws IOException {
        Path stageDir = spec.stageDir();
        Path marker = stageDir.resolve(MARKER_FILE);
        String wantedGav = spec.distribution().toGav();
        Path zip = resolver.resolve(spec.distribution());
        if (Files.exists(marker)) {
            String stagedGav = readMarker(marker, MARKER_KEY_DIST);
            boolean sameGav = wantedGav.equals(stagedGav);
            if (sameGav && !isNewer(zip, marker)) {
                LOG.debug("Dist already staged at " + stageDir + " (" + wantedGav + ")");
                return;
            }
            LOG.info(sameGav
                    ? "Re-staging dist (" + wantedGav + " changed since it was staged)"
                    : "Re-staging dist (was " + stagedGav + ", now " + wantedGav + ")");
            wipe(stageDir);
        }
        Files.createDirectories(stageDir);
        LOG.info("Unpacking " + wantedGav + " to " + stageDir);
        unzip(zip, stageDir);
        writeMarker(marker, MARKER_KEY_DIST, wantedGav);
    }

    /**
     * Whether {@code zip} was written after {@code marker}. The marker is
     * written last, after the unpack, so a newer zip was rebuilt
     * ({@code mvn install}) or downloaded since: the same check as the
     * dependency plugin's {@code overWriteIfNewer}.
     */
    private static boolean isNewer(Path zip, Path marker) throws IOException {
        return Files.getLastModifiedTime(zip).compareTo(Files.getLastModifiedTime(marker)) > 0;
    }

    /**
     * Name of the single top-level directory every entry of {@code zip} lives
     * under (e.g. {@code glassfish9} or {@code payara6}), or {@code null} if
     * the zip has several top-level entries or files at its root. Lets the
     * plugins derive the install root as {@code stageDir/<root>} without the
     * user hard-coding a directory name that the distribution dictates.
     *
     * <p>Reads only the zip's central directory, so it is cheap even for a
     * full distribution, and works before the unpack has run.
     */
    public static String singleRootDirectory(Path zip) throws IOException {
        String root = null;
        try (ZipFile zf = ZipFile.builder().setFile(zip.toFile()).get()) {
            var entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    return null; // file at the zip root, or an absolute-looking entry
                }
                String first = name.substring(0, slash);
                if (root == null) {
                    root = first;
                } else if (!root.equals(first)) {
                    return null;
                }
            }
        }
        return root;
    }

    private static void copyOverlays(StageSpec spec, ArtifactResolver resolver) throws IOException {
        Path target = spec.overlayTargetDir();
        Files.createDirectories(target);
        for (OverlayCoord overlay : spec.overlays()) {
            if (overlay.isSkip()) {
                LOG.info("Skipping overlay " + overlay.toGav() + " (skip=true)");
                continue;
            }
            Path source = resolver.resolve(overlay);
            String fileName = (overlay.getDestFileName() == null || overlay.getDestFileName().isEmpty())
                    ? source.getFileName().toString()
                    : overlay.getDestFileName();
            Path dest = target.resolve(fileName);
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Overlaid " + overlay.toGav() + " → " + dest);
        }
    }

    /**
     * Import the certificates into the truststore: the one {@code certImport}
     * names, else the domain's own under {@code home}, {@code cacerts.p12} or,
     * in older versions, {@code cacerts.jks}. The JDK probes the store type
     * from the file.
     *
     * <p>A certificate the truststore already holds under its alias is left
     * alone, and an unchanged truststore isn't written, so staging again
     * leaves it as it is. A changed one is written to a sibling file and moved
     * into place, so whoever clones the install meanwhile gets the old
     * truststore or the new one, and an interrupted build can't leave half of
     * it behind.
     */
    private static void importCerts(CertImport certImport, Path home) throws IOException {
        Path trustStore = (certImport.trustStore() != null) ? certImport.trustStore() : domainTrustStore(home);
        char[] password = certImport.password().toCharArray();
        KeyStore store = loadTrustStore(trustStore, password);
        boolean changed = false;
        for (TrustedCert trusted : certImport.certs()) {
            Certificate cert = readCertificate(trusted.getFile());
            try {
                if (cert.equals(store.getCertificate(trusted.getAlias()))) {
                    LOG.debug("Already trusted: " + trusted.getAlias() + " in " + trustStore);
                    continue;
                }
                store.setCertificateEntry(trusted.getAlias(), cert);
            } catch (KeyStoreException e) {
                throw new IOException("Could not import " + trusted.getFile() + " as '" + trusted.getAlias()
                        + "' into " + trustStore + ": " + e.getMessage(), e);
            }
            LOG.info("Trusting " + trusted.getFile() + " as '" + trusted.getAlias() + "' in " + trustStore);
            changed = true;
        }
        if (changed) {
            storeAtomically(store, trustStore, password);
        }
    }

    private static Path domainTrustStore(Path home) throws IOException {
        if (home == null) {
            throw new IOException("No install to find the truststore in; set the truststore");
        }
        Path config = home.resolve(DOMAIN_CONFIG);
        for (String name : DOMAIN_TRUST_STORES) {
            Path trustStore = config.resolve(name);
            if (Files.isRegularFile(trustStore)) {
                return trustStore;
            }
        }
        throw new IOException("Found no " + String.join(" or ", DOMAIN_TRUST_STORES) + " in " + config
                + "; set the truststore");
    }

    private static KeyStore loadTrustStore(Path trustStore, char[] password) throws IOException {
        if (!Files.isRegularFile(trustStore)) {
            throw new IOException("Truststore " + trustStore + " doesn't exist");
        }
        try {
            return KeyStore.getInstance(trustStore.toFile(), password);
        } catch (IOException | GeneralSecurityException e) {
            throw new IOException("Could not load truststore " + trustStore + ": " + e.getMessage(), e);
        }
    }

    /** The first certificate in a PEM or DER file. */
    private static Certificate readCertificate(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (CertificateException e) {
            throw new IOException("Could not read a certificate from " + file + ": " + e.getMessage(), e);
        }
    }

    private static void storeAtomically(KeyStore store, Path trustStore, char[] password) throws IOException {
        Path tmp = trustStore.resolveSibling(trustStore.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            store.store(out, password);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not write truststore " + trustStore + ": " + e.getMessage(), e);
        }
        try {
            Files.move(tmp, trustStore, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Still replaces the file rather than rewriting it in place, just not crash-safe.
            Files.move(tmp, trustStore, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Unzip preserving POSIX permissions. Uses commons-compress because
     * {@link java.util.zip.ZipFile} doesn't expose external file attributes,
     * which {@code asadmin}'s {@code +x} bit lives in.
     */
    private static void unzip(Path zip, Path targetDir) throws IOException {
        try (ZipFile zf = ZipFile.builder().setFile(zip.toFile()).get()) {
            var entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    throw new IOException("Zip entry escapes target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Files.createDirectories(resolved.getParent());
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                applyUnixMode(resolved, entry.getUnixMode());
            }
        }
    }

    private static void applyUnixMode(Path file, int unixMode) {
        if (unixMode == 0) {
            return;
        }
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        if ((unixMode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((unixMode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((unixMode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((unixMode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((unixMode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((unixMode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((unixMode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((unixMode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((unixMode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        try {
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows). Skip silently — Windows doesn't need +x.
        }
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var entries = Files.walk(dir)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    /* best-effort */
                }
            });
        }
    }

    private static String readMarker(Path marker, String key) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        return props.getProperty(key);
    }

    private static void writeMarker(Path marker, String key, String value) throws IOException {
        Properties props = new Properties();
        if (Files.exists(marker)) {
            try (InputStream in = Files.newInputStream(marker)) {
                props.load(in);
            }
        }
        props.setProperty(key, value);
        Files.createDirectories(marker.getParent());
        try (var out = Files.newOutputStream(marker)) {
            props.store(out, "GlassFish staging marker");
        }
    }
}
