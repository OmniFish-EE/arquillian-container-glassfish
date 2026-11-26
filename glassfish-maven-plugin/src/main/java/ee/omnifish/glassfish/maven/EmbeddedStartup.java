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
package ee.omnifish.glassfish.maven;

import java.util.ArrayList;
import java.util.List;
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
 * <p>The startup commands before them may deploy and query too, so the
 * application's {@code deploy} is the one after as many as they have, and
 * its query likewise.
 *
 * <p>It takes the lines of the child's output in order, from one thread.
 */
final class EmbeddedStartup {

    private static final String SUCCESS = "SUCCESS: PlainTextActionReporterSUCCESSDescription: ";
    private static final String DEPLOY = SUCCESS + "deploy AdminCommand";
    private static final String QUERY = SUCCESS + "get AdminCommand";
    private static final Pattern NAME = Pattern.compile("\\s+\\[name=(.*)");
    private static final Pattern CONTEXT_ROOT = Pattern.compile(
            "\\s+applications\\.application\\.(.+)\\.context-root=(.*)");

    private enum Record { NONE, DEPLOY, QUERY }

    private final boolean war;
    private final int deploysBefore;
    private final int queriesBefore;
    private final List<String> contextRoots = new ArrayList<>();

    private Record record = Record.NONE;
    private int deploys;
    private int queries;
    private boolean deployed;
    private String name;
    private boolean started;
    private String failure;

    /**
     * @param war whether the child deploys a WAR, whose deploy a context-root query follows
     * @param commands the startup commands that run before the deploy
     */
    EmbeddedStartup(boolean war, List<String> commands) {
        this.war = war;
        deploysBefore = count(commands, "deploy");
        queriesBefore = count(commands, "get");
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

    void accept(String line) {
        if (started || failure != null) {
            return;
        }

        if (line.contains(DEPLOY)) {
            record = (++deploys > deploysBefore) ? Record.DEPLOY : Record.NONE;
        } else if (line.contains(QUERY)) {
            record = (++queries > queriesBefore) ? Record.QUERY : Record.NONE;
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
        }
    }

    private void end() {
        if (record == Record.DEPLOY) {
            deployed = true;
            started = !war;
        } else if (record == Record.QUERY) {
            if (deployed) {
                started = true;
            } else {
                // As with 7.0.x, which goes on after a command failed.
                failure = "GlassFish Embedded started without deploying the application";
            }
        }
        record = Record.NONE;
    }

    /** Whether the child has deployed the application, and for a WAR, said where. */
    boolean isStarted() {
        return started;
    }

    /** Why the start failed, or {@code null}. */
    String failure() {
        return failure;
    }

    /** The name GlassFish deployed the application under. */
    String name() {
        return name;
    }

    /** A WAR's context root, from the query; empty for other archives. */
    List<String> contextRoots() {
        return contextRoots;
    }
}
