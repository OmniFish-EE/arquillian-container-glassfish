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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddedCommandTest {

    @TempDir
    Path tmp;

    @Test
    void theJarRunsTheApplicationOverHttp() throws IOException {
        Path jar = tmp.resolve("glassfish-embedded-all-8.0.5.jar");

        List<String> line = new EmbeddedCommand(EmbeddedGlassFish.jar(jar, null, null), "shop.war", true).build();

        assertThat(line.get(0), endsWith(File.separator + "bin" + File.separator
                + (File.separatorChar == '\\' ? "java.exe" : "java")));
        assertThat(line.subList(1, line.size()), contains("-jar", jar.toAbsolutePath().toString(), "--httpPort=8080",
                "--noInfo=true", "deploy shop.war", EmbeddedCommand.CONTEXT_ROOT_QUERY));
    }

    /** Its domain has no truststore, so the child selects the one with the certificates. */
    @Test
    void theJarSelectsItsTruststore() throws IOException {
        Path jar = tmp.resolve("glassfish-embedded-all-8.0.5.jar");
        Path trustStore = tmp.resolve("cacerts.p12");

        List<String> line = new EmbeddedCommand(EmbeddedGlassFish.jar(jar, trustStore, "changeit"), "shop.war", true)
                .build();

        assertThat(line.subList(1, 4), contains("-Djavax.net.ssl.trustStore=" + trustStore.toAbsolutePath(),
                "-Djavax.net.ssl.trustStorePassword=changeit", "-jar"));
    }

    @Test
    void debuggingAndTheLogFormatAreOptionsOfTheJvm() throws IOException {
        EmbeddedGlassFish glassfish = EmbeddedGlassFish.jar(tmp.resolve("embedded.jar"), null, null);

        List<String> debug = new EmbeddedCommand(glassfish, "shop.war", true).debug(true, false).messagesOnly(true)
                .build();
        List<String> suspend = new EmbeddedCommand(glassfish, "shop.war", true).debug(false, true).build();

        assertThat(debug.subList(1, 4), contains(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:9009",
                "-Djava.util.logging.SimpleFormatter.format=%5$s%6$s%n", "-jar"));
        assertThat(suspend.get(1), equalTo(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:9009"));
    }

    @Test
    void anInstallRunsEmbeddedThroughItsStaticShell() throws IOException {
        Path home = install(tmp.resolve("glassfish8"));

        List<String> line = new EmbeddedCommand(EmbeddedGlassFish.install(home, null), "shop.ear", false).build();

        Path glassfish = home.toAbsolutePath().resolve("glassfish");
        assertThat(line.subList(1, line.size()), contains(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.naming/javax.naming.spi=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector",
                "-cp",
                glassfish.resolve("lib/embedded/glassfish-embedded-static-shell.jar") + File.pathSeparator
                        + glassfish.resolve("lib/bootstrap").resolve("*"),
                "org.glassfish.runnablejar.UberMain",
                "--domainDir=domain",
                "--httpPort=8080",
                "--httpsPort=8181",
                "--noInfo=true",
                "deploy shop.ear"));
    }

    @Test
    void theOpenPortsDependOnTheForm() {
        assertThat(new EmbeddedCommand(EmbeddedGlassFish.jar(tmp.resolve("embedded.jar"), null, null), "a.war", true)
                .ports(), contains(8080));
        assertThat(new EmbeddedCommand(EmbeddedGlassFish.install(tmp, null), "a.war", true).ports(),
                contains(8080, 8181));
    }

    /** GlassFish splits commands where whitespace has no backslash before it, and doesn't read quotes. */
    @Test
    void theCommandsComeFirstWithTheirQuotedSpacesEscaped() throws IOException {
        List<String> line = new EmbeddedCommand(EmbeddedGlassFish.jar(tmp.resolve("embedded.jar"), null, null),
                "shop.war", true)
                .commands(List.of("create-system-properties marker=\"from a command\"", "set a.b=c"))
                .name("my shop")
                .contextRoot("/shop")
                .build();

        assertThat(line.subList(line.size() - 4, line.size()), contains(
                "create-system-properties marker=from\\ a\\ command",
                "set a.b=c",
                "deploy --name=my\\ shop --contextroot=/shop shop.war",
                EmbeddedCommand.CONTEXT_ROOT_QUERY));
    }

    @Test
    void onlyAWarIsQueriedForItsContextRoot() throws IOException {
        List<String> line = new EmbeddedCommand(EmbeddedGlassFish.jar(tmp.resolve("embedded.jar"), null, null),
                "bean.jar", false).build();

        assertThat(line.get(line.size() - 1), equalTo("deploy bean.jar"));
        assertThat(line, not(hasItem(EmbeddedCommand.CONTEXT_ROOT_QUERY)));
    }

    @Test
    void aWordEndingWithABackslashCantBePassedOn() {
        EmbeddedCommand command = new EmbeddedCommand(EmbeddedGlassFish.jar(tmp.resolve("embedded.jar"), null, null),
                "shop.war", true).commands(List.of("set a=b\\ c=d"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, command::build);

        assertThat(e.getMessage(), containsString("'a=b\\'"));
    }

    @Test
    void theLauncherNeedsAMainClass() throws IOException {
        Path runnable = jar(tmp.resolve("runnable.jar"), "org.glassfish.runnablejar.UberMain");
        Path old = jar(tmp.resolve("old.jar"), null);

        assertDoesNotThrow(() -> EmbeddedGlassFish.jar(runnable, null, null).requireMainClass());
        IOException withoutMainClass = assertThrows(IOException.class,
                () -> EmbeddedGlassFish.jar(old, null, null).requireMainClass());
        IOException missing = assertThrows(IOException.class,
                () -> EmbeddedGlassFish.install(tmp.resolve("glassfish8"), null).requireMainClass());

        assertThat(withoutMainClass.getMessage(), containsString("old.jar has no Main-Class"));
        assertThat(missing.getMessage(), containsString("glassfish-embedded-static-shell.jar"));
    }

    /** An install whose domain.xml has module options among others, in two configs. */
    static Path install(Path home) throws IOException {
        Path config = Files.createDirectories(home.resolve(GlassFishStaging.DOMAIN_CONFIG));
        Files.writeString(config.resolve("domain.xml"), "<domain><configs>"
                + "<config name=\"server-config\"><java-config>"
                + "<jvm-options>--add-opens=java.base/java.lang=ALL-UNNAMED</jvm-options>"
                + "<jvm-options>--add-opens=java.naming/javax.naming.spi=org.glassfish.main.jdke</jvm-options>"
                + "<jvm-options>[17|]--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED</jvm-options>"
                + "<jvm-options>--add-modules=jdk.incubator.vector</jvm-options>"
                + "<jvm-options>-Xmx512m</jvm-options>"
                + "</java-config></config>"
                + "<config name=\"default-config\"><java-config>"
                + "<jvm-options>--add-opens=java.base/java.lang=ALL-UNNAMED</jvm-options>"
                + "</java-config></config>"
                + "</configs></domain>");
        return home;
    }

    /** An empty JAR, with {@code mainClass} in its manifest unless that's {@code null}. */
    private static Path jar(Path file, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return file;
    }
}
