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
package ee.omnifish.glassfish.admin.embedded;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** {@link EmbeddedStartup} on the output of GlassFish Embedded 8.0.5, as recorded in {@code /embedded}. */
class EmbeddedStartupTest {

    @Test
    void aWarHasStartedOnceItsContextRootIsKnown() throws Exception {
        for (String recording : List.of("war-default.log", "war-messages.log")) {
            EmbeddedStartup startup = new EmbeddedStartup(true, List.of());

            int line = feedUntilStarted(startup, recording);

            assertThat(recording, startup.isStarted(), is(true));
            assertThat(recording, startup.name(), equalTo("shop"));
            assertThat(recording, startup.contextRoots(), contains("/descriptor-shop"));
            assertThat(recording, lines(recording).get(line), containsString("context-root=/descriptor-shop"));
        }
    }

    @Test
    void anEarHasStartedOnceItsDeployEnded() throws Exception {
        EmbeddedStartup startup = new EmbeddedStartup(false, List.of());

        feedUntilStarted(startup, "ear-messages.log");

        assertThat(startup.isStarted(), is(true));
        assertThat(startup.name(), equalTo("shop-ear"));
        assertThat(startup.contextRoots(), empty());
    }

    @Test
    void anEjbJarHasStartedOnceItsDeployEnded() throws Exception {
        EmbeddedStartup startup = new EmbeddedStartup(false, List.of());

        feedUntilStarted(startup, "ejb-default.log");

        assertThat(startup.isStarted(), is(true));
        assertThat(startup.name(), equalTo("bean"));
    }

    /**
     * Startup commands that query, deploy a helper, then query again, come
     * before the application's deploy and query, which lists both.
     */
    @Test
    void theStartupCommandsDeploysAndQueriesDontCount() throws Exception {
        List<String> commands = List.of(
                "get configs.config.server-config.network-config.network-listeners.network-listener.*.port",
                "deploy alpha.war", "get applications.application.*.context-root");
        for (String recording : List.of("war-startup-commands-default.log", "war-startup-commands-messages.log")) {
            EmbeddedStartup startup = new EmbeddedStartup(true, commands);

            int line = feedUntilStarted(startup, recording);

            assertThat(recording, startup.failure(), nullValue());
            assertThat(recording, startup.isStarted(), is(true));
            assertThat(recording, startup.name(), equalTo("shop"));
            assertThat(recording, startup.contextRoots(), contains("/descriptor-shop"));
            assertThat(recording, lines(recording).get(line), containsString("context-root=/alpha"));
        }
    }

    /** With its servlets listed, a WAR has started once they are known, by its context root. */
    @Test
    void aWarsServletsAreKnownOnceListed() throws Exception {
        for (String recording : List.of("war-servlets-default.log", "war-servlets-messages.log")) {
            EmbeddedStartup startup = new EmbeddedStartup(true, List.of(), Map.of());

            int line = feedUntilStarted(startup, recording);

            assertThat(recording, startup.isStarted(), is(true));
            assertThat(recording, startup.name(), equalTo("shop"));
            assertThat(recording, startup.contextRoots(), contains("/shop"));
            assertThat(recording, startup.servlets().keySet(), contains("/shop"));
            assertThat(recording, startup.servlets().get("/shop"),
                    containsInAnyOrder("default", "probe.ShopServlet", "jsp", "FragmentRunner"));
            assertThat(recording, lines(recording).get(line), containsString("<Servlet>"));
        }
    }

    /**
     * An EAR's modules have the context roots GlassFish gave them, rather
     * than the descriptor's, which only stand in when GlassFish doesn't say.
     */
    @Test
    void anEarsServletsAreKnownByTheContextRootsGlassFishGave() throws Exception {
        Map<String, String> webModules = new LinkedHashMap<>();
        webModules.put("alpha.war", "/from-descriptor");
        webModules.put("beta.war", "/beta-from-descriptor");
        for (String recording : List.of("ear-servlets-default.log", "ear-servlets-messages.log")) {
            EmbeddedStartup startup = new EmbeddedStartup(false, List.of(), webModules);

            feedUntilStarted(startup, recording);

            assertThat(recording, startup.isStarted(), is(true));
            assertThat(recording, startup.name(), equalTo("pairapp"));
            assertThat(recording, startup.servlets().keySet(), contains("/alpha", "/beta-root"));
            for (List<String> servlets : startup.servlets().values()) {
                assertThat(recording, servlets, containsInAnyOrder("default", "jsp", "probe.HelloServlet"));
            }
        }
    }

    /** A startup command that lists subcomponents comes before the application's own listing. */
    @Test
    void theStartupCommandsListingsDontCount() {
        EmbeddedStartup startup = new EmbeddedStartup(true, List.of("list-sub-components helper"), Map.of());

        startup.accept("SUCCESS: PlainTextActionReporterSUCCESSDescription: list-sub-components AdminCommandnull");
        startup.accept("    [helper.Servlet=Servlet");
        startup.accept("");
        startup.accept("SUCCESS: PlainTextActionReporterSUCCESSDescription: deploy AdminCommandApplication deployed "
                + "with name shop.");
        startup.accept("    [name=shop");
        startup.accept("");
        startup.accept("SUCCESS: PlainTextActionReporterSUCCESSDescription: get AdminCommandnull");
        startup.accept("    applications.application.shop.context-root=/shop");
        startup.accept("");
        assertThat(startup.isStarted(), is(false));

        startup.accept("SUCCESS: PlainTextActionReporterSUCCESSDescription: list-sub-components AdminCommandnull");
        startup.accept("    [probe.ShopServlet=Servlet");
        startup.accept("");

        assertThat(startup.isStarted(), is(true));
        assertThat(startup.servlets(), equalTo(Map.of("/shop", List.of("probe.ShopServlet"))));
    }

    /** A record of another command, without a description, doesn't count. */
    @Test
    void otherCommandsDontStartIt() {
        EmbeddedStartup startup = new EmbeddedStartup(true, List.of());

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
        EmbeddedStartup startup = new EmbeddedStartup(true, List.of());

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
