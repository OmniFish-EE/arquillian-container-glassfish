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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands;

/**
 * The command line of a GlassFish Embedded child that runs an application,
 * the way GlassFish's own main class runs one from the command line: its
 * options, the startup commands, and a {@code deploy} of the archive, by its
 * file name in the child's working directory ({@link EmbeddedWorkDir}). For a
 * WAR, a {@code get} of its context root follows, so its URL is known.
 * {@code --noInfo=true} leaves out the banner GlassFish prints after its
 * start, which fails for archives other than WARs.
 *
 * <p>GlassFish splits each startup command where it has whitespace that no
 * backslash precedes, and doesn't read quotes, so the words of a command,
 * which quotes may group, are passed with their whitespace escaped.
 */
public final class EmbeddedCommand {

    public static final int HTTP_PORT = 8080;
    public static final int HTTPS_PORT = 8181;

    /** Where a debugger attaches: GlassFish's own port, on localhost only. */
    public static final String DEBUG_ADDRESS = "localhost:9009";

    /** The query of a WAR's context root, which follows its deploy. */
    public static final String CONTEXT_ROOT_QUERY = "get applications.application.*.context-root";

    /** A log format with only each record's message, and its exception. */
    static final String MESSAGES_ONLY_FORMAT = "%5$s%6$s%n";

    private final EmbeddedGlassFish glassfish;
    private final String archiveName;
    private final boolean war;

    private boolean debug;
    private boolean suspend;
    private boolean messagesOnly;
    private List<String> commands = List.of();
    private String name;
    private String contextRoot;

    /**
     * @param archiveName the archive's file name in the child's working directory
     * @param war whether the archive is a WAR
     */
    public EmbeddedCommand(EmbeddedGlassFish glassfish, String archiveName, boolean war) {
        this.glassfish = glassfish;
        this.archiveName = archiveName;
        this.war = war;
    }

    /** Let a debugger attach on {@link #DEBUG_ADDRESS}, and with {@code suspend}, wait for one before starting. */
    public EmbeddedCommand debug(boolean debug, boolean suspend) {
        this.debug = debug;
        this.suspend = suspend;
        return this;
    }

    /** Log only the records' messages, without the line with their time, logger and level before each. */
    public EmbeddedCommand messagesOnly(boolean messagesOnly) {
        this.messagesOnly = messagesOnly;
        return this;
    }

    /** The {@code asadmin} commands to run before the deploy, one per entry, as a user writes them. */
    public EmbeddedCommand commands(List<String> commands) {
        this.commands = List.copyOf(commands);
        return this;
    }

    /** The application's name, or {@code null} for GlassFish's. */
    public EmbeddedCommand name(String name) {
        this.name = name;
        return this;
    }

    /** The context root of a WAR, or {@code null} for GlassFish's. */
    public EmbeddedCommand contextRoot(String contextRoot) {
        this.contextRoot = contextRoot;
        return this;
    }

    /** The ports the child opens: HTTP, and for an install, which has a server key, HTTPS. */
    public List<Integer> ports() {
        return glassfish.isJar() ? List.of(HTTP_PORT) : List.of(HTTP_PORT, HTTPS_PORT);
    }

    /**
     * The command line, for the JVM that runs Maven.
     *
     * @throws IllegalArgumentException if a command can't be passed on, as
     *         with an unclosed quote
     */
    public List<String> build() throws IOException {
        boolean windows = File.separatorChar == '\\';
        List<String> line = new ArrayList<>();
        line.add(Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        if (debug || suspend) {
            line.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (suspend ? "y" : "n") + ",address="
                    + DEBUG_ADDRESS);
        }
        if (messagesOnly) {
            line.add("-Djava.util.logging.SimpleFormatter.format=" + MESSAGES_ONLY_FORMAT);
        }
        if (glassfish.isJar()) {
            if (glassfish.trustStore() != null) {
                line.add("-Djavax.net.ssl.trustStore=" + glassfish.trustStore().toAbsolutePath());
                line.add("-Djavax.net.ssl.trustStorePassword=" + glassfish.trustStorePassword());
            }
            line.add("-jar");
            line.add(glassfish.jar().toAbsolutePath().toString());
            line.add("--httpPort=" + HTTP_PORT);
        } else {
            Path home = glassfish.home().toAbsolutePath();
            line.addAll(moduleOptions(DomainXmlEditor.jvmOptions(home.resolve(GlassFishStaging.DOMAIN_CONFIG)
                    .resolve("domain.xml"))));
            line.add("-cp");
            line.add(glassfish.launcher().toAbsolutePath() + File.pathSeparator
                    + home.resolve("glassfish/lib/bootstrap").resolve("*"));
            line.add(EmbeddedProcesses.MAIN_CLASS);
            line.add("--domainDir=" + EmbeddedWorkDir.DOMAIN_DIR);
            line.add("--httpPort=" + HTTP_PORT);
            line.add("--httpsPort=" + HTTPS_PORT);
        }
        line.add("--noInfo=true");
        for (String command : commands) {
            line.add(escape(PostBootCommands.words(command)));
        }
        List<String> deploy = new ArrayList<>(List.of("deploy"));
        if (name != null) {
            deploy.add("--name=" + name);
        }
        if (contextRoot != null) {
            deploy.add("--contextroot=" + contextRoot);
        }
        deploy.add(archiveName);
        line.add(escape(deploy));
        if (war) {
            line.add(CONTEXT_ROOT_QUERY);
        }

        return line;
    }

    /**
     * The module options of an install's {@code domain.xml}, for its static
     * shell: that puts GlassFish on the class path, so {@code --add-opens}
     * and {@code --add-exports} open to every unnamed module, whichever
     * module they name. {@code --add-modules} stays as it is. A JDK range
     * GlassFish puts in front, as in {@code [17|]--add-opens=…}, is left out.
     * Package-private for unit tests.
     */
    static List<String> moduleOptions(List<String> jvmOptions) {
        Set<String> options = new LinkedHashSet<>();
        for (String option : jvmOptions) {
            String trimmed = option.trim().replaceFirst("^\\[[^\\]]*\\]", "");
            if (trimmed.startsWith("--add-opens=") || trimmed.startsWith("--add-exports=")) {
                int target = trimmed.lastIndexOf('=');
                options.add(trimmed.substring(0, target + 1) + "ALL-UNNAMED");
            } else if (trimmed.startsWith("--add-modules=")) {
                options.add(trimmed);
            }
        }

        return new ArrayList<>(options);
    }

    /**
     * {@code words} as one startup command, with each word's whitespace
     * escaped. Package-private for unit tests.
     *
     * @throws IllegalArgumentException if a word ends with a backslash,
     *         which would escape the space after it
     */
    static String escape(List<String> words) {
        List<String> escaped = new ArrayList<>();
        for (String word : words) {
            if (word.endsWith("\\")) {
                throw new IllegalArgumentException("GlassFish Embedded would join '" + word + "' with the word after "
                        + "it, as it ends with a backslash; quote a value that has spaces instead");
            }
            escaped.add(word.replaceAll("(\\s)", "\\\\$1"));
        }

        return String.join(" ", escaped);
    }
}
