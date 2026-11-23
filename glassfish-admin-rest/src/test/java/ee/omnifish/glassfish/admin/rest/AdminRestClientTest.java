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
package ee.omnifish.glassfish.admin.rest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a stand-in for the DAS, which answers as GlassFish does, and records what it's asked. */
class AdminRestClientTest {

    private static final String APPLICATIONS = "/management/domain/applications/application";

    private static final String DEPLOYED = map(entry("exit_code", "SUCCESS")
            + "<entry key=\"properties\">" + map(entry("name", "assigned")) + "</entry>");

    private static final String REJECTED = map(entry("exit_code", "FAILURE")
            + entry("message", "Application with name app is already registered."));

    private final Map<String, Answer> answers = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theDeploySendsTheArchiveAndTheOptionsThatAreSet() {
        answers.put("POST " + APPLICATIONS, new Answer(200, DEPLOYED));

        String name = client().deploy(new ByteArrayInputStream("the archive".getBytes(UTF_8)), "app.war",
                new DeployOptions().contextRoot("/ctx").force(true));

        assertEquals("assigned", name);
        String body = bodies.get(0);
        assertTrue(body.contains("filename=\"app.war\"") && body.contains("name=\"id\""), body);
        assertTrue(body.contains("the archive"), body);
        assertField(body, "target", "server");
        assertField(body, "contextroot", "/ctx");
        assertField(body, "force", "true");
        assertFalse(body.contains("name=\"name\""), body);
        assertFalse(body.contains("name=\"libraries\""), body);
        assertFalse(body.contains("name=\"availabilityenabled\""), body);
    }

    /** It fails with GlassFish's answer, and asks nothing further. */
    @Test
    void aRejectedDeploymentFails() {
        answers.put("POST " + APPLICATIONS, new Answer(400, REJECTED));

        GlassFishClientException e = assertThrows(GlassFishClientException.class,
                () -> client().deploy(new ByteArrayInputStream(new byte[1]), "app.war", new DeployOptions()));

        assertTrue(e.getMessage().contains("deploying application app"), e.getMessage());
        assertEquals(List.of("POST " + APPLICATIONS), requests);
    }

    /** Not as JAX-RS's exception, which is relocated in this jar, out of its users' reach. */
    @Test
    void aDasThatDoesNotAnswerIsAGlassFishClientException() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        AdminRestClient client = new AdminRestClient(AdminConnection.local(port));

        assertFalse(client.isDASRunning());
        assertThrows(GlassFishClientException.class,
                () -> client.deploy(new ByteArrayInputStream(new byte[1]), "app.war", new DeployOptions()));
    }

    @Test
    void theContextRootsAreThoseOfTheWebModulesOrElseTheApplications() {
        answers.put("GET " + APPLICATIONS + "/ear/list-sub-components", new Answer(200, map(
                entry("exit_code", "SUCCESS")
                + "<entry key=\"properties\">" + map(entry("one.war", "WebModule") + entry("ejb.jar", "EJBModule"))
                + "</entry>"
                + "<entry key=\"children\"><list>"
                + map("<entry key=\"properties\">" + map(entry("moduleInfo", "one.war:web:/site-one")) + "</entry>")
                + map("<entry key=\"properties\">" + map(entry("moduleInfo", "ejb.jar:ejb:null")) + "</entry>")
                + "</list></entry>")));
        answers.put("GET " + APPLICATIONS + "/war/list-sub-components", new Answer(200, map(
                entry("exit_code", "SUCCESS")
                + "<entry key=\"properties\">" + map(entry("Faces Servlet", "Servlet")) + "</entry>")));
        answers.put("GET " + APPLICATIONS + "/war", new Answer(200, map(
                entry("exit_code", "SUCCESS")
                + "<entry key=\"extraProperties\">"
                + map("<entry key=\"entity\">" + map(entry("contextRoot", "run-it")) + "</entry>")
                + "</entry>")));
        answers.put("GET " + APPLICATIONS + "/ejb/list-sub-components", new Answer(200, map(
                entry("exit_code", "SUCCESS")
                + "<entry key=\"properties\">" + map(entry("Bean", "StatelessSessionBean")) + "</entry>")));
        answers.put("GET " + APPLICATIONS + "/ejb", new Answer(200, map(
                entry("exit_code", "SUCCESS")
                + "<entry key=\"extraProperties\">" + map("<entry key=\"entity\">" + map("") + "</entry>")
                + "</entry>")));

        AdminRestClient client = client();

        assertEquals(List.of("/site-one"), client.webContextRoots("ear"));
        assertEquals(List.of("/run-it"), client.webContextRoots("war"));
        assertEquals(List.of(), client.webContextRoots("ejb"));
    }

    private AdminRestClient client() {
        return new AdminRestClient(AdminConnection.local(server.getAddress().getPort()));
    }

    private void answer(HttpExchange exchange) throws IOException {
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(request);
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));

        Answer answer = answers.getOrDefault(request, new Answer(404, ""));
        byte[] body = answer.body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(answer.status, (body.length == 0) ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void assertField(String body, String field, String value) {
        assertTrue(body.matches("(?s).*name=\"" + field + "\"\\r?\\n(?:[^\\r\\n]+\\r?\\n)*\\r?\\n"
                + Pattern.quote(value) + "\\r?\\n.*"), field + " = " + value + " in " + body);
    }

    private static String map(String entries) {
        return "<map>" + entries + "</map>";
    }

    private static String entry(String key, String value) {
        return "<entry key=\"" + key + "\" value=\"" + value + "\"/>";
    }

    private static final class Answer {

        final int status;
        final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
