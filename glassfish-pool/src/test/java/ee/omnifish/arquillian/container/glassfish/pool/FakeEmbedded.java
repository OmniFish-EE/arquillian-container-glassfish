/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.ToolProvider;

/**
 * Stands in for GlassFish Embedded: a runnable JAR whose main class logs a
 * result record for each startup command it's given, as GlassFish
 * Embedded's own does, then idles until it's ended. It fails the start, with
 * exit code 1, for an archive whose name has {@code broken}.
 */
final class FakeEmbedded {

    /** The part of Embedded's own domain.xml that has the IIOP and JMS ports. */
    static final String DOMAIN_XML = "<domain><servers><server name=\"server\" config-ref=\"server-config\"/>"
            + "</servers><configs><config name=\"server-config\"><iiop-service>"
            + "<iiop-listener port=\"3700\" id=\"orb-listener-1\"/>"
            + "<iiop-listener port=\"3820\" id=\"SSL\"/>"
            + "<iiop-listener port=\"3920\" id=\"SSL_MUTUALAUTH\"/>"
            + "</iiop-service><jms-service default-jms-host=\"default_JMS_host\">"
            + "<jms-host name=\"default_JMS_host\" port=\"7676\"/></jms-service></config></configs></domain>";

    private static final String MAIN = "public class FakeUberMain {\n"
            + "    public static void main(String[] args) throws Exception {\n"
            + "        boolean commands = false;\n"
            + "        String name = null;\n"
            + "        for (String arg : args) {\n"
            + "            if (arg.startsWith(\"--noInfo\")) {\n"
            + "                commands = true;\n"
            + "                continue;\n"
            + "            }\n"
            + "            if (!commands) {\n"
            + "                continue;\n"
            + "            }\n"
            + "            String command = arg.replace(\"\\\\ \", \" \");\n"
            + "            if (command.contains(\"broken\")) {\n"
            + "                System.out.println(\"SEVERE: the archive is broken\");\n"
            + "                System.exit(1);\n"
            + "            }\n"
            + "            String verb = command.split(\" \")[0];\n"
            + "            System.out.println(\"INFO: SUCCESS: PlainTextActionReporterSUCCESSDescription: \" + verb\n"
            + "                    + \" AdminCommand\");\n"
            + "            if (verb.equals(\"deploy\")) {\n"
            + "                for (String word : command.split(\" \")) {\n"
            + "                    if (word.startsWith(\"--name=\")) {\n"
            + "                        name = word.substring(\"--name=\".length());\n"
            + "                    }\n"
            + "                }\n"
            + "                System.out.println(\"    [name=\" + name);\n"
            + "            } else if (verb.equals(\"get\")) {\n"
            + "                System.out.println(\"    applications.application.\" + name\n"
            + "                        + \".context-root=/\" + name);\n"
            + "            } else if (verb.equals(\"list-sub-components\")) {\n"
            + "                System.out.println(\"    [StandIn=Servlet\");\n"
            + "            }\n"
            + "            System.out.println();\n"
            + "        }\n"
            + "        Thread.sleep(120_000);\n"
            + "    }\n"
            + "}\n";

    private FakeEmbedded() {
    }

    /** The stand-in's runnable JAR, built in {@code dir}, with Embedded's own domain.xml. */
    static Path jar(Path dir) throws IOException {
        Path source = Files.createDirectories(dir.resolve("src")).resolve("FakeUberMain.java");
        Files.writeString(source, MAIN);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                source.toString()), equalTo(0));

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeUberMain");
        Path jar = dir.resolve("glassfish-embedded-all-9.0.0.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream entries = new JarOutputStream(out, manifest)) {
            entries.putNextEntry(new JarEntry("FakeUberMain.class"));
            entries.write(Files.readAllBytes(classes.resolve("FakeUberMain.class")));
            entries.closeEntry();
            entries.putNextEntry(new JarEntry("org/glassfish/embed/domain.xml"));
            entries.write(DOMAIN_XML.getBytes());
            entries.closeEntry();
        }
        return jar;
    }

    /** A process that sleeps until it's ended, as a child that runs. */
    static Process sleeper(Path dir) throws IOException {
        Path sleeper = Files.writeString(dir.resolve("Sleeper.java"),
                "class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(120_000); } }");
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                sleeper.toString()).start();
    }
}
