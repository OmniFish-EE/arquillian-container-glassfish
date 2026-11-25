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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ServerLogMessagesTest {

    private static final String HEADER = "[2026-11-24T19:21:21.649283+01:00] [GF 9.0.0-SNAPSHOT] [INFO] []"
            + " [org.glassfish.soteria.servlet.SamRegistrationInstaller]"
            + " [tid: _ThreadID=77 _ThreadName=admin-listener(4)] [levelValue: 800] [[";

    private final List<String> messages = new ArrayList<>();
    private final ServerLogMessages log = new ServerLogMessages(messages::add);

    @Test
    void aRecordIsItsMessage() {
        take(HEADER,
                "  Initializing Soteria 5.0.0-M3 for context '/app-mem-basic']]",
                "",
                HEADER,
                "  Loaded server named: server]]",
                "");

        assertEquals(List.of("Initializing Soteria 5.0.0-M3 for context '/app-mem-basic'",
                "Loaded server named: server"), messages);
    }

    @Test
    void aMessageKeepsTheBracketsItEndsWith() {
        take(HEADER, "  Grizzly Framework 5.0.3 started in: 17 ms - bound to [/0.0.0.0:14849]]]", "");

        assertEquals(List.of("Grizzly Framework 5.0.3 started in: 17 ms - bound to [/0.0.0.0:14849]"), messages);
    }

    @Test
    void aMessageOfSeveralLinesKeepsItsOtherLines() {
        take(HEADER,
                "  Internal Server error: /management/domain/applications/application/app",
                "java.lang.RuntimeException: java.lang.InterruptedException",
                "\tat org.glassfish.admin.rest.adapter.RestAdapter.reportError(RestAdapter.java:339)",
                "",
                "\tat org.glassfish.admin.rest.adapter.RestAdapter.service(RestAdapter.java:171)",
                "]]",
                "");

        assertEquals(List.of("Internal Server error: /management/domain/applications/application/app",
                "java.lang.RuntimeException: java.lang.InterruptedException",
                "\tat org.glassfish.admin.rest.adapter.RestAdapter.reportError(RestAdapter.java:339)",
                "",
                "\tat org.glassfish.admin.rest.adapter.RestAdapter.service(RestAdapter.java:171)"), messages);
    }

    @Test
    void aRecordOnOneLineIsItsMessage() {
        take(HEADER + "Loaded server named: server]]", "");

        assertEquals(List.of("Loaded server named: server"), messages);
    }

    @Test
    void linesOutsideARecordArePassedOnAsTheyAre() {
        take("Waiting for domain1 to start",
                "",
                "[#|2026-11-24T19:21:21.649+0100|INFO|glassfish|Loaded server named: server|#]",
                HEADER,
                "  Loaded server named: server]]",
                "",
                "",
                "after");

        assertEquals(List.of("Waiting for domain1 to start",
                "",
                "[#|2026-11-24T19:21:21.649+0100|INFO|glassfish|Loaded server named: server|#]",
                "Loaded server named: server",
                "",
                "after"), messages);
    }

    private void take(String... lines) {
        for (String line : lines) {
            log.accept(line);
        }
    }
}
