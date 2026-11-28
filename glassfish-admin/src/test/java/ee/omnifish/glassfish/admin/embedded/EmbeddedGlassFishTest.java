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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a JVM that hosts GlassFish Embedded needs from it: its class path, and its module options. */
class EmbeddedGlassFishTest {

    @TempDir
    Path tmp;

    @Test
    void theJarIsItsOwnClassPath() throws IOException {
        Path jar = tmp.resolve("glassfish-embedded-all-8.0.5.jar");

        assertThat(EmbeddedGlassFish.jar(tmp, jar, null, null).classPath(), contains(jar));
    }

    /** The static shell's manifest names the modules, but not the JARs of lib/bootstrap. */
    @Test
    void anInstallsClassPathIsItsStaticShellAndBootstrapJars() throws IOException {
        Path home = tmp.resolve("glassfish8");
        Path bootstrap = Files.createDirectories(home.resolve(EmbeddedGlassFish.BOOTSTRAP_DIR));
        Files.writeString(bootstrap.resolve("simple-glassfish-api.jar"), "api");
        Files.writeString(bootstrap.resolve("glassfish-jul-extension.jar"), "jul");
        Files.writeString(bootstrap.resolve("readme.txt"), "not a JAR");

        assertThat(EmbeddedGlassFish.install(tmp, home, null, null).classPath(), contains(
                home.resolve(EmbeddedGlassFish.STATIC_SHELL),
                bootstrap.resolve("glassfish-jul-extension.jar"),
                bootstrap.resolve("simple-glassfish-api.jar")));
    }

    /** Its manifest has them for java -jar only; on a class path, the JVM's command line needs them. */
    @Test
    void theJarsModuleOptionsComeFromItsManifest() throws IOException {
        Path jar = tmp.resolve("glassfish-embedded-all-8.0.5.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Add-Opens", "java.base/java.lang java.naming/javax.naming.spi");
        manifest.getMainAttributes().putValue("Add-Exports", "java.naming/com.sun.jndi.ldap");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.finish();
        }

        assertThat(EmbeddedGlassFish.jar(tmp, jar, null, null).moduleOptions(), contains(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.naming/javax.naming.spi=ALL-UNNAMED",
                "--add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED"));
    }

    /** Domain1's, each to every unnamed module, as GlassFish's classes are in one there. */
    @Test
    void anInstallsModuleOptionsComeFromDomain1() throws IOException {
        Path home = tmp.resolve("glassfish8");
        Path config = Files.createDirectories(home.resolve(EmbeddedGlassFish.DOMAIN_CONFIG));
        Files.writeString(config.resolve("domain.xml"), "<domain><configs><config name=\"server-config\">"
                + "<java-config><jvm-options>-Xmx512m</jvm-options>"
                + "<jvm-options>[17|]--add-opens=java.base/java.lang=ALL-UNNAMED</jvm-options>"
                + "<jvm-options>--add-opens=java.naming/javax.naming.spi=org.glassfish.main.jdke</jvm-options>"
                + "<jvm-options>--add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED</jvm-options>"
                + "</java-config></config></configs></domain>");

        assertThat(EmbeddedGlassFish.install(tmp, home, null, null).moduleOptions(), contains(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.naming/javax.naming.spi=ALL-UNNAMED",
                "--add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED"));
    }

    /**
     * In the stage directory, relative to itself, as the JAR specification
     * has it: GlassFish's JSP compiler reads an absolute URL as a file path
     * relative to the JAR, which Windows then refuses, and doesn't decode an
     * escaped space. Next to what it names, the entries hold the staged
     * layout only, also where the stage directory's own path has a space.
     */
    @Test
    void theClassPathJarNamesTheClassPathRelativeToItself() throws IOException {
        Path stage = tmp.resolve("with space/dist");
        Path home = stage.resolve("glassfish8");
        Path bootstrap = Files.createDirectories(home.resolve(EmbeddedGlassFish.BOOTSTRAP_DIR));
        Files.writeString(bootstrap.resolve("glassfish.jar"), "bootstrap");
        EmbeddedGlassFish glassfish = EmbeddedGlassFish.install(stage, home, null, null);

        glassfish.writeClassPathJar();

        assertThat(glassfish.classPathJar(), equalTo(stage.resolve("embedded-classpath.jar")));
        assertThat(EmbeddedGlassFish.classPathOf(glassfish.classPathJar()), equalTo(
                "glassfish8/glassfish/lib/embedded/glassfish-embedded-static-shell.jar "
                + "glassfish8/glassfish/lib/bootstrap/glassfish.jar"));
    }

    /** A test JVM may run from it, which on Windows keeps it from being replaced. */
    @Test
    void aClassPathJarThatSaysTheSameIsLeftAlone() throws IOException {
        EmbeddedGlassFish glassfish = EmbeddedGlassFish.jar(tmp, tmp.resolve("glassfish-embedded-all.jar"), null, null);
        Path classPathJar = tmp.resolve("embedded-classpath.jar");
        glassfish.writeClassPathJar(classPathJar);
        FileTime written = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(classPathJar, written);

        glassfish.writeClassPathJar(classPathJar);

        assertThat(Files.getLastModifiedTime(classPathJar), equalTo(written));
    }
}
