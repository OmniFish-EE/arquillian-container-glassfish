/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.admin;

import static ee.omnifish.glassfish.admin.DomainXmlEditor.ADMIN_LISTENER;
import static ee.omnifish.glassfish.admin.DomainXmlEditor.HTTPS_LISTENER;
import static ee.omnifish.glassfish.admin.DomainXmlEditor.HTTP_LISTENER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainXmlEditorTest {

    private static final String FRAGMENT =
            "<network-listeners>"
            + "<network-listener address=\"0.0.0.0\" port=\"4848\" protocol=\"admin-listener\" "
            + "transport=\"tcp\" name=\"admin-listener\" thread-pool=\"admin-thread-pool\"></network-listener>"
            + "<network-listener address=\"0.0.0.0\" port=\"8080\" protocol=\"http-listener-1\" "
            + "transport=\"tcp\" name=\"http-listener-1\" thread-pool=\"http-thread-pool\"></network-listener>"
            + "<network-listener address=\"0.0.0.0\" port=\"8181\" protocol=\"http-listener-2\" "
            + "transport=\"tcp\" name=\"http-listener-2\" thread-pool=\"http-thread-pool\"></network-listener>"
            + "</network-listeners>";

    /** A domain whose server runs with {@code server-config}, which has the listeners. */
    private static final String DOMAIN = domain("server-config", FRAGMENT);

    @Test
    void rewritesAllThreePorts(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, DOMAIN);

        DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950);

        String content = Files.readString(domainXml);
        assertTrue(content.contains("port=\"14948\""), content);
        assertTrue(content.contains("port=\"14949\""), content);
        assertTrue(content.contains("port=\"14950\""), content);
        assertFalse(content.contains("port=\"4848\""), content);
        assertFalse(content.contains("port=\"8080\""), content);
        assertFalse(content.contains("port=\"8181\""), content);
    }

    @Test
    void replacesAPlaceholderInTheServersConfig(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, DOMAIN.replace("port=\"4848\"", "port=\"${ADMIN_PORT}\""));

        DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950);

        assertEquals(Map.of(ADMIN_LISTENER, "14948", HTTP_LISTENER, "14949", HTTPS_LISTENER, "14950"),
                DomainXmlEditor.ports(domainXml));
    }

    /** The stock {@code domain.xml}'s {@code default-config} is a template, with placeholder ports. */
    @Test
    void leavesOtherConfigsAlone(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        String template = "<config name=\"default-config\"><network-config>"
                + FRAGMENT.replace("port=\"4848\"", "port=\"${ASADMIN_LISTENER_PORT}\"")
                + "</network-config></config>";
        Files.writeString(domainXml, DOMAIN.replace("</configs>", template + "</configs>"));

        DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950);

        String content = Files.readString(domainXml);
        String templatePart = content.substring(content.indexOf("default-config"));
        assertTrue(templatePart.contains("port=\"${ASADMIN_LISTENER_PORT}\""), content);
        assertTrue(templatePart.contains("port=\"8080\""), content);
        assertEquals("14948", DomainXmlEditor.ports(domainXml).get(ADMIN_LISTENER));
    }

    @Test
    void followsTheServersConfigRef(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><servers><server name=\"server\" config-ref=\"das-config\"/></servers>"
                + "<configs>"
                + "<config name=\"server-config\"><network-config>" + FRAGMENT + "</network-config></config>"
                + "<config name=\"das-config\"><network-config>" + FRAGMENT + "</network-config></config>"
                + "</configs></domain>");

        DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950);

        String content = Files.readString(domainXml);
        int dasConfig = content.indexOf("das-config\">");
        assertTrue(content.substring(0, dasConfig).contains("port=\"4848\""), content);
        assertTrue(content.substring(dasConfig).contains("port=\"14948\""), content);
    }

    @Test
    void readsThePortsAsWritten(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, DOMAIN.replace("port=\"4848\"", "port=\"${ADMIN_PORT}\""));

        assertEquals(Map.of(ADMIN_LISTENER, "${ADMIN_PORT}", HTTP_LISTENER, "8080", HTTPS_LISTENER, "8181"),
                DomainXmlEditor.ports(domainXml));
    }

    /** As the DAS resolves it: the server's own system property first, then its config's, then the domain's. */
    @Test
    void resolvesAPlaceholderWithTheDasSystemProperty(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><system-property name=\"ADMIN_PORT\" value=\"1\"/>"
                + "<servers><server name=\"server\" config-ref=\"server-config\">"
                + "<system-property name=\"ADMIN_PORT\" value=\"24848\"/></server></servers>"
                + "<configs><config name=\"server-config\"><system-property name=\"ADMIN_PORT\" value=\"2\"/>"
                + "<network-config>" + FRAGMENT.replace("port=\"4848\"", "port=\"${ADMIN_PORT}\"")
                + "</network-config></config></configs></domain>");

        assertEquals("24848", DomainXmlEditor.resolve(domainXml, DomainXmlEditor.ports(domainXml).get(ADMIN_LISTENER)));
        assertEquals("8080", DomainXmlEditor.resolve(domainXml, "8080"));
        assertEquals("${UNDEFINED}", DomainXmlEditor.resolve(domainXml, "${UNDEFINED}"));
    }

    @Test
    void putsAPortBackAsItWasRead(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, DOMAIN.replace("port=\"4848\"", "port=\"${ADMIN_PORT}\""));
        String admin = DomainXmlEditor.ports(domainXml).get(ADMIN_LISTENER);
        DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950);

        DomainXmlEditor.setPorts(domainXml, Map.of(ADMIN_LISTENER, admin));

        assertEquals(Map.of(ADMIN_LISTENER, "${ADMIN_PORT}", HTTP_LISTENER, "14949", HTTPS_LISTENER, "14950"),
                DomainXmlEditor.ports(domainXml));
    }

    @Test
    void aDomainWithoutTheServersConfigFails(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, domain("other-config", FRAGMENT));

        IOException e = assertThrows(IOException.class, () -> DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950));

        assertTrue(e.getMessage().contains("server-config"), e.getMessage());
    }

    /** GlassFish's own debug options, as in a stock {@code server-config}. */
    private static final String DEBUG_OPTIONS = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:9009";

    @Test
    void readsTheServersDebugAddress(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><configs><config name=\"server-config\">"
                + "<java-config debug-options=\"" + DEBUG_OPTIONS + "\"/></config>"
                + "<config name=\"default-config\"><java-config debug-options=\""
                + DEBUG_OPTIONS.replace("9009", "${JAVA_DEBUGGER_PORT}") + "\"/></config></configs></domain>");

        assertEquals("*:9009", DomainXmlEditor.debugAddress(domainXml));
    }

    @Test
    void resolvesAPlaceholderInTheDebugAddress(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><configs><config name=\"server-config\">"
                + "<system-property name=\"JAVA_DEBUGGER_PORT\" value=\"29009\"/>"
                + "<java-config debug-options=\"" + DEBUG_OPTIONS.replace("9009", "${JAVA_DEBUGGER_PORT}") + "\"/>"
                + "</config></configs></domain>");

        assertEquals("*:29009", DomainXmlEditor.debugAddress(domainXml));
    }

    @Test
    void setsTheDebugAddressOfTheServersConfigOnly(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><configs><config name=\"server-config\">"
                + "<java-config debug-options=\"" + DEBUG_OPTIONS + "\"/></config>"
                + "<config name=\"default-config\"><java-config debug-options=\"" + DEBUG_OPTIONS + "\"/></config>"
                + "</configs></domain>");

        DomainXmlEditor.setDebugAddress(domainXml, "localhost:14857");

        String content = Files.readString(domainXml);
        assertTrue(content.contains("debug-options=\"" + DEBUG_OPTIONS.replace("*:9009", "localhost:14857") + "\""),
                content);
        assertTrue(content.substring(content.indexOf("default-config")).contains(DEBUG_OPTIONS), content);
        assertEquals("localhost:14857", DomainXmlEditor.debugAddress(domainXml));
    }

    @Test
    void setsTheDebugAddressWithoutDebugOptions(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><configs><config name=\"server-config\">"
                + "<java-config><jvm-options>-Xmx512m</jvm-options></java-config></config></configs></domain>");

        DomainXmlEditor.setDebugAddress(domainXml, "localhost:14857");

        assertEquals("localhost:14857", DomainXmlEditor.debugAddress(domainXml));
        assertTrue(Files.readString(domainXml).contains(DEBUG_OPTIONS.replace("*:9009", "localhost:14857")));
    }

    private static final String JAVA_CONFIG_FRAGMENT =
            "<java-config>"
            + "<jvm-options>-Xmx512m</jvm-options>"
            + "<jvm-options>-Djavax.net.ssl.trustStore=cacerts.p12</jvm-options>"
            + "</java-config>";

    @Test
    void appendsNewSystemProperty(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setSystemProperties(domainXml,
                List.of("javax.net.ssl.trustStorePassword=changeit"));

        String content = Files.readString(domainXml);
        assertTrue(content.contains(
                "<jvm-options>-Djavax.net.ssl.trustStorePassword=changeit</jvm-options>"), content);
        assertTrue(content.contains("<jvm-options>-Xmx512m</jvm-options>"), content);
    }

    @Test
    void replacesExistingSystemPropertyWithSameKey(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setSystemProperties(domainXml,
                List.of("javax.net.ssl.trustStore=elsewhere.jks"));

        String content = Files.readString(domainXml);
        assertTrue(content.contains(
                "<jvm-options>-Djavax.net.ssl.trustStore=elsewhere.jks</jvm-options>"), content);
        assertFalse(content.contains(
                "<jvm-options>-Djavax.net.ssl.trustStore=cacerts.p12</jvm-options>"), content);
    }

    @Test
    void systemPropertyIsIdempotentWhenAlreadySet(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);
        long mtimeBefore = Files.getLastModifiedTime(domainXml).toMillis();

        // Sleep just enough that a second write would advance mtime; the
        // contract is that no write happens, so mtime stays put.
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        DomainXmlEditor.setSystemProperties(domainXml,
                List.of("javax.net.ssl.trustStore=cacerts.p12"));

        long mtimeAfter = Files.getLastModifiedTime(domainXml).toMillis();
        assertEquals(mtimeBefore, mtimeAfter);
    }

    @Test
    void bareSystemPropertyKeyIsIdempotent(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml,
                "<java-config><jvm-options>-Dsomeflag</jvm-options></java-config>");

        DomainXmlEditor.setSystemProperties(domainXml, List.of("someflag"));

        // Should still be a single occurrence — no duplicate appended.
        String content = Files.readString(domainXml);
        int count = content.split("-Dsomeflag", -1).length - 1;
        assertEquals(1, count);
    }

    @Test
    void emptyOrNullSystemPropertiesIsNoop(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setSystemProperties(domainXml, List.of());
        DomainXmlEditor.setSystemProperties(domainXml, null);

        assertEquals(JAVA_CONFIG_FRAGMENT, Files.readString(domainXml));
    }

    @Test
    void replacesExistingJvmOptionByPrefix(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setJvmOptions(domainXml, Map.of("-Xmx", "-Xmx2g"));

        String content = Files.readString(domainXml);
        assertTrue(content.contains("<jvm-options>-Xmx2g</jvm-options>"), content);
        assertFalse(content.contains("<jvm-options>-Xmx512m</jvm-options>"), content);
    }

    @Test
    void appendsNewJvmOptionWhenAbsent(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setJvmOptions(domainXml, Map.of("-ea", "-ea"));

        String content = Files.readString(domainXml);
        assertTrue(content.contains("<jvm-options>-ea</jvm-options>"), content);
    }

    @Test
    void jvmOptionIsIdempotentWhenAlreadySet(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);
        long mtimeBefore = Files.getLastModifiedTime(domainXml).toMillis();

        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        DomainXmlEditor.setJvmOptions(domainXml, Map.of("-Xmx", "-Xmx512m"));

        long mtimeAfter = Files.getLastModifiedTime(domainXml).toMillis();
        assertEquals(mtimeBefore, mtimeAfter);
    }

    @Test
    void emptyOrNullJvmOptionsIsNoop(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setJvmOptions(domainXml, Map.of());
        DomainXmlEditor.setJvmOptions(domainXml, null);

        assertEquals(JAVA_CONFIG_FRAGMENT, Files.readString(domainXml));
    }

    @Test
    void mapsHeapAndAssertionsAsManagedDoes() {
        assertEquals(Map.of("-Xmx", "-Xmx2048m", "-ea", "-ea:org.jboss.cdi.tck..."),
                DomainXmlEditor.heapAndAssertionOptions("2048m", ":org.jboss.cdi.tck..."));
        assertTrue(DomainXmlEditor.heapAndAssertionOptions(null, null).isEmpty());
    }

    @Test
    void readsTheJvmOptions(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        assertEquals(List.of("-Xmx512m", "-Djavax.net.ssl.trustStore=cacerts.p12"),
                DomainXmlEditor.jvmOptions(domainXml));
    }

    @Test
    void atomicWriteReplacesInodeNotTruncatesIt(@TempDir Path tmp) throws IOException {
        // A second name pointing at the same inode. After setPorts, the old
        // inode (and so its other names) should NOT see the new content: the
        // file is replaced, not truncated and rewritten.
        Path source = tmp.resolve("source.xml");
        Path slot = tmp.resolve("slot.xml");
        Files.writeString(source, DOMAIN);
        try {
            Files.createLink(slot, source);
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem doesn't support hardlinks (e.g. /tmp on tmpfs sometimes);
            // skip the inode-isolation assertion entirely.
            return;
        }

        DomainXmlEditor.setPorts(slot, 14948, 14949, 14950);

        String slotContent = Files.readString(slot);
        String sourceContent = Files.readString(source);
        assertTrue(slotContent.contains("port=\"14948\""), slotContent);
        assertTrue(sourceContent.contains("port=\"4848\""), sourceContent);
        assertFalse(sourceContent.equals(slotContent));
    }

    /** As in GlassFish Embedded's own domain.xml, for a child of a pool slot; the template stays as it is. */
    @Test
    void setsTheIiopAndJmsPortsOfTheServersConfig(@TempDir Path tmp) throws IOException {
        String services = "<iiop-service>"
                + "<iiop-listener address=\"0.0.0.0\" id=\"orb-listener-1\" port=\"3700\"/>"
                + "<iiop-listener security-enabled=\"true\" id=\"SSL\" port=\"3820\"><ssl cert-nickname=\"s1as\"/>"
                + "</iiop-listener>"
                + "<iiop-listener security-enabled=\"true\" id=\"SSL_MUTUALAUTH\" port=\"3920\"/>"
                + "</iiop-service>"
                + "<jms-service default-jms-host=\"default_JMS_host\">"
                + "<jms-host name=\"default_JMS_host\" host=\"localhost\" port=\"7676\"/></jms-service>";
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><configs><config name=\"server-config\">" + services + "</config>"
                + "<config name=\"default-config\">" + services + "</config></configs></domain>");

        DomainXmlEditor.setIiopAndJmsPorts(domainXml, 14851, 14852, 14853, 14854);

        String content = Files.readString(domainXml);
        String server = content.substring(0, content.indexOf("default-config"));
        String template = content.substring(content.indexOf("default-config"));
        assertTrue(server.contains("id=\"orb-listener-1\" port=\"14851\""), content);
        assertTrue(server.contains("id=\"SSL\" port=\"14852\""), content);
        assertTrue(server.contains("id=\"SSL_MUTUALAUTH\" port=\"14853\""), content);
        assertTrue(server.contains("port=\"14854\""), content);
        assertTrue(template.contains("port=\"3700\"") && template.contains("port=\"7676\""), content);
    }

    /** A distribution's domain1 names them http-listener-1 and http-listener-2; the admin listener is neither. */
    @Test
    void findsTheWebListenersAsADistributionNamesThem(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><configs><config name=\"server-config\">"
                + "<http-service>"
                + "<virtual-server id=\"server\" network-listeners=\"http-listener-1,http-listener-2\"/>"
                + "<virtual-server id=\"__asadmin\" network-listeners=\"admin-listener\"/>"
                + "</http-service>"
                + "<network-config><protocols>"
                + "<protocol name=\"http-listener-1\"><http default-virtual-server=\"server\"/></protocol>"
                + "<protocol security-enabled=\"true\" name=\"http-listener-2\">"
                + "<http default-virtual-server=\"server\"/><ssl cert-nickname=\"s1as\"/></protocol>"
                + "<protocol name=\"admin-listener\"><http default-virtual-server=\"__asadmin\"/></protocol>"
                + "</protocols>" + FRAGMENT + "</network-config></config></configs></domain>");

        assertEquals(List.of(HTTP_LISTENER), DomainXmlEditor.webListeners(domainXml, false));
        assertEquals(List.of(HTTPS_LISTENER), DomainXmlEditor.webListeners(domainXml, true));
    }

    /** Embedded's own domain.xml names them http-listener and https-listener, and has no admin listener. */
    @Test
    void findsTheWebListenersAsEmbeddedNamesThem(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, "<domain><configs><config name=\"server-config\">"
                + "<http-service>"
                + "<virtual-server id=\"server\" network-listeners=\"http-listener, https-listener\"/>"
                + "</http-service>"
                + "<network-config><protocols>"
                + "<protocol name=\"http-listener\"><http default-virtual-server=\"server\"/></protocol>"
                + "<protocol security-enabled=\"true\" name=\"https-listener\">"
                + "<http default-virtual-server=\"server\"/><ssl cert-nickname=\"s1as\"/></protocol>"
                + "</protocols><network-listeners>"
                + "<network-listener port=\"0\" protocol=\"http-listener\" name=\"http-listener\" enabled=\"false\"/>"
                + "<network-listener port=\"0\" protocol=\"https-listener\" name=\"https-listener\" enabled=\"false\"/>"
                + "</network-listeners></network-config></config></configs></domain>");

        assertEquals(List.of("http-listener"), DomainXmlEditor.webListeners(domainXml, false));
        assertEquals(List.of("https-listener"), DomainXmlEditor.webListeners(domainXml, true));
    }

    /** A {@code domain.xml} with one config, {@code configName}, holding {@code listeners}. */
    private static String domain(String configName, String listeners) {
        return "<domain><configs><config name=\"" + configName + "\"><network-config>" + listeners
                + "</network-config></config></configs></domain>";
    }
}
