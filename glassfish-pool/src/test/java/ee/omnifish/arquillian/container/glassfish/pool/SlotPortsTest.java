/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlotPortsTest {

    @Test
    void roundTripsThroughDisk(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("ports.properties");
        SlotPorts original = new SlotPorts(14948, 14949, 14950, "/some/glassfish");
        original.writeTo(file);

        SlotPorts read = SlotPorts.readFrom(file);
        assertThat(read.adminPort(), equalTo(14948));
        assertThat(read.httpPort(), equalTo(14949));
        assertThat(read.httpsPort(), equalTo(14950));
        assertThat(read.glassFishHome(), equalTo("/some/glassfish"));
    }

    @Test
    void writeIsAtomicallyVisible(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("ports.properties");
        new SlotPorts(1, 2, 3, "x").writeTo(file);
        // The .tmp sibling must not be left behind.
        assertThat(Files.exists(file.resolveSibling("ports.properties.tmp")), equalTo(false));
    }

    @Test
    void rejectsMissingProperty(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("partial.properties");
        Files.writeString(file, "glassfish.adminPort=14948\n");
        assertThrows(IllegalStateException.class, () -> SlotPorts.readFrom(file));
    }

    /**
     * One writer alternates between two known port sets while many readers
     * loop {@link SlotPorts#readFrom}. The writer's {@code tmp + ATOMIC_MOVE}
     * dance must guarantee every reader sees either set in full — never a
     * half-written file (which would surface as missing-property
     * {@code IllegalStateException}). The slot lock in production keeps
     * writers single, so this single-writer / many-reader shape matches the
     * actual concurrency contract.
     */
    @Test
    void concurrentReadersNeverSeePartialWrite(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ports.properties");
        SlotPorts a = new SlotPorts(10000, 10001, 10002, "/a");
        SlotPorts b = new SlotPorts(20000, 20001, 20002, "/b");
        a.writeTo(file); // ensure the file exists before readers start

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int readers = 6;
        ExecutorService exec = Executors.newFixedThreadPool(readers + 1);
        try {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            tasks.add(CompletableFuture.runAsync(() -> {
                try {
                    boolean toggle = false;
                    for (int i = 0; i < 500 && !stop.get(); i++) {
                        (toggle ? a : b).writeTo(file);
                        toggle = !toggle;
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, exec));
            for (int r = 0; r < readers; r++) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        while (!stop.get()) {
                            SlotPorts read = SlotPorts.readFrom(file);
                            // Must match one of the two known sets in full —
                            // no field carried over from the other.
                            assertThat(read.adminPort(),
                                    anyOf(equalTo(10000), equalTo(20000)));
                            int expectedBase = read.adminPort();
                            assertThat(read.httpPort(), equalTo(expectedBase + 1));
                            assertThat(read.httpsPort(), equalTo(expectedBase + 2));
                            assertThat(read.glassFishHome(),
                                    equalTo(expectedBase == 10000 ? "/a" : "/b"));
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        stop.set(true);
                    }
                }, exec));
            }
            // First future is the writer; await it, then signal readers to stop.
            tasks.get(0).get(30, TimeUnit.SECONDS);
            stop.set(true);
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(failure.get(), nullValue());
    }

    @Test
    void rejectsNonNumericPort(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("bad.properties");
        Files.writeString(file,
                "glassfish.adminPort=oops\n"
                + "glassfish.httpPort=2\n"
                + "glassfish.httpsPort=3\n"
                + "glassfish.home=x\n");
        assertThrows(IllegalStateException.class, () -> SlotPorts.readFrom(file));
    }
}
