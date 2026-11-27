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
package ee.omnifish.arquillian.container.glassfish.pool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import ee.omnifish.glassfish.admin.TrustStores;
import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;

/**
 * The GlassFish Embedded a pool's slots run, as {@code up} describes it in
 * the pool directory, in {@code embedded.properties}: its self-contained JAR,
 * or the install whose static shell runs it, the stage directory it runs
 * from, and the truststore its children get a copy of. The test JVMs read
 * it, so nothing more needs forwarding to them; without it, the pool runs
 * domains.
 *
 * <p>The truststore is a copy with GlassFish's master password, in the pool
 * directory, so the build's truststore password goes no further. The
 * description is published only once that copy is complete, and atomically,
 * so a JVM that reads it meanwhile gets the old one or the new one. Both are
 * published under the pool's {@code grow.lock}, so two JVMs that publish at
 * once, as the {@code glassfish-pool:run}s of two modules that share a pool,
 * take turns, and leave the description of one with that one's truststore.
 */
public final class EmbeddedPool {

    static final String FILE = "embedded.properties";

    /** The copy of the truststore, with GlassFish's master password, in the pool directory. */
    static final String TRUST_STORE = "embedded-cacerts.p12";

    /** How the children start, as {@code <embeddedMode>} names it. */
    static final String COMMAND_LINE = "command-line";

    private static final String EDITION = "edition";
    private static final String MODE = "mode";
    private static final String STAGE_DIR = "stageDir";
    private static final String JAR = "jar";
    private static final String HOME = "home";
    private static final String TRUST = "trustStore";

    private EmbeddedPool() {
    }

    /** Describe {@code glassfish}, of {@code edition}, as what the slots of the pool in {@code poolDir} run. */
    public static void write(Path poolDir, EmbeddedGlassFish glassfish, String edition) throws IOException {
        Files.createDirectories(poolDir);
        Properties description = new Properties();
        description.setProperty(EDITION, edition);
        description.setProperty(MODE, COMMAND_LINE);
        description.setProperty(STAGE_DIR, absolute(glassfish.stageDir()));
        if (glassfish.isJar()) {
            description.setProperty(JAR, absolute(glassfish.jar()));
        } else {
            description.setProperty(HOME, absolute(glassfish.home()));
        }
        PoolLocks.withGrowLock(poolDir, () -> {
            Path trustStore = poolDir.resolve(TRUST_STORE);
            if (glassfish.trustStore() != null) {
                TrustStores.copyCertificates(glassfish.trustStore(), glassfish.trustStorePassword().toCharArray(),
                        trustStore, TrustStores.MASTER_PASSWORD.toCharArray());
                description.setProperty(TRUST, absolute(trustStore));
            }
            publish(description, poolDir.resolve(FILE));
            if (glassfish.trustStore() == null) {
                // Only now: whoever read the old description may still copy it.
                Files.deleteIfExists(trustStore);
            }
            return null;
        });
    }

    /** Publish {@code description} as {@code file}, through a temporary file of its own. */
    private static void publish(Properties description, Path file) throws IOException {
        Path tmp = file.resolveSibling(FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                description.store(out, "The GlassFish Embedded the slots run, written by glassfish-pool:up");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Whether the slots of the pool in {@code poolDir} run GlassFish Embedded. */
    public static boolean exists(Path poolDir) {
        return Files.isRegularFile(poolDir.resolve(FILE));
    }

    /** The GlassFish Embedded the slots of the pool in {@code poolDir} run, or empty, when they run domains. */
    public static Optional<EmbeddedGlassFish> read(Path poolDir) throws IOException {
        Path file = poolDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        Properties description = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            description.load(in);
        }
        Path stageDir = path(description, STAGE_DIR);
        Path trustStore = description.containsKey(TRUST) ? path(description, TRUST) : null;
        String password = (trustStore == null) ? null : TrustStores.MASTER_PASSWORD;
        if (description.containsKey(JAR)) {
            return Optional.of(EmbeddedGlassFish.jar(stageDir, path(description, JAR), trustStore, password));
        }

        return Optional.of(EmbeddedGlassFish.install(stageDir, path(description, HOME), trustStore, password));
    }

    /** Describe no GlassFish Embedded: the slots of the pool in {@code poolDir} run domains. */
    public static void remove(Path poolDir) throws IOException {
        if (!exists(poolDir) && !Files.exists(poolDir.resolve(TRUST_STORE))) {
            return;
        }

        PoolLocks.withGrowLock(poolDir, () -> {
            Files.deleteIfExists(poolDir.resolve(FILE));
            Files.deleteIfExists(poolDir.resolve(TRUST_STORE));
            return null;
        });
    }

    private static Path path(Properties description, String key) throws IOException {
        String value = description.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IOException("The description of GlassFish Embedded in " + FILE + " has no " + key);
        }
        return Paths.get(value);
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
