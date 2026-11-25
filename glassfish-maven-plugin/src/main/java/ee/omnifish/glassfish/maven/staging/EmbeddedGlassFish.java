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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The GlassFish Embedded that staging provided: its self-contained JAR, or an
 * install whose static shell runs Embedded with the install's modules, as
 * when a module is overlaid. Either runs GlassFish Embedded's own main class,
 * with the same options.
 */
public final class EmbeddedGlassFish {

    /** The static shell of an install, relative to its root. */
    static final String STATIC_SHELL = "glassfish/lib/embedded/glassfish-embedded-static-shell.jar";

    private final Path jar;
    private final Path home;
    private final Path trustStore;
    private final String trustStorePassword;

    private EmbeddedGlassFish(Path jar, Path home, Path trustStore, String trustStorePassword) {
        this.jar = jar;
        this.home = home;
        this.trustStore = trustStore;
        this.trustStorePassword = trustStorePassword;
    }

    /**
     * The self-contained JAR. Its domain has no truststore, so the child
     * selects {@code trustStore} with {@code javax.net.ssl.trustStore}.
     *
     * @param trustStore the truststore with the imported certificates, or
     *        {@code null} for the JDK's
     */
    public static EmbeddedGlassFish jar(Path jar, Path trustStore, String trustStorePassword) {
        return new EmbeddedGlassFish(jar, null, trustStore, trustStorePassword);
    }

    /**
     * An install, run through its static shell. GlassFish reads the
     * truststore of its instance, so {@code trustStore} is copied there.
     *
     * @param trustStore the truststore for the instance, such as {@code domain1}'s
     */
    public static EmbeddedGlassFish install(Path home, Path trustStore) {
        return new EmbeddedGlassFish(null, home, trustStore, null);
    }

    public boolean isJar() {
        return jar != null;
    }

    /** The self-contained JAR, or {@code null} for an install. */
    public Path jar() {
        return jar;
    }

    /** The install root, or {@code null} for the self-contained JAR. */
    public Path home() {
        return home;
    }

    /** The truststore the child uses, or {@code null} for the JDK's. */
    public Path trustStore() {
        return trustStore;
    }

    /** The password of the JAR's {@link #trustStore}. */
    public String trustStorePassword() {
        return trustStorePassword;
    }

    /** The JAR whose main class runs GlassFish Embedded: the self-contained JAR, or the install's static shell. */
    public Path launcher() {
        return isJar() ? jar : home.resolve(STATIC_SHELL);
    }

    /**
     * Fail unless the {@link #launcher} has a main class: GlassFish Embedded
     * runs an application from the command line since 7.0.18.
     */
    public void requireMainClass() throws IOException {
        Path launcher = launcher();
        if (!Files.isRegularFile(launcher)) {
            throw new IOException("Found no " + launcher + " to run GlassFish Embedded with");
        }
        try (JarFile file = new JarFile(launcher.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null || manifest.getMainAttributes().getValue("Main-Class") == null) {
                throw new IOException(launcher.getFileName() + " has no Main-Class: this GlassFish Embedded can't "
                        + "run an application from the command line");
            }
        }
    }
}
