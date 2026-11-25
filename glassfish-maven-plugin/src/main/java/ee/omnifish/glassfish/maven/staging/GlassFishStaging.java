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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolve and unpack a GlassFish distribution zip, copy overlay artifacts into
 * the staged install, and import trusted certificates into its truststore.
 * Shared by {@code glassfish:provision} and the pool plugin's
 * {@code up}/{@code provision} goals. A GlassFish Embedded JAR is copied
 * instead of unpacked, with its certificates in a truststore of its own.
 *
 * <p>Artifact lookup is delegated to a caller-supplied {@link ArtifactResolver},
 * which keeps this class free of Maven types; the mojos wire Aether in. It
 * logs through SLF4J, so its lines show up as regular Maven output.
 *
 * <p>When it unpacks the distribution, staging owns the stage directory. A
 * marker file in it records a fingerprint of everything staging puts there,
 * and a change to any of it stages the directory afresh; see {@link #stage}.
 *
 * <p>Concurrency: {@link #stage} runs one caller at a time per stage
 * directory, so modules of a {@code -T} build that share one stage it once.
 * The fingerprint check makes the later runs no-ops; staging itself is not
 * safe to interleave.
 */
public final class GlassFishStaging {

    private static final Logger LOG = LoggerFactory.getLogger(GlassFishStaging.class);

    private static final String MARKER_FILE = ".staging-glassfish.properties";
    private static final String MARKER_KEY_DIST = "distribution";
    private static final String MARKER_KEY_FINGERPRINT = "fingerprint";
    private static final String MARKER_KEY_HOME = "home";

    /** Where a domain's configuration, and its truststore, live, relative to the install root. */
    static final Path DOMAIN_CONFIG = Paths.get("glassfish", "domains", "domain1", "config");

    /** The domain truststores to look for: current versions ship the first, older ones (6, 7.0.0) the second. */
    private static final List<String> DOMAIN_TRUST_STORES = List.of("cacerts.p12", "cacerts.jks");

    /**
     * The truststore staged next to a GlassFish Embedded JAR, in the stage
     * directory, when certificates are imported without a truststore of
     * their own: the JDK's trust anchors, and the certificates.
     */
    public static final String EMBEDDED_TRUST_STORE = "cacerts.p12";

    private static final ConcurrentMap<Path, Object> STAGE_DIR_LOCKS = new ConcurrentHashMap<>();

    /** Resolves a GAV to a local file (typically the artifact under {@code ~/.m2/repository}). */
    @FunctionalInterface
    public interface ArtifactResolver {
        Path resolve(ArtifactCoord coord) throws IOException;
    }

    /**
     * Runs the {@link StagingCommands} on the staged install at {@code home},
     * on a start of the domain: a brief one of their own, unless the caller
     * starts it anyway.
     */
    @FunctionalInterface
    public interface CommandRunner {
        void run(Path home, StagingCommands commands) throws IOException;
    }

    /** Runs the commands on a brief start of their own, on free ports. */
    public static final CommandRunner BRIEF_START = BriefStart::runCommands;

    private GlassFishStaging() {
    }

    /**
     * Run the staging steps: unpack the distribution, copy the overlays,
     * import the certificates, and run the commands on a brief start. Each
     * is a no-op when its skip flag is set or its inputs are empty.
     *
     * <p>When the distribution is unpacked, the steps are one unit, recorded
     * by a fingerprint of their inputs in the stage directory's marker. A
     * matching fingerprint leaves the staged install alone. A changed one, or
     * a marker without one, stages the directory afresh: wipe it, unpack
     * again, copy the overlays, import the certificates, run the commands,
     * and write the fingerprint last. So a rebuilt zip or overlay, and an
     * overlay, certificate or command added, changed or removed, all take
     * effect, and a staging that didn't finish is redone, after stopping a
     * server its brief start may have left running. If that server doesn't
     * stop, staging fails, and leaves the directory as it is. A non-empty
     * directory without a marker is left alone, since it may be an install
     * this plugin didn't unpack.
     *
     * <p>Without the unpack, the install isn't staging's to own, so overlays
     * and certificates are applied to it on every run. That install may be
     * one staged here before, which its fingerprint then no longer describes,
     * so the fingerprint is dropped, and the next staging with the unpack
     * starts afresh.
     */
    public static void stage(StageSpec spec, ArtifactResolver resolver) throws IOException {
        stage(spec, resolver, BRIEF_START);
    }

    /** {@link #stage(StageSpec, ArtifactResolver)}, with {@code commandRunner} running the commands. */
    public static void stage(StageSpec spec, ArtifactResolver resolver, CommandRunner commandRunner)
            throws IOException {
        Path lockKey = spec.stageDir().toAbsolutePath().normalize();
        synchronized (STAGE_DIR_LOCKS.computeIfAbsent(lockKey, dir -> new Object())) {
            if (!spec.unpackSkip() && spec.distribution() != null) {
                stageOwned(spec, resolver, commandRunner);
            } else if ((!spec.overlaySkip() && !spec.overlays().isEmpty()) || spec.certImport().hasWork()) {
                forgetFingerprint(spec.stageDir().resolve(MARKER_FILE));
                applyOverlaysAndCerts(spec, resolver);
            }
        }
    }

    private static void stageOwned(StageSpec spec, ArtifactResolver resolver, CommandRunner commandRunner)
            throws IOException {
        Path stageDir = spec.stageDir();
        Path marker = stageDir.resolve(MARKER_FILE);
        String gav = spec.distribution().toGav();
        Path zip = resolver.resolve(spec.distribution());
        String fingerprint = fingerprint(spec, zip, resolver);
        if (Files.exists(marker)) {
            String stagedFingerprint = readMarker(marker, MARKER_KEY_FINGERPRINT);
            if (fingerprint.equals(stagedFingerprint)) {
                if (stagedHome(stageDir) == null && spec.home() != null) {
                    // Written by plugin 2.2.2, which didn't record the install.
                    writeMarker(marker, gav, spec.home(), fingerprint);
                }
                LOG.debug("Already staged at " + stageDir + " (" + gav + ")");
                return;
            }

            // The install staged there before: another distribution unpacks to another directory.
            Path stagedHome = stagedHome(stageDir);
            if (stagedHome == null) {
                stagedHome = spec.home();
            }
            if (stagedFingerprint == null) {
                LOG.info("Re-staging " + stageDir + ": its last staging didn't finish");
                BriefStart.stopLeftover(stagedHome);
            } else {
                Optional<GlassFishDomain> running = runningDomain(stageDir, stagedHome);
                if (running.isPresent()) {
                    // Started by glassfish:start: it's not staging's to stop, and wiping it would pull the install
                    // out from under it.
                    throw new IOException("The inputs of " + stageDir + " changed, but " + GlassFishDomain.DOMAIN
                            + " runs from " + running.get().home() + ". Stop it with glassfish:stop, then build "
                            + "again.");
                }
                if (EmbeddedProcesses.runningFrom(stageDir).isPresent()) {
                    // Started by glassfish:run, in another Maven.
                    throw new IOException("The inputs of " + stageDir + " changed, but GlassFish Embedded runs from "
                            + "it. End that glassfish:run, then build again.");
                }
                LOG.info("Re-staging " + stageDir + ": its inputs changed");
            }
        } else if (!isEmpty(stageDir)) {
            throw new IOException(stageDir + " isn't empty, and this plugin didn't stage it. Empty it, or point "
                    + "the stage directory elsewhere.");
        }

        // A marker without a fingerprint claims the directory, so a staging
        // interrupted from here on is redone by the next run.
        Files.createDirectories(stageDir);
        writeMarker(marker, gav, spec.home(), null);
        wipeAllBut(stageDir, marker);
        if (Edition.isJar(spec.distribution())) {
            Path jar = stagedJar(stageDir, spec.distribution());
            LOG.info("Copying " + gav + " to " + jar);
            Files.copy(zip, jar);
            if (spec.certImport().hasWork() && spec.certImport().trustStore() == null) {
                writeJdkTrustStore(stageDir.resolve(EMBEDDED_TRUST_STORE), spec.certImport().password().toCharArray());
            }
        } else {
            LOG.info("Unpacking " + gav + " to " + stageDir);
            unzip(zip, stageDir);
        }
        applyOverlaysAndCerts(spec, resolver);
        if (spec.commands().hasWork()) {
            commandRunner.run(spec.home(), spec.commands());
        }
        writeMarker(marker, gav, spec.home(), fingerprint);
    }

    /**
     * The install root staged in {@code stageDir}, as its marker records it,
     * or {@code null} without one. It's where a domain {@code glassfish:start}
     * started runs from, also after the pom names another distribution, which
     * would unpack to another directory.
     */
    public static Path stagedHome(Path stageDir) {
        Path marker = stageDir.resolve(MARKER_FILE);
        String home = Files.exists(marker) ? readMarker(marker, MARKER_KEY_HOME) : null;
        return (home == null) ? null : Paths.get(home);
    }

    /**
     * Where staging copies the GlassFish Embedded JAR {@code jar}:
     * {@code stageDir/<artifactId>-<version>[-<classifier>].jar}, whatever
     * the file in the local repository is called, as for a snapshot.
     */
    public static Path stagedJar(Path stageDir, ArtifactCoord jar) {
        String classifier = (jar.getClassifier() == null || jar.getClassifier().isEmpty()) ? ""
                : "-" + jar.getClassifier();
        return stageDir.resolve(jar.getArtifactId() + "-" + jar.getVersion() + classifier + ".jar");
    }

    /**
     * {@code domain1} of the install staged in {@code stageDir}, if it runs:
     * by the {@code config/pid} of {@code stagedHome}, or by the command line
     * of a server of any install there, as for a marker plugin 2.2.2 wrote,
     * which doesn't record the install.
     */
    private static Optional<GlassFishDomain> runningDomain(Path stageDir, Path stagedHome) {
        if (stagedHome != null) {
            GlassFishDomain staged = new GlassFishDomain(stagedHome);
            if (staged.isRunning()) {
                return Optional.of(staged);
            }
        }

        return GlassFishDomain.runningIn(stageDir);
    }

    private static void applyOverlaysAndCerts(StageSpec spec, ArtifactResolver resolver) throws IOException {
        if (!spec.overlaySkip() && !spec.overlays().isEmpty()) {
            copyOverlays(spec, resolver);
        }
        if (spec.certImport().hasWork()) {
            importCerts(spec.certImport(), trustStore(spec));
        }
    }

    /**
     * The truststore the certificates go into: the one {@code certImport}
     * names, else, next to a GlassFish Embedded JAR, {@link #EMBEDDED_TRUST_STORE},
     * else the domain's own under {@code home}, {@code cacerts.p12} or, in
     * older versions, {@code cacerts.jks}.
     */
    private static Path trustStore(StageSpec spec) throws IOException {
        if (spec.certImport().trustStore() != null) {
            return spec.certImport().trustStore();
        }
        if (Edition.isJar(spec.distribution())) {
            return spec.stageDir().resolve(EMBEDDED_TRUST_STORE);
        }

        return domainTrustStore(spec.home());
    }

    /**
     * A fingerprint of what staging puts into the directory: the distribution
     * and its zip, the install root, the overlays and their files, the
     * certificates and their truststore, and the commands, with whether a
     * failed one fails the staging. The install root counts because the
     * default truststore is found from it. Zips and jars are identified by
     * size and modification time, so a rebuilt one counts as changed.
     * Certificates are small, so their content counts, whatever their
     * timestamps.
     */
    private static String fingerprint(StageSpec spec, Path zip, ArtifactResolver resolver) throws IOException {
        List<String> inputs = new ArrayList<>();
        inputs.add("distribution " + spec.distribution().toGav() + " " + identity(zip));
        inputs.add("home " + spec.home());
        if (!spec.overlaySkip() && !spec.overlays().isEmpty()) {
            inputs.add("overlays into " + spec.overlayTargetDir());
            for (OverlayCoord overlay : spec.overlays()) {
                inputs.add("overlay " + overlay.toGav() + " as " + overlay.getDestFileName()
                        + (overlay.isSkipped() ? " skipped" : " " + identity(resolver.resolve(overlay))));
            }
        }
        CertImport certImport = spec.certImport();
        if (certImport.hasWork()) {
            inputs.add("truststore " + certImport.trustStore() + " " + certImport.password());
            if (certImport.trustStore() == null && Edition.isJar(spec.distribution())) {
                Path jdkTrustStore = jdkTrustStore();
                inputs.add("jdk truststore " + jdkTrustStore + " " + identity(jdkTrustStore));
            }
            for (TrustedCert cert : certImport.certs()) {
                inputs.add("cert " + cert.getAlias() + " " + sha256(Files.readAllBytes(cert.getFile().toPath())));
            }
        }
        StagingCommands commands = spec.commands();
        if (commands.hasWork()) {
            inputs.add("commands fail on error " + commands.failOnError());
            for (String command : commands.lines()) {
                inputs.add("command " + command);
            }
        }

        return sha256(String.join("\n", inputs).getBytes(StandardCharsets.UTF_8));
    }

    private static String identity(Path file) throws IOException {
        return Files.size(file) + " bytes, modified " + Files.getLastModifiedTime(file).toMillis();
    }

    private static String sha256(byte[] bytes) {
        try {
            return String.format("%064x", new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK has SHA-256", e);
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return !Files.exists(dir);
        }
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
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
            if (overlay.isSkipped()) {
                LOG.info("Skipping overlay " + overlay.toGav()
                        + (Boolean.FALSE.equals(overlay.getApply()) ? " (apply=false)" : " (skip=true)"));
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

    /** The trust anchors of the JDK that runs Maven, which also runs GlassFish Embedded's child. */
    private static Path jdkTrustStore() {
        return Paths.get(System.getProperty("java.home"), "lib", "security", "cacerts");
    }

    /**
     * Write the JDK's trust anchors to {@code trustStore}, as PKCS12 with
     * {@code password}, for a GlassFish Embedded JAR: its domain has no
     * truststore, so without one, it trusts what the JDK trusts. The JDK's
     * store is read without its password, which only checks its integrity.
     */
    private static void writeJdkTrustStore(Path trustStore, char[] password) throws IOException {
        Path jdkTrustStore = jdkTrustStore();
        try {
            KeyStore jdk = KeyStore.getInstance(jdkTrustStore.toFile(), (char[]) null);
            KeyStore copy = KeyStore.getInstance("PKCS12");
            copy.load(null, null);
            for (String alias : Collections.list(jdk.aliases())) {
                if (jdk.isCertificateEntry(alias)) {
                    copy.setCertificateEntry(alias, jdk.getCertificate(alias));
                }
            }
            storeAtomically(copy, trustStore, password);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not copy the JDK's trust anchors from " + jdkTrustStore + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * Import the certificates into {@code trustStore}. The JDK probes the
     * store type from the file.
     *
     * <p>A certificate the truststore already holds under its alias is left
     * alone, and an unchanged truststore isn't written, so staging again
     * leaves it as it is. A changed one is written to a sibling file and moved
     * into place, so whoever clones the install meanwhile gets the old
     * truststore or the new one, and an interrupted build can't leave half of
     * it behind.
     */
    private static void importCerts(CertImport certImport, Path trustStore) throws IOException {
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

    /** The truststore of {@code home}'s {@code domain1}: {@code cacerts.p12}, or in older versions, {@code cacerts.jks}. */
    public static Path domainTrustStore(Path home) throws IOException {
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

    /**
     * Delete everything under {@code dir} except {@code keep}. Unpacking over
     * leftovers would mix two installs, so a file that can't be deleted, for
     * example because a GlassFish running from the install keeps it open,
     * fails the staging.
     */
    private static void wipeAllBut(Path dir, Path keep) throws IOException {
        List<Path> paths;
        try (var entries = Files.walk(dir)) {
            paths = entries.filter(path -> !path.equals(dir) && !path.equals(keep))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new IOException("Could not delete " + path + " to stage " + dir + " afresh: " + e, e);
            }
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

    /**
     * Drop the fingerprint from {@code marker}, if there is one. The marker
     * stays, so the directory stays staging's, to be staged afresh.
     */
    private static void forgetFingerprint(Path marker) throws IOException {
        if (Files.exists(marker) && readMarker(marker, MARKER_KEY_FINGERPRINT) != null) {
            String home = readMarker(marker, MARKER_KEY_HOME);
            writeMarker(marker, readMarker(marker, MARKER_KEY_DIST), (home == null) ? null : Paths.get(home), null);
        }
    }

    /** Write the marker; a {@code null} fingerprint marks a staging that hasn't finished. */
    private static void writeMarker(Path marker, String gav, Path home, String fingerprint) throws IOException {
        Properties props = new Properties();
        if (gav != null) {
            props.setProperty(MARKER_KEY_DIST, gav);
        }
        if (home != null) {
            props.setProperty(MARKER_KEY_HOME, home.toAbsolutePath().normalize().toString());
        }
        if (fingerprint != null) {
            props.setProperty(MARKER_KEY_FINGERPRINT, fingerprint);
        }
        try (var out = Files.newOutputStream(marker)) {
            props.store(out, "GlassFish staging marker");
        }
    }
}
