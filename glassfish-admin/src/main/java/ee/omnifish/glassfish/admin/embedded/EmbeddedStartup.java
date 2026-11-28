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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ee.omnifish.glassfish.admin.PostBootCommands;

/**
 * Follows the output of a GlassFish Embedded child until it has deployed the
 * application, from the records its main class logs of the startup commands
 * it ran, such as
 * <pre>
 * INFO: SUCCESS: PlainTextActionReporterSUCCESSDescription: deploy AdminCommandApplication deployed with name shop.
 *     [name=shop
 *
 * </pre>
 * with or without the line before each record that a full log format adds,
 * and without translated text to match. The {@code deploy} record gives the
 * application's name. For a WAR, the record of the context-root query that
 * follows gives its context root, among those of the applications deployed
 * before it, and the child has started once it ends; otherwise, it has once
 * the {@code deploy} record ends. The lines of a record end at an empty one.
 *
 * <p>When the command lists the servlets ({@link EmbeddedCommand#listServlets}),
 * the child has started once the last {@code list-sub-components} record
 * ends. For a WAR, one record gives its servlets, as {@code [name=Servlet}
 * lines. For an EAR, the first gives its web modules, with the context root
 * GlassFish gave each, as {@code [moduleInfo=alpha.war:war:/alpha}, and one
 * per module follows, with its servlets.
 *
 * <p>The startup commands before them may deploy, query and list too, so the
 * application's {@code deploy} is the one after as many as they have, and its
 * query and listings likewise.
 *
 * <p>It takes the lines of the child's output in order, from one thread.
 */
public final class EmbeddedStartup {

    private static final String SUCCESS = "SUCCESS: PlainTextActionReporterSUCCESSDescription: ";
    private static final String DEPLOY = SUCCESS + "deploy AdminCommand";
    private static final String QUERY = SUCCESS + "get AdminCommand";
    private static final String LISTING = SUCCESS + "list-sub-components AdminCommand";
    private static final Pattern NAME = Pattern.compile("\\s+\\[name=(.*)");
    private static final Pattern CONTEXT_ROOT = Pattern.compile(
            "\\s+applications\\.application\\.(.+)\\.context-root=(.*)");
    private static final Pattern WEB_MODULE = Pattern.compile("\\s+\\[moduleInfo=([^:]+):war:(.*)");
    private static final Pattern SERVLET = Pattern.compile("\\s+\\[(.+)=Servlet");

    private enum Record { NONE, DEPLOY, QUERY, LISTING }

    private final boolean war;
    private final int deploysBefore;
    private final int queriesBefore;
    private final int listingsBefore;
    private final boolean listsServlets;
    private final Map<String, String> webModules;
    private final int listings;
    private final List<String> contextRoots = new ArrayList<>();
    private final Map<String, String> reportedRoots = new LinkedHashMap<>();
    private final Map<String, List<String>> servlets = new LinkedHashMap<>();

    private Record record = Record.NONE;
    private int deploys;
    private int queries;
    private int listing;
    private List<String> listed;
    private boolean deployed;
    private String name;
    private boolean started;
    private String failure;

    /**
     * @param war whether the child deploys a WAR, whose deploy a context-root query follows
     * @param commands the startup commands that run before the deploy
     */
    public EmbeddedStartup(boolean war, List<String> commands) {
        this(war, commands, false, Map.of());
    }

    /** With the servlet listings {@link EmbeddedCommand#listServlets} adds for {@code webModules}. */
    EmbeddedStartup(boolean war, List<String> commands, Map<String, String> webModules) {
        this(war, commands, true, webModules);
    }

    private EmbeddedStartup(boolean war, List<String> commands, boolean listsServlets,
            Map<String, String> webModules) {
        this.war = war;
        this.listsServlets = listsServlets && (war || !webModules.isEmpty());
        this.webModules = new LinkedHashMap<>(webModules);
        listings = !this.listsServlets ? 0 : war ? 1 : 1 + webModules.size();
        deploysBefore = count(commands, "deploy");
        queriesBefore = count(commands, "get");
        listingsBefore = count(commands, "list-sub-components");
    }

    private static int count(List<String> commands, String name) {
        int count = 0;
        for (String command : commands) {
            List<String> words = PostBootCommands.words(command);
            if (!words.isEmpty() && words.get(0).equals(name)) {
                count++;
            }
        }

        return count;
    }

