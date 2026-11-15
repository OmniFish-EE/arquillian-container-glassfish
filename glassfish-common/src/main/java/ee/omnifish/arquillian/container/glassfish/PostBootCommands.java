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
import java.util.ArrayList;
import java.util.List;

import static java.lang.System.Logger.Level.WARNING;
import static java.util.Arrays.copyOfRange;

/**
 * Runs the {@code glassfish.postBootCommands} list against a started GlassFish
 * via a container-supplied {@link AdminCommandExecutor}. Centralises the split
 * and the warn-and-continue policy so each container module only provides the
 * "how to run one command" half.
 *
 * <p>The pool plugin's command lists go through {@link #run} instead, which
 * splits lines with the quote-aware {@link #words} and can stop at the first
 * failure.
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

    /**
     * Run each command line through {@code executor}, in order, split by
     * {@link #words}. With {@code failOnError}, the first failure stops the
     * list and is thrown as a {@link CommandFailedException}; otherwise each
     * failure is logged and the list continues.
     */
    public static void run(List<String> commandLines, AdminCommandExecutor executor, boolean failOnError) {
        for (String commandLine : commandLines) {
            List<String> words = words(commandLine);
            if (words.isEmpty()) {
                continue;
            }

            try {
                executor.run(words.get(0), words.subList(1, words.size()));
            } catch (Exception e) {
                if (failOnError) {
                    throw new CommandFailedException(commandLine, e);
                }
                LOG.log(WARNING, "Command failed: " + commandLine, e);
            }
        }
    }

    /**
     * Split a command line into words. Whitespace separates words, and single
     * or double quotes group them: the quoted text, spaces included, becomes
     * part of the current word, without the quotes. Backslashes stay as they
     * are, because {@code asadmin} reads {@code \:} and {@code \=} itself.
     *
     * @throws IllegalArgumentException if a quote isn't closed
     */
    public static List<String> words(String commandLine) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (char c : commandLine.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unclosed " + quote + " in command: " + commandLine);
        }
        if (inWord) {
            words.add(word.toString());
        }

        return words;
    }

    /** A command failed while {@link #run} was told to stop at the first failure. */
    public static final class CommandFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CommandFailedException(String commandLine, Throwable cause) {
            super("Command failed: " + commandLine + ": " + cause.getMessage(), cause);
        }
    }
}
