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
package ee.omnifish.arquillian.container.glassfish;

import java.lang.System.Logger;
import java.util.List;

import static java.lang.System.Logger.Level.WARNING;
import static java.util.Arrays.copyOfRange;

/**
 * Runs the {@code glassfish.postBootCommands} list against a started GlassFish
 * via a container-supplied {@link AdminCommandExecutor}. Centralises the split
 * and the warn-and-continue policy so each container module only provides the
 * "how to run one command" half.
 */
public final class PostBootCommands {

    private static final Logger LOG = System.getLogger(PostBootCommands.class.getName());

    private PostBootCommands() {
    }

    /**
     * Run each post-boot command line through {@code executor}, warning and
     * continuing on failure. Warn-and-continue is intentional: post-boot
     * commands are typically idempotent setup (e.g. {@code create-file-user})
     * that fails benignly when re-run against an already-configured server,
     * which is the normal case for a recycled pool slot.
     */
    public static void execute(List<String> commandLines, AdminCommandExecutor executor) {
        for (String commandLine : commandLines) {
            String[] parts = commandLine.trim().split("\\s+");
            String command = parts[0];
            List<String> arguments = parts.length > 1
                    ? List.of(copyOfRange(parts, 1, parts.length))
                    : List.of();
            try {
                executor.run(command, arguments);
            } catch (Exception e) {
                LOG.log(WARNING, "Post boot command failed: " + commandLine, e);
            }
        }
    }
}
