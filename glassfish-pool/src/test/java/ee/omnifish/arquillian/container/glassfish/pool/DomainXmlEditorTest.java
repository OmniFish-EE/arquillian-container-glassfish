/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
