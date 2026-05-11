/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void rewritesAllThreePorts(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, FRAGMENT);

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
    void leavesPropertyPlaceholdersAlone(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        String withPlaceholder = FRAGMENT.replace("port=\"4848\"", "port=\"${ADMIN_PORT}\"");
        Files.writeString(domainXml, withPlaceholder);

        DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950);

        String content = Files.readString(domainXml);
        assertTrue(content.contains("port=\"${ADMIN_PORT}\""), content);
        assertTrue(content.contains("port=\"14949\""), content);
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
    void atomicWriteReplacesInodeNotTruncatesIt(@TempDir Path tmp) throws IOException {
        // Simulate hardlinked source: a second name pointing at the same inode.
        // After setPorts, the source inode (and its other names) should NOT see
        // the new content — that's the contract that protects the source install.
        Path source = tmp.resolve("source.xml");
        Path slot = tmp.resolve("slot.xml");
        Files.writeString(source, FRAGMENT);
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
}
