/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
