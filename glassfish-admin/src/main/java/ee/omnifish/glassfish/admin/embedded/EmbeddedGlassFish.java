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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ee.omnifish.glassfish.admin.DomainXmlEditor;

/**
 * The GlassFish Embedded that staging provided: its self-contained JAR, or an
 * install whose static shell runs Embedded with the install's modules, as
 * when a module is overlaid. Either runs GlassFish Embedded's own main class,
 * with the same options, or boots in a JVM that hosts it, from a class path
 * ({@link #classPath}) and with module options ({@link #moduleOptions}) of
 * that JVM.
 */
public final class EmbeddedGlassFish {

    /** The static shell of an install, relative to its root. */
    static final String STATIC_SHELL = "glassfish/lib/embedded/glassfish-embedded-static-shell.jar";

    /** The JARs a static shell's manifest doesn't name, relative to an install's root. */
    static final String BOOTSTRAP_DIR = "glassfish/lib/bootstrap";

    /** Where {@code domain1}'s configuration lives, relative to an install's root. */
    static final Path DOMAIN_CONFIG = Paths.get("glassfish", "domains", "domain1", "config");

    private final Path stageDir;
    private final Path jar;
    private final Path home;
    private final Path trustStore;
    private final String trustStorePassword;

    private EmbeddedGlassFish(Path stageDir, Path jar, Path home, Path trustStore, String trustStorePassword) {
        this.stageDir = stageDir;
        this.jar = jar;
        this.home = home;
        this.trustStore = trustStore;
        this.trustStorePassword = trustStorePassword;
    }

    /**
     * The self-contained JAR, staged in {@code stageDir}. Its domain has no
     * truststore, so the child selects a copy of {@code trustStore} with
     * {@code javax.net.ssl.trustStore}.
     *
     * @param trustStore the truststore with the imported certificates, or
     *        {@code null} for the JDK's
     * @param trustStorePassword the password that opens {@code trustStore}
     */
    public static EmbeddedGlassFish jar(Path stageDir, Path jar, Path trustStore, String trustStorePassword) {
        return new EmbeddedGlassFish(stageDir, jar, null, trustStore, trustStorePassword);
    }

    /**
     * An install, staged in {@code stageDir}, run through its static shell.
     * GlassFish reads the truststore of its instance, so a copy of
     * {@code trustStore} goes there.
     *
     * @param trustStore the truststore for the instance, such as {@code domain1}'s
     * @param trustStorePassword the password that opens {@code trustStore}
     */
    public static EmbeddedGlassFish install(Path stageDir, Path home, Path trustStore, String trustStorePassword) {
        return new EmbeddedGlassFish(stageDir, null, home, trustStore, trustStorePassword);
    }

    /** The stage directory it runs from. */
    public Path stageDir() {
        return stageDir;
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

    /** The truststore the child uses a copy of, or {@code null} for the JDK's. */
    public Path trustStore() {
        return trustStore;
    }

    /** The password that opens {@link #trustStore}. */
    public String trustStorePassword() {
        return trustStorePassword;
    }

    /** The configuration directory of the install's {@code domain1}, with its keystore and module options. */
    public Path domainConfig() {
        return home.resolve(DOMAIN_CONFIG);
    }

    /** The JAR whose main class runs GlassFish Embedded: the self-contained JAR, or the install's static shell. */
    public Path launcher() {
        return isJar() ? jar : home.resolve(STATIC_SHELL);
    }

    /**
     * What a JVM that hosts GlassFish Embedded needs on its class path: the
     * self-contained JAR, or the static shell, whose manifest names the
     * install's modules, and the JARs of {@code lib/bootstrap}, which it
     * doesn't.
     */
    public List<Path> classPath() throws IOException {
        if (isJar()) {
            return List.of(jar);
        }

        List<Path> classPath = new ArrayList<>(List.of(launcher()));
        Path bootstrapDir = home.resolve(BOOTSTRAP_DIR);
        if (Files.isDirectory(bootstrapDir)) {
            try (Stream<Path> bootstrap = Files.list(bootstrapDir)) {
                classPath.addAll(bootstrap.filter(file -> file.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .collect(Collectors.toList()));
            }
        }
        return classPath;
    }

    /**
     * Write {@code file}, a JAR with only a manifest, whose {@code Class-Path}
     * names the {@link #classPath}, for a class path that takes one entry
     * per file, as Failsafe's {@code additionalClasspathElements} does. A
     * file that already says the same is left alone: a test JVM may run
     * from it, which on Windows keeps it from being replaced.
     */
    public void writeClassPathJar(Path file) throws IOException {
        String classPath = classPath().stream()
                .map(entry -> entry.toAbsolutePath().normalize().toUri().toString())
                .collect(Collectors.joining(" "));
        if (Files.isRegularFile(file) && classPath.equals(classPathOf(file))) {
            return;
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        Path tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
                // Only the manifest.
                out.finish();
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

    /** The {@code Class-Path} of the JAR {@code file}'s manifest, or {@code null}. Package-private for unit tests. */
    static String classPathOf(Path file) {
        try (JarFile jarFile = new JarFile(file.toFile())) {
            Manifest manifest = jarFile.getManifest();
            return (manifest == null) ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The module options a JVM that hosts GlassFish Embedded needs, each to
     * {@code ALL-UNNAMED}: the JAR's {@code Add-Opens} and
     * {@code Add-Exports}, which its manifest has for {@code java -jar} only,
     * or those of the install's {@code domain1}.
     */
    public List<String> moduleOptions() throws IOException {
        if (!isJar()) {
            return EmbeddedCommand.moduleOptions(DomainXmlEditor.jvmOptions(domainConfig().resolve("domain.xml")));
        }

        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return List.of();
            }

            List<String> options = new ArrayList<>();
            for (String pkg : packages(manifest, "Add-Opens")) {
                options.add("--add-opens=" + pkg + "=ALL-UNNAMED");
            }
            for (String pkg : packages(manifest, "Add-Exports")) {
                options.add("--add-exports=" + pkg + "=ALL-UNNAMED");
            }
            return options;
        }
    }

    /** The {@code module/package} entries of a manifest attribute, such as {@code Add-Opens}. */
    private static List<String> packages(Manifest manifest, String attribute) {
        String value = manifest.getMainAttributes().getValue(attribute);
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.asList(value.trim().split("\\s+"));
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
