/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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

    private static String[] prepend(String command, List<String> args) {
        List<String> all = new ArrayList<>();
        all.add(command);
        all.addAll(args);
        return all.toArray(String[]::new);
    }
}
