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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The working directory of one GlassFish Embedded child, which only that
 * child uses: {@code run-<pid>}, after the Maven that runs it, under a
 * parent such as {@code target/glassfish-embedded}. It holds a copy of the
 * archive, which the child deploys by its file name, and, for an install's
 * static shell, the domain directory the child runs on.
 *
 * <p>The child gets a directory of its own because Embedded takes over a
 * {@code glassfish-domain} directory it finds in its working directory, and
 * deletes it on exit, and because its {@code deploy} command splits an
 * archive's path where it has a space. The directory is removed when the run
 * ends; one a Maven that no longer runs left behind is removed by the next.
 *
 * <p>The child gets its own copy of the truststore, with GlassFish's master
 * password: GlassFish opens its truststore with that, whatever
 * {@code javax.net.ssl.trustStorePassword} says.
 *
 * <p>While the child runs, the stage directory it runs from names it, so
 * staging leaves that directory alone, also where the system doesn't say
 * what a process runs, as on Windows.
 */
public final class EmbeddedWorkDir implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedWorkDir.class);

    static final String PREFIX = "run-";

    /** The domain directory of an install's child, relative to its working directory. */
    public static final String DOMAIN_DIR = "domain";

    /** The truststore of the JAR's child, relative to its working directory. */
    public static final String TRUST_STORE = "cacerts.p12";

    /** The password of the child's truststore: GlassFish's master password. */
    public static final String TRUST_STORE_PASSWORD = CertImport.DEFAULT_PASSWORD;

    /** Embedded's own {@code domain.xml}, in the install's {@code modules/kernel.jar}. */
    static final String EMBEDDED_DOMAIN_XML = "org/glassfish/embed/domain.xml";

    private final Path dir;
    private final String archiveName;
    private final Path stageDir;

    private volatile ProcessHandle child;

    private EmbeddedWorkDir(Path dir, String archiveName, Path stageDir) {
        this.dir = dir;
        this.archiveName = archiveName;
        this.stageDir = stageDir;
    }

    /** The working directory of this Maven's child under {@code parent}, with {@code archive} and what it runs on. */
    public static EmbeddedWorkDir create(Path parent, Path archive, EmbeddedGlassFish glassfish) throws IOException {
        return create(parent, ProcessHandle.current().pid(), archive, glassfish);
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
    static EmbeddedWorkDir create(Path parent, long pid, Path archive, EmbeddedGlassFish glassfish)
            throws IOException {
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
            addDomain(dir.resolve(DOMAIN_DIR), glassfish);
        } else if (glassfish.trustStore() != null) {
            copyTrustStore(glassfish, dir.resolve(TRUST_STORE));
        }

        return new EmbeddedWorkDir(dir, archiveName, glassfish.stageDir());
    }

    private static void addDomain(Path domain, EmbeddedGlassFish glassfish) throws IOException {
        Path config = Files.createDirectories(domain.resolve("config"));
        Files.createDirectories(domain.resolve("docroot"));
        Path kernel = glassfish.home().resolve("glassfish/modules/kernel.jar");
        try (ZipFile zip = new ZipFile(kernel.toFile())) {
            ZipEntry domainXml = zip.getEntry(EMBEDDED_DOMAIN_XML);
            if (domainXml == null) {
                throw new IOException(kernel + " has no " + EMBEDDED_DOMAIN_XML + ", so this GlassFish can't run "
                        + "Embedded from its static shell");
            }
            try (InputStream in = zip.getInputStream(domainXml)) {
                Files.copy(in, config.resolve("domain.xml"));
            }
        }
        Files.copy(glassfish.home().resolve(GlassFishStaging.DOMAIN_CONFIG).resolve("keystore.p12"),
                config.resolve("keystore.p12"));
        copyTrustStore(glassfish, config.resolve("cacerts.p12"));
    }

    /** Copy the certificates of {@code glassfish}'s truststore to {@code target}, with {@link #TRUST_STORE_PASSWORD}. */
    private static void copyTrustStore(EmbeddedGlassFish glassfish, Path target) throws IOException {
        GlassFishStaging.copyCertificates(glassfish.trustStore(), glassfish.trustStorePassword().toCharArray(), target,
                TRUST_STORE_PASSWORD.toCharArray());
    }

    /** Remove the working directories under {@code parent} of Mavens other than {@code pid} that no longer run. */
    private static void removeStale(Path parent, long pid) throws IOException {
        List<Path> dirs;
        try (Stream<Path> entries = Files.list(parent)) {
            dirs = entries.filter(entry -> entry.getFileName().toString().startsWith(PREFIX))
                    .collect(Collectors.toList());
        }
        for (Path dir : dirs) {
            long owner;
            try {
                owner = Long.parseLong(dir.getFileName().toString().substring(PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (owner != pid && !ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false)) {
                delete(dir);
            }
        }
    }

    public Path dir() {
        return dir;
    }

    /** The archive's file name, by which the child deploys it. */
    public String archiveName() {
        return archiveName;
    }

    /** Name {@code child}, which runs in this directory, in the stage directory it runs from, until {@link #close}. */
    public void runs(ProcessHandle child) throws IOException {
        this.child = child;
        EmbeddedProcesses.record(stageDir, child);
    }

    /**
     * Remove the child's name from the stage directory, once it ended, and
     * this directory. What can't be removed yet, as on Windows right after
     * the child ended, the next run removes.
     */
    @Override
    public void close() {
        if (child != null) {
            try {
                EmbeddedProcesses.forget(stageDir, child);
            } catch (IOException e) {
                // The name of a child that ended doesn't hold staging up.
                LOG.debug("Could not remove the name of GlassFish Embedded's child from " + stageDir + ": "
                        + e.getMessage());
            }
        }
        try {
            delete(dir);
        } catch (IOException e) {
            LOG.debug("Could not remove " + dir + ": " + e.getMessage());
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
