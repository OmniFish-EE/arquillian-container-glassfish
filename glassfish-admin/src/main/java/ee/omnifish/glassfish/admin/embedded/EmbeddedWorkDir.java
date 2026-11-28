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

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.TrustStores;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * The working directory of one GlassFish Embedded child, which only that
 * child uses: {@code run-<pid>}, after the JVM that runs it, under a parent
 * such as {@code target/glassfish-embedded}. It holds a copy of the archive,
 * which the child deploys by its file name, and, for an install's static
 * shell, the domain directory the child runs on.
 *
 * <p>The child gets a directory of its own because Embedded takes over a
 * {@code glassfish-domain} directory it finds in its working directory, and
 * deletes it on exit, and because its {@code deploy} command splits an
 * archive's path where it has a space. The directory is removed when the run
 * ends; one a JVM that no longer runs left behind is removed by the next.
 *
 * <p>The child gets its own copy of the truststore, with GlassFish's master
 * password: GlassFish opens its truststore with that, whatever
 * {@code javax.net.ssl.trustStorePassword} says.
 *
 * <p>When its {@link EmbeddedPorts} move the IIOP and JMS ports, the domain
 * directory's {@code domain.xml} has them, and the self-contained JAR gets a
 * domain directory too, from its own {@code domain.xml}.
 *
 * <p>A JVM that hosts GlassFish itself gets one too ({@link #forHost}), named
 * after itself and its how-manieth GlassFish it is, {@code run-<pid>-<n>},
 * whose domain directory is the instance root it boots on, for either form,
 * and which takes the archives as they're deployed. As a JVM may host
 * GlassFish again, a directory of its own each time keeps a file the last
 * GlassFish left open, as on Windows, from failing the next.
 */
public final class EmbeddedWorkDir implements AutoCloseable {

    private static final Logger LOG = System.getLogger(EmbeddedWorkDir.class.getName());

    static final String PREFIX = "run-";

    /** How many GlassFishes this JVM hosted, which names their working directories. */
    private static final AtomicInteger HOSTED = new AtomicInteger();

    /** The domain directory of a child, relative to its working directory. */
    public static final String DOMAIN_DIR = "domain";

    /** The truststore of the JAR's child, relative to its working directory. */
    public static final String TRUST_STORE = "cacerts.p12";

    /** The password of the child's truststore: GlassFish's master password. */
    public static final String TRUST_STORE_PASSWORD = TrustStores.MASTER_PASSWORD;

    /** Embedded's own {@code domain.xml}, in the JAR and in an install's {@code modules/kernel.jar}. */
    static final String EMBEDDED_DOMAIN_XML = "org/glassfish/embed/domain.xml";

    private final Path dir;
    private final String archiveName;
    private final Path stageDir;

    private EmbeddedWorkDir(Path dir, String archiveName, Path stageDir) {
        this.dir = dir;
        this.archiveName = archiveName;
        this.stageDir = stageDir;
    }

    /** The working directory of this JVM's child under {@code parent}, with {@code archive} and what it runs on. */
    public static EmbeddedWorkDir create(Path parent, Path archive, EmbeddedGlassFish glassfish) throws IOException {
        return create(parent, archive, glassfish, EmbeddedPorts.standard());
    }

    /** As {@link #create(Path, Path, EmbeddedGlassFish)}, for a child on {@code ports}. */
    public static EmbeddedWorkDir create(Path parent, Path archive, EmbeddedGlassFish glassfish, EmbeddedPorts ports)
            throws IOException {
        return create(parent, ProcessHandle.current().pid(), archive, glassfish, ports);
    }

    /**
     * {@code run-<pid>} under {@code parent}, emptied, with a copy of
     * {@code archive}, and for the JAR, of its truststore, if it has one, as
     * {@link #TRUST_STORE}. For an install, it has its domain directory:
     * Embedded's own {@code domain.xml}, {@code domain1}'s
     * {@code keystore.p12}, which holds the HTTPS listener's key, a copy of
     * the install's truststore as {@code cacerts.p12}, and an empty
     * {@code docroot}. Package-private for unit tests.
     */
    static EmbeddedWorkDir create(Path parent, long pid, Path archive, EmbeddedGlassFish glassfish,
            EmbeddedPorts ports) throws IOException {
        String archiveName = archive.getFileName().toString();
        if (archiveName.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException("GlassFish Embedded can't deploy " + archiveName + ": its deploy command splits a "
                    + "file name where it has a space. Give the artifact a <finalName> without one.");
        }

        Files.createDirectories(parent);
        removeStale(parent, pid);
        Path dir = parent.resolve(PREFIX + pid);
        delete(dir);
        Files.createDirectories(dir);
        Files.copy(archive, dir.resolve(archiveName));
        if (!glassfish.isJar()) {
            addDomain(dir.resolve(DOMAIN_DIR), glassfish, ports, null, null);
        } else {
            if (glassfish.trustStore() != null) {
                copyTrustStore(glassfish, dir.resolve(TRUST_STORE));
            }
            if (ports.movesOthers()) {
                addDomain(dir.resolve(DOMAIN_DIR), glassfish, ports, null, null);
            }
        }

        return new EmbeddedWorkDir(dir, archiveName, glassfish.stageDir());
    }

    /**
     * The working directory of the GlassFish this JVM hosts, under
     * {@code parent}: {@code run-<pid>}, emptied, with the domain directory
     * GlassFish boots on as its instance root, and for the JAR, a copy of its
     * truststore, if it has one, as {@link #TRUST_STORE}, which the host
     * selects with {@code javax.net.ssl.trustStore}.
     *
     * @param instanceRoot an instance root to start the domain directory
     *        from, which is copied, or {@code null}
     * @param domainXml a {@code domain.xml} to run on, which is copied, or
     *        {@code null} for the instance root's, or else Embedded's own
     */
    public static EmbeddedWorkDir forHost(Path parent, EmbeddedGlassFish glassfish, EmbeddedPorts ports,
            Path instanceRoot, Path domainXml) throws IOException {
        return forHost(parent, ProcessHandle.current().pid(), glassfish, ports, instanceRoot, domainXml);
    }

    /** Package-private for unit tests. */
    static EmbeddedWorkDir forHost(Path parent, long pid, EmbeddedGlassFish glassfish, EmbeddedPorts ports,
            Path instanceRoot, Path domainXml) throws IOException {
        Files.createDirectories(parent);
        removeStale(parent, pid);
        Path dir = parent.resolve(PREFIX + pid + "-" + HOSTED.incrementAndGet());
        delete(dir);
        Files.createDirectories(dir);
        if (glassfish.isJar() && glassfish.trustStore() != null) {
            copyTrustStore(glassfish, dir.resolve(TRUST_STORE));
        }
        addDomain(dir.resolve(DOMAIN_DIR), glassfish, ports, instanceRoot, domainXml);

        return new EmbeddedWorkDir(dir, null, glassfish.stageDir());
    }

    /**
     * A domain directory: Embedded's own {@code domain.xml}, or that of an
     * instance root or a {@code domain.xml} of the user's own, with the IIOP
     * and JMS ports of {@code ports} when it moves them, and a
     * {@code docroot}. An install's also has {@code domain1}'s keystore,
     * unless the instance root brings one, and a copy of the truststore; the
     * JAR keeps selecting its truststore with {@code javax.net.ssl.trustStore}.
     *
     * @param instanceRoot an instance root, which is copied first, or {@code null}
     * @param userDomainXml a {@code domain.xml} to copy in, or {@code null}
     */
    private static void addDomain(Path domain, EmbeddedGlassFish glassfish, EmbeddedPorts ports, Path instanceRoot,
            Path userDomainXml) throws IOException {
        if (instanceRoot != null) {
            copyTree(instanceRoot, domain);
        }
        Path config = Files.createDirectories(domain.resolve("config"));
        Files.createDirectories(domain.resolve("docroot"));
        Path domainXml = config.resolve("domain.xml");
        if (userDomainXml != null) {
            Files.copy(userDomainXml, domainXml, StandardCopyOption.REPLACE_EXISTING);
        } else if (!Files.exists(domainXml)) {
            copyEmbeddedDomainXml(glassfish, domainXml);
        }
        if (ports.movesOthers()) {
            DomainXmlEditor.setIiopAndJmsPorts(domainXml, ports.iiop(), ports.iiopSsl(), ports.iiopMutualAuth(),
                    ports.jms());
        }
        if (!glassfish.isJar()) {
            Path keyStore = config.resolve("keystore.p12");
            if (!Files.exists(keyStore)) {
                Files.copy(glassfish.domainConfig().resolve("keystore.p12"), keyStore);
            }
            copyTrustStore(glassfish, config.resolve("cacerts.p12"));
        }
    }

    /** Embedded's own {@code domain.xml}, from the JAR, or from the install's {@code kernel.jar}. */
    private static void copyEmbeddedDomainXml(EmbeddedGlassFish glassfish, Path domainXml) throws IOException {
        Path source = glassfish.isJar() ? glassfish.jar() : glassfish.home().resolve("glassfish/modules/kernel.jar");
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry(EMBEDDED_DOMAIN_XML);
            if (entry == null) {
                throw new IOException(source + " has no " + EMBEDDED_DOMAIN_XML + ", so this GlassFish can't run "
                        + "Embedded" + (glassfish.isJar() ? " on ports of its own" : " from its static shell"));
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, domainXml);
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> entries = Files.walk(source)) {
            paths = entries.collect(Collectors.toList());
        }
        for (Path path : paths) {
            Path copy = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(copy);
            } else {
                Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Copy the certificates of {@code glassfish}'s truststore to {@code target}, with {@link #TRUST_STORE_PASSWORD}. */
    private static void copyTrustStore(EmbeddedGlassFish glassfish, Path target) throws IOException {
        TrustStores.copyCertificates(glassfish.trustStore(), glassfish.trustStorePassword().toCharArray(), target,
                TRUST_STORE_PASSWORD.toCharArray());
    }

    /**
     * Remove the working directories under {@code parent} of JVMs other than
     * {@code pid} that no longer run, and those of GlassFishes that
     * {@code pid} hosted before, as far as they can be removed.
     */
    private static void removeStale(Path parent, long pid) throws IOException {
        List<Path> dirs;
        try (Stream<Path> entries = Files.list(parent)) {
            dirs = entries.filter(entry -> entry.getFileName().toString().startsWith(PREFIX))
                    .collect(Collectors.toList());
        }
        for (Path dir : dirs) {
            String name = dir.getFileName().toString().substring(PREFIX.length());
            int dash = name.indexOf('-');
            long owner;
            try {
                owner = Long.parseLong((dash < 0) ? name : name.substring(0, dash));
            } catch (NumberFormatException e) {
                continue;
            }
            if (owner != pid && !ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false)) {
                delete(dir);
            } else if (owner == pid && dash >= 0) {
                try {
                    delete(dir);
                } catch (IOException e) {
                    LOG.log(DEBUG, "Could not remove " + dir + " yet: " + e.getMessage());
                }
            }
        }
    }

    public Path dir() {
        return dir;
    }

    /** The archive's file name, by which the child deploys it, or {@code null} for a host's. */
    public String archiveName() {
        return archiveName;
    }

    /** The domain directory, which a host boots on as its instance root. */
    public Path domainDir() {
        return dir.resolve(DOMAIN_DIR);
    }

    /** The stage directory the child runs from, which records it while it runs. */
    public Path stageDir() {
        return stageDir;
    }

    /**
     * Remove this directory. What can't be removed yet, as on Windows right
     * after the child ended, the next run removes.
     */
    @Override
    public void close() {
        try {
            delete(dir);
        } catch (IOException e) {
            LOG.log(DEBUG, "Could not remove " + dir + ": " + e.getMessage());
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        List<Path> paths;
        try (Stream<Path> entries = Files.walk(dir)) {
            paths = entries.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
