/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertThat(content, containsString("port=\"14948\""));
        assertThat(content, containsString("port=\"14949\""));
        assertThat(content, containsString("port=\"14950\""));
        assertThat(content, not(containsString("port=\"4848\"")));
        assertThat(content, not(containsString("port=\"8080\"")));
        assertThat(content, not(containsString("port=\"8181\"")));
    }

    @Test
    void leavesPropertyPlaceholdersAlone(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        String withPlaceholder = FRAGMENT.replace("port=\"4848\"", "port=\"${ADMIN_PORT}\"");
        Files.writeString(domainXml, withPlaceholder);

        DomainXmlEditor.setPorts(domainXml, 14948, 14949, 14950);

        String content = Files.readString(domainXml);
        assertThat(content, containsString("port=\"${ADMIN_PORT}\""));
        assertThat(content, containsString("port=\"14949\""));
    }

    private static final String JAVA_CONFIG_FRAGMENT =
            "<java-config>"
            + "<jvm-options>-Xmx512m</jvm-options>"
            + "<jvm-options>-Djavax.net.ssl.trustStore=cacerts.p12</jvm-options>"
            + "</java-config>";

    @Test
    void appendsNewJvmOption(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setJvmOptions(domainXml,
                List.of("javax.net.ssl.trustStorePassword=changeit"));

        String content = Files.readString(domainXml);
        assertThat(content, containsString(
                "<jvm-options>-Djavax.net.ssl.trustStorePassword=changeit</jvm-options>"));
        assertThat(content, containsString("<jvm-options>-Xmx512m</jvm-options>"));
    }

    @Test
    void replacesExistingJvmOptionWithSameKey(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setJvmOptions(domainXml,
                List.of("javax.net.ssl.trustStore=elsewhere.jks"));

        String content = Files.readString(domainXml);
        assertThat(content, containsString(
                "<jvm-options>-Djavax.net.ssl.trustStore=elsewhere.jks</jvm-options>"));
        assertThat(content, not(containsString(
                "<jvm-options>-Djavax.net.ssl.trustStore=cacerts.p12</jvm-options>")));
    }

    @Test
    void idempotentWhenAlreadySet(@TempDir Path tmp) throws IOException {
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

        DomainXmlEditor.setJvmOptions(domainXml,
                List.of("javax.net.ssl.trustStore=cacerts.p12"));

        long mtimeAfter = Files.getLastModifiedTime(domainXml).toMillis();
        assertThat(mtimeAfter, equalTo(mtimeBefore));
    }

    @Test
    void bareKeyIsIdempotent(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml,
                "<java-config><jvm-options>-Dsomeflag</jvm-options></java-config>");

        DomainXmlEditor.setJvmOptions(domainXml, List.of("someflag"));

        // Should still be a single occurrence — no duplicate appended.
        String content = Files.readString(domainXml);
        int count = content.split("-Dsomeflag", -1).length - 1;
        assertThat(count, equalTo(1));
    }

    @Test
    void emptyOrNullPropertiesIsNoop(@TempDir Path tmp) throws IOException {
        Path domainXml = tmp.resolve("domain.xml");
        Files.writeString(domainXml, JAVA_CONFIG_FRAGMENT);

        DomainXmlEditor.setJvmOptions(domainXml, List.of());
        DomainXmlEditor.setJvmOptions(domainXml, null);

        assertThat(Files.readString(domainXml), equalTo(JAVA_CONFIG_FRAGMENT));
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
        assertThat(slotContent, containsString("port=\"14948\""));
        assertThat(sourceContent, containsString("port=\"4848\""));
        assertThat(sourceContent.equals(slotContent), is(false));
    }
}
