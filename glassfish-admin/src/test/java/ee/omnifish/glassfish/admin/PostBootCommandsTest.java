/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ee.omnifish.glassfish.admin.PostBootCommands.CommandFailedException;

class PostBootCommandsTest {

    @Test
    void splitsCommandFromArguments() {
        List<String[]> captured = new ArrayList<>();
        PostBootCommands.execute(
                List.of("--passwordfile /tmp/javajoe.pass create-file-user --groups Manager:Employee javajoe"),
                (command, args) -> captured.add(prepend(command, args)));

        assertEquals(1, captured.size());
        assertArrayEquals(
                new String[] { "--passwordfile", "/tmp/javajoe.pass", "create-file-user", "--groups", "Manager:Employee", "javajoe" },
                captured.get(0));
    }

    @Test
    void collapsesRepeatedWhitespace() {
        List<String[]> captured = new ArrayList<>();
        PostBootCommands.execute(List.of("  create-file-user   --groups  Employee  bob  "),
                (command, args) -> captured.add(prepend(command, args)));

        assertArrayEquals(new String[] { "create-file-user", "--groups", "Employee", "bob" }, captured.get(0));
    }

    @Test
    void passesCommandWithoutArguments() {
        List<String[]> captured = new ArrayList<>();
        PostBootCommands.execute(List.of("list-file-users"),
                (command, args) -> captured.add(prepend(command, args)));

        assertArrayEquals(new String[] { "list-file-users" }, captured.get(0));
    }

    @Test
    void warnsAndContinuesWhenACommandFails() {
        List<String> run = new ArrayList<>();
        PostBootCommands.execute(List.of("first", "boom", "third"), (command, args) -> {
            run.add(command);
            if ("boom".equals(command)) {
                throw new IllegalStateException("user already exists");
            }
        });

        assertEquals(List.of("first", "boom", "third"), run);
    }

    @Test
    void emptyListRunsNothing() {
        PostBootCommands.execute(List.of(), (command, args) -> {
            throw new AssertionError("should not be called");
        });
    }

    // --- run(), for the pool plugin's lists ---

    @Test
    void wordsGroupQuotedText() {
        assertEquals(List.of("create-file-user", "--groups", "Manager Employee", "joe"),
                PostBootCommands.words("create-file-user --groups \"Manager Employee\" joe"));
        assertEquals(List.of("set", "a=it's"), PostBootCommands.words("set \"a=it's\""));
        assertEquals(List.of("--property", "a=b c:d"), PostBootCommands.words("--property a='b c':d"));
        assertEquals(List.of("x", ""), PostBootCommands.words("x ''"));
        assertEquals(List.of(), PostBootCommands.words("   "));
    }

    /** asadmin reads backslash escapes such as {@code \:} itself. */
    @Test
    void wordsKeepBackslashes() {
        assertEquals(List.of("create-jvm-options", "-Dhosts=a\\:b", "\\\""),
                PostBootCommands.words("create-jvm-options -Dhosts=a\\:b '\\\"'"));
    }

    @Test
    void wordsRejectAnUnclosedQuote() {
        assertThrows(IllegalArgumentException.class, () -> PostBootCommands.words("set \"a=b"));
    }

    @Test
    void runPassesQuotedTextAsOneArgument() {
        List<String[]> captured = new ArrayList<>();
        PostBootCommands.run(List.of("create-file-user --groups 'a b' joe"),
                (command, args) -> captured.add(prepend(command, args)), true);

        assertArrayEquals(new String[] { "create-file-user", "--groups", "a b", "joe" }, captured.get(0));
    }

    @Test
    void runStopsAtTheFirstFailureWhenToldTo() {
        List<String> run = new ArrayList<>();
        CommandFailedException e = assertThrows(CommandFailedException.class,
                () -> PostBootCommands.run(List.of("first", "boom now", "third"), (command, args) -> {
                    run.add(command);
                    if ("boom".equals(command)) {
                        throw new IllegalStateException("user already exists");
                    }
                }, true));

        assertEquals(List.of("first", "boom"), run);
        assertTrue(e.getMessage().contains("boom now"), e.getMessage());
        assertTrue(e.getMessage().contains("user already exists"), e.getMessage());
    }

    @Test
    void runContinuesAfterAFailureOtherwise() {
        List<String> run = new ArrayList<>();
        PostBootCommands.run(List.of("first", "boom", "third"), (command, args) -> {
            run.add(command);
            if ("boom".equals(command)) {
                throw new IllegalStateException("user already exists");
            }
        }, false);

        assertEquals(List.of("first", "boom", "third"), run);
    }

    private static String[] prepend(String command, List<String> args) {
        List<String> all = new ArrayList<>();
        all.add(command);
        all.addAll(args);
        return all.toArray(String[]::new);
    }
}
