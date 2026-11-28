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
package ee.omnifish.arquillian.container.glassfish.embedded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The container booting GlassFish itself, without Arquillian, so in a JVM of
 * its own: the Arquillian tests keep theirs running until they end, and the
 * JVM hosts one at a time.
 */
class GlassFishContainerRestartTest {

    private static final Pattern HTTP_LISTENER = Pattern.compile("<network-listener\\b[^>]*\\bname=\"http-listener\"[^>]*>");

    @TempDir
    Path tmp;

    /** As in a Failsafe JVM that runs a second suite: stop() shuts GlassFish down, so it boots again. */
    @Test
    void itStartsAgainAfterItStopped() throws Exception {
        GlassFishConfiguration configuration = new GlassFishConfiguration();
        configuration.setBindHttpPort(7172);
        configuration.setBindHttpsPort(7173);

        GlassFishContainer container = started(configuration);
        try {
            assertEquals("hello", serve(container, 7172));
        } finally {
            container.stop();
        }
        container.start();
        try {
            assertEquals("hello", serve(container, 7172));
        } finally {
            container.stop();
        }
    }

    /** Set, the port replaces that of a domain.xml of the user's own, which it used to leave alone. */
    @Test
    void aSetPortAppliesAlsoWithADomainXmlOfItsOwn() throws Exception {
        GlassFishConfiguration configuration = new GlassFishConfiguration();
        configuration.setConfigurationXml(domainXml(7180).toString());
        configuration.setBindHttpPort(7174);

        GlassFishContainer container = started(configuration);
        try {
            assertEquals("hello", serve(container, 7174));
            assertThrows(ConnectException.class, () -> get(7180));
        } finally {
            container.stop();
        }
    }

    /** Unset, the domain.xml's own port stays, as before. */
    @Test
    void anUnsetPortLeavesADomainXmlsOwn() throws Exception {
        GlassFishConfiguration configuration = new GlassFishConfiguration();
        configuration.setConfigurationXml(domainXml(7181).toString());

        GlassFishContainer container = started(configuration);
        try {
            assertEquals("hello", serve(container, 7181));
        } finally {
            container.stop();
        }
    }

    private static GlassFishContainer started(GlassFishConfiguration configuration) throws Exception {
        configuration.validate();
        GlassFishContainer container = new GlassFishContainer();
        container.setup(configuration);
        container.start();
        return container;
    }

    /** Deploy a WAR, read its page on {@code port}, and undeploy it. */
    private static String serve(GlassFishContainer container, int port) throws Exception {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "hello.war")
                .addAsWebResource(new StringAsset("hello"), "index.html");
        container.deploy(war);
        try {
            return get(port);
        } finally {
            container.undeploy(war);
        }
    }

    private static String get(int port) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + port + "/hello/")
                .openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** Embedded's own domain.xml, from the test class path's GlassFish, with its HTTP listener on {@code port}. */
    private Path domainXml(int port) throws IOException {
        String domainXml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("org/glassfish/embed/domain.xml")) {
            domainXml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher listener = HTTP_LISTENER.matcher(domainXml);
        if (!listener.find()) {
            throw new IllegalStateException("Embedded's domain.xml has no http-listener");
        }
        String enabled = listener.group()
                .replaceFirst("\\bport=\"[^\"]*\"", "port=\"" + port + "\"")
                .replaceFirst("\\benabled=\"false\"", "enabled=\"true\"");
        return Files.writeString(tmp.resolve("domain.xml"), domainXml.substring(0, listener.start()) + enabled
                + domainXml.substring(listener.end()));
    }
}
