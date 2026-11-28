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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * start, which fails for archives other than WARs. With
 * {@link #listServlets}, {@code list-sub-components} queries follow, for the
 * servlets of each web module.
 *
 * <p>GlassFish splits each startup command where it has whitespace that no
 * backslash precedes, and doesn't read quotes, so the words of a command,
 * which quotes may group, are passed with their whitespace escaped.
 */
public final class EmbeddedCommand {

    /** The query of a WAR's context root, which follows its deploy. */
    public static final String CONTEXT_ROOT_QUERY = "get applications.application.*.context-root";

    /** The system property that gives the JMS broker's RMI registry its port, by default the broker's + 100. */
    public static final String JMS_RMI_PORT_PROPERTY = "com.sun.enterprise.connectors.system.mq.rmiport";

    /** A log format with only each record's message, and its exception. */
    static final String MESSAGES_ONLY_FORMAT = "%5$s%6$s%n";

    private static final String LIST_SUB_COMPONENTS = "list-sub-components";

    private final EmbeddedGlassFish glassfish;
    private final String archiveName;
    private final boolean war;

    private EmbeddedPorts ports = EmbeddedPorts.standard();
    private boolean debug;
    private boolean suspend;
    private boolean messagesOnly;
    private List<String> jvmOptions = List.of();
    private List<String> commands = List.of();
    private String name;
    private String contextRoot;
    private List<String> deployOptions = List.of();
    private boolean listServlets;
    private Map<String, String> webModules = Map.of();

    /**
     * @param archiveName the archive's file name in the child's working directory
     * @param war whether the archive is a WAR
     */
    public EmbeddedCommand(EmbeddedGlassFish glassfish, String archiveName, boolean war) {
        this.glassfish = glassfish;
        this.archiveName = archiveName;
        this.war = war;
    }

    /** The ports the child opens; {@link EmbeddedPorts#standard()} by default. */
    public EmbeddedCommand ports(EmbeddedPorts ports) {
        this.ports = ports;
        return this;
    }

    /** Let a debugger attach on the ports' debug address, and with {@code suspend}, wait for one before starting. */
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

    /** Options of the child's JVM, such as {@code -Xmx1g} or {@code -Dkey=value}, before the ones it needs. */
    public EmbeddedCommand jvmOptions(List<String> jvmOptions) {
        this.jvmOptions = List.copyOf(jvmOptions);
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

    /** More options of the {@code deploy}, one word each, such as {@code --libraries=a.jar}. */
    public EmbeddedCommand deployOptions(List<String> deployOptions) {
        this.deployOptions = List.copyOf(deployOptions);
        return this;
    }

    /**
     * Query the servlets of the application, which needs its {@link #name}:
     * of a WAR with one {@code list-sub-components}, of an EAR with one for
     * its web modules and their context roots, then one per module, with
     * {@code --appname}.
     *
     * @param webModules an EAR's web modules, by URI, each with the context
     *        root its descriptor gives, for when GlassFish doesn't say; empty
     *        for other archives
     */
    public EmbeddedCommand listServlets(Map<String, String> webModules) {
        this.listServlets = true;
        this.webModules = new LinkedHashMap<>(webModules);
        return this;
    }

    /**
     * The command line, for the JVM that runs this one.
     *
     * @throws IllegalArgumentException if a command can't be passed on, as
     *         with an unclosed quote
     * @throws IllegalStateException if servlets are listed without a name
     */
    public List<String> build() throws IOException {
        if (listServlets && name == null) {
            throw new IllegalStateException("Listing the servlets of " + archiveName + " needs its name");
        }

        boolean windows = File.separatorChar == '\\';
        List<String> line = new ArrayList<>();
        line.add(Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        line.addAll(jvmOptions);
        if (debug || suspend) {
            line.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (suspend ? "y" : "n") + ",address="
                    + ports.debugAddress());
        }
        if (messagesOnly) {
            line.add("-Djava.util.logging.SimpleFormatter.format=" + MESSAGES_ONLY_FORMAT);
        }
        if (ports.movesOthers()) {
            line.add("-D" + JMS_RMI_PORT_PROPERTY + "=" + ports.jmsRmi());
        }
        if (glassfish.isJar()) {
            if (glassfish.trustStore() != null) {
                line.add("-Djavax.net.ssl.trustStore=" + EmbeddedWorkDir.TRUST_STORE);
                line.add("-Djavax.net.ssl.trustStorePassword=" + EmbeddedWorkDir.TRUST_STORE_PASSWORD);
            }
            line.add("-jar");
            line.add(glassfish.jar().toAbsolutePath().toString());
            if (ports.movesOthers()) {
                // Its own domain.xml, with the IIOP and JMS ports of the child's.
                line.add("--domainDir=" + EmbeddedWorkDir.DOMAIN_DIR);
            }
            line.add("--httpPort=" + ports.http());
        } else {
            Path home = glassfish.home().toAbsolutePath();
            line.addAll(moduleOptions(DomainXmlEditor.jvmOptions(glassfish.domainConfig().resolve("domain.xml"))));
            line.add("-cp");
            // Windows doesn't take * in a path, only in a class path.
            line.add(glassfish.launcher().toAbsolutePath() + File.pathSeparator
                    + home.resolve("glassfish/lib/bootstrap") + File.separator + "*");
            line.add(EmbeddedProcesses.MAIN_CLASS);
            line.add("--domainDir=" + EmbeddedWorkDir.DOMAIN_DIR);
            line.add("--httpPort=" + ports.http());
            line.add("--httpsPort=" + ports.https());
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
        deploy.addAll(deployOptions);
        deploy.add(archiveName);
        line.add(escape(deploy));
        if (war) {
            line.add(CONTEXT_ROOT_QUERY);
        }
        if (listServlets && (war || !webModules.isEmpty())) {
            line.add(escape(List.of(LIST_SUB_COMPONENTS, name)));
            for (String module : webModules.keySet()) {
                line.add(escape(List.of(LIST_SUB_COMPONENTS, "--appname=" + name, module)));
            }
        }

        return line;
    }

    /** What follows the output of the child {@link #build} starts, until it has started. */
    public EmbeddedStartup startup() {
        return listServlets ? new EmbeddedStartup(war, commands, webModules) : new EmbeddedStartup(war, commands);
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
