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
package ee.omnifish.glassfish.admin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerLogTailerTest {

    @TempDir
    Path tmp;

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    @Test
    void startsAtTheEndOfAnExistingLog() throws IOException {
        Path log = write("before\n");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);

        append(log, "after\n");
        tailer.poll();

        assertEquals(List.of("after"), lines);
    }

    @Test
    void followsAMissingLogFromItsStartOnceItAppears() throws IOException {
        Path log = tmp.resolve("server.log");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);
        tailer.poll();

        append(log, "first\n");
        tailer.poll();

        assertEquals(List.of("first"), lines);
    }

    @Test
    void aLineWaitsForItsNewline() throws IOException {
        Path log = write("");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);

        append(log, "par");
        tailer.poll();
        assertEquals(List.of(), lines);

        append(log, "tial\r\n\nnext\n");
        tailer.poll();
        assertEquals(List.of("partial", "", "next"), lines);
    }

    @Test
    void aCharacterSplitAcrossReadsArrivesWhole() throws IOException {
        Path log = write("");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);
        byte[] bytes = "Grüße\n".getBytes(UTF_8);

        Files.write(log, Arrays.copyOfRange(bytes, 0, 3), APPEND);
        tailer.poll();
        Files.write(log, Arrays.copyOfRange(bytes, 3, bytes.length), APPEND);
        tailer.poll();

        assertEquals(List.of("Grüße"), lines);
    }

    /** As GlassFish rotates its log: the old file gets another name, and a new one takes its place. */
    @Test
    void aRotatedLogIsReadToItsEndAndTheNewOneFromItsStart() throws IOException {
        Path log = write("old start\n");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);
        append(log, "old end");

        Files.move(log, tmp.resolve("server.log_2026-11-17T10-00-00"));
        append(log, "new start\n");
        tailer.poll();

        assertEquals(List.of("old end", "new start"), lines);
    }

    @Test
    void aTruncatedLogIsReadFromItsStart() throws IOException {
        Path log = write("a line that's longer than what follows\n");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);

        Files.writeString(log, "short\n", TRUNCATE_EXISTING);
        tailer.poll();

        assertEquals(List.of("short"), lines);
    }

    @Test
    void finishingPassesOnALastLineWithoutItsNewline() throws IOException {
        Path log = write("");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);

        append(log, "done\nunfinished");
        tailer.finish();

        assertEquals(List.of("done", "unfinished"), lines);
    }

    @Test
    void aVeryLongLineIsPassedOnInParts() throws IOException {
        Path log = write("");
        ServerLogTailer tailer = new ServerLogTailer(log, lines::add);

        append(log, "x".repeat(ServerLogTailer.MAX_LINE_BYTES + 10) + "\n");
        tailer.poll();

        assertEquals(2, lines.size());
        assertEquals(ServerLogTailer.MAX_LINE_BYTES, lines.get(0).length());
        assertEquals("x".repeat(10), lines.get(1));
    }

    /** One warning, and no more lines for a consumer that can't take them. */
    @Test
    void aConsumerThatThrowsStopsTheFollowing() throws IOException {
        Path log = write("");
        List<String> offered = new ArrayList<>();
        ServerLogTailer tailer = new ServerLogTailer(log, line -> {
            offered.add(line);
            throw new IllegalStateException("closed");
        });

        append(log, "one\ntwo\n");
        tailer.poll();
        append(log, "three\n");
        tailer.poll();
        tailer.finish();

        assertEquals(List.of("one"), offered);
    }

    @Test
    void closingTheFollowerPassesOnWhatItHasNotReadYet() throws IOException {
        Path log = tmp.resolve("server.log");
        ServerLogTailer tailer = ServerLogTailer.follow(log, lines::add);

        append(log, "started\nstopping");
        tailer.close();
        tailer.close();

        assertEquals(List.of("started", "stopping"), lines);
    }

    @Test
    void aPrefixIsALabelInBrackets() {
        assertEquals("[glassfish:slot-1] ", ServerLogTailer.prefix("glassfish:slot-1", 0, false));
    }

    /** One colour per slot, so their lines can be told apart; after five, they repeat. */
    @Test
    void aColouredPrefixColoursOnlyTheLabel() {
        assertEquals("\u001b[36m[glassfish:slot-1]\u001b[0m ", ServerLogTailer.prefix("glassfish:slot-1", 0, true));
        assertEquals("\u001b[35m[glassfish:slot-2]\u001b[0m ", ServerLogTailer.prefix("glassfish:slot-2", 1, true));
        assertEquals("\u001b[36m[glassfish:slot-6]\u001b[0m ", ServerLogTailer.prefix("glassfish:slot-6", 5, true));
    }

    private Path write(String content) throws IOException {
        return Files.writeString(tmp.resolve("server.log"), content);
    }

    private static void append(Path log, String content) throws IOException {
        Files.writeString(log, content, CREATE, APPEND);
    }
}