    public void accept(String line) {
        if (started || failure != null) {
            return;
        }

        if (line.contains(DEPLOY)) {
            record = (++deploys > deploysBefore) ? Record.DEPLOY : Record.NONE;
        } else if (line.contains(QUERY)) {
            record = (++queries > queriesBefore) ? Record.QUERY : Record.NONE;
        } else if (line.contains(LISTING)) {
            record = (++listing > listingsBefore && deployed) ? Record.LISTING : Record.NONE;
            listed = new ArrayList<>();
        } else if (line.isBlank()) {
            end();
        } else if (record == Record.DEPLOY) {
            Matcher deployedName = NAME.matcher(line);
            if (deployedName.matches()) {
                name = deployedName.group(1).trim();
            }
        } else if (record == Record.QUERY) {
            Matcher contextRoot = CONTEXT_ROOT.matcher(line);
            if (contextRoot.matches() && contextRoot.group(1).equals(name)) {
                contextRoots.add(contextRoot.group(2).trim());
            }
        } else if (record == Record.LISTING) {
            Matcher webModule = WEB_MODULE.matcher(line);
            Matcher servlet = SERVLET.matcher(line);
            if (webModule.matches()) {
                reportedRoots.put(webModule.group(1).trim(), rooted(webModule.group(2).trim()));
            } else if (servlet.matches()) {
                listed.add(servlet.group(1).trim());
            }
        }
    }

    private void end() {
        if (record == Record.DEPLOY) {
            deployed = true;
            started = !war && listings == 0;
        } else if (record == Record.QUERY) {
            if (deployed) {
                started = listings == 0;
            } else {
                // As with 7.0.x, which goes on after a command failed.
                failure = "GlassFish Embedded started without deploying the application";
            }
        } else if (record == Record.LISTING) {
            int index = listing - listingsBefore - 1;
            if (war) {
                servlets.put(contextRoots.isEmpty() ? "/" : rooted(contextRoots.get(0)), listed);
            } else if (index > 0) {
                String module = new ArrayList<>(webModules.keySet()).get(index - 1);
                servlets.put(reportedRoots.getOrDefault(module, rooted(webModules.get(module))), listed);
            }
            started = index == listings - 1;
        }
        record = Record.NONE;
    }

    /** {@code contextRoot}, starting with {@code /}, as GlassFish reports it either way. */
    static String rooted(String contextRoot) {
        return contextRoot.startsWith("/") ? contextRoot : "/" + contextRoot;
    }

    /**
     * The servlets that the output of a {@code list-sub-components} of a web
     * module lists, as a child logs it, or as a host gets it from GlassFish's
     * {@code CommandRunner}.
     */
    public static List<String> servletsIn(String output) {
        List<String> servlets = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher servlet = SERVLET.matcher(line);
            if (servlet.matches()) {
                servlets.add(servlet.group(1).trim());
            }
        }
        return servlets;
    }

    /**
     * The web modules that the output of a {@code list-sub-components} of an
     * EAR lists, by URI, each with the context root GlassFish gave it,
     * starting with {@code /}.
     */
    public static Map<String, String> webModulesIn(String output) {
        Map<String, String> webModules = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher webModule = WEB_MODULE.matcher(line);
            if (webModule.matches()) {
                webModules.put(webModule.group(1).trim(), rooted(webModule.group(2).trim()));
            }
        }
        return webModules;
    }

    /** The context roots that the output of a context-root query gives the application {@code name}. */
    public static List<String> contextRootsIn(String output, String name) {
        List<String> contextRoots = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher contextRoot = CONTEXT_ROOT.matcher(line);
            if (contextRoot.matches() && contextRoot.group(1).equals(name)) {
                contextRoots.add(contextRoot.group(2).trim());
            }
        }
        return contextRoots;
    }

    /** Whether the child has deployed the application, and said what the command asked. */
    public boolean isStarted() {
        return started;
    }

    /** Why the start failed, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** The name GlassFish deployed the application under. */
    public String name() {
        return name;
    }

    /** A WAR's context root, from the query; empty for other archives. */
    public List<String> contextRoots() {
        return contextRoots;
    }

    /**
     * The servlets of each web module, by its context root, starting with
     * {@code /}: GlassFish's for an EAR's module, or else its descriptor's.
     * Empty unless the command listed them.
     */
    public Map<String, List<String>> servlets() {
        return servlets;
    }
}
