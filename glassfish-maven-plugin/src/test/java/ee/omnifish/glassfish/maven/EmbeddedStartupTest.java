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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link EmbeddedStartup} on the output of GlassFish Embedded 8.0.5, as recorded in {@code /embedded}. */
class EmbeddedStartupTest {

    @Test
    void aWarHasStartedOnceItsContextRootIsKnown() throws Exception {
        for (String recording : List.of("war-default.log", "war-messages.log")) {
            EmbeddedStartup startup = new EmbeddedStartup(true);

            int line = feedUntilStarted(startup, recording);

            assertThat(recording, startup.isStarted(), is(true));
            assertThat(recording, startup.name(), equalTo("shop"));
            assertThat(recording, startup.contextRoots(), contains("/descriptor-shop"));
            assertThat(recording, lines(recording).get(line), containsString("context-root=/descriptor-shop"));
        }
    }

    @Test
    void anEarHasStartedOnceItsDeployEnded() throws Exception {
        EmbeddedStartup startup = new EmbeddedStartup(false);

        feedUntilStarted(startup, "ear-messages.log");

        assertThat(startup.isStarted(), is(true));
        assertThat(startup.name(), equalTo("shop-ear"));
        assertThat(startup.contextRoots(), empty());
    }

    @Test
    void anEjbJarHasStartedOnceItsDeployEnded() throws Exception {
        EmbeddedStartup startup = new EmbeddedStartup(false);

        feedUntilStarted(startup, "ejb-default.log");

        assertThat(startup.isStarted(), is(true));
        assertThat(startup.name(), equalTo("bean"));
    }

    /** A record of another command, without a description, doesn't count. */
    @Test
    void otherCommandsDontStartIt() {
        EmbeddedStartup startup = new EmbeddedStartup(true);

        startup.accept("SUCCESS: PlainTextActionReporterSUCCESSNo monitoring data to report.");
        startup.accept("");
        startup.accept("SUCCESS: PlainTextActionReporterSUCCESSDescription: deploy AdminCommandApplication deployed "
                + "with name shop.");
        startup.accept("    [name=shop");
        startup.accept("");

        assertThat(startup.isStarted(), is(false));
        assertThat(startup.name(), equalTo("shop"));
    }

    /** As with GlassFish 7.0.x, which goes on after a failed command. */
    @Test
    void aQueryWithoutADeployFails() {
        EmbeddedStartup startup = new EmbeddedStartup(true);

        startup.accept("INFO: SUCCESS: PlainTextActionReporterSUCCESSDescription: get AdminCommandnull");
        startup.accept("");

        assertThat(startup.isStarted(), is(false));
        assertThat(startup.failure(), containsString("without deploying the application"));
        assertThat(startup.name(), nullValue());
    }

    /** Feed {@code recording} until {@code startup} has started, and return the index of the line it started after. */
    private static int feedUntilStarted(EmbeddedStartup startup, String recording) throws Exception {
        List<String> lines = lines(recording);
        for (int i = 0; i < lines.size(); i++) {
            startup.accept(lines.get(i));
            if (startup.isStarted()) {
                // The context root, or the name, came before the empty line that ended the record.
                return i - 1;
            }
        }

        return -1;
    }

    private static List<String> lines(String recording) throws IOException, URISyntaxException {
        return Files.readAllLines(Path.of(EmbeddedStartupTest.class.getResource("/embedded/" + recording).toURI()));
    }
}
