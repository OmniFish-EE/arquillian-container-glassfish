/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link PoolSourceCloner} on a small fake install: domain files are
 * copied, everything else is hardlinked, and a domain's runtime directories
 * come out empty.
 */
class PoolSourceClonerTest {

    private static final String MODULE = "glassfish/modules/weld-osgi-bundle.jar";
    private static final String DOMAIN = "glassfish/domains/domain1";
    private static final String KEYFILE = DOMAIN + "/config/keyfile";
    private static final String KEYFILE_CONTENT = "admin;{SSHA256}source;asadmin\n";

    @Test
    void domainFilesAreCopiesAndOtherFilesAreLinks(@TempDir Path tmp) throws IOException {
        assumeHardlinks(tmp);
        Path source = fakeInstall(tmp.resolve("source"));
        Path slot = tmp.resolve("slot");

        PoolSourceCloner.clone(source, slot);

        assertThat(Files.isSameFile(source.resolve(MODULE), slot.resolve(MODULE)), equalTo(true));
        assertThat(Files.isSameFile(source.resolve(KEYFILE), slot.resolve(KEYFILE)), equalTo(false));
        assertThat(Files.readString(slot.resolve(KEYFILE)), equalTo(KEYFILE_CONTENT));
    }

    @Test
    void runtimeDirectoriesOfADomainAreCreatedEmpty(@TempDir Path tmp) throws IOException {
        Path source = fakeInstall(tmp.resolve("source"));
        for (String file : List.of("osgi-cache/felix/bundle1/bundle.info", "generated/jsp/index_jsp.java",
                "logs/server.log", "imq/instances/imqbroker/props/config.properties", "imq/marker")) {
            write(source.resolve(DOMAIN).resolve(file), "runtime state");
        }
        Path slot = tmp.resolve("slot");

        PoolSourceCloner.clone(source, slot);

        Path slotDomain = slot.resolve(DOMAIN);
        for (String dir : List.of("osgi-cache", "generated", "logs", "imq/instances")) {
            assertThat(dir, list(slotDomain.resolve(dir)), empty());
        }
        assertThat(Files.readString(slotDomain.resolve("imq/marker")), equalTo("runtime state"));
    }

    /**
     * GlassFish rewrites some domain files in place: {@code FileRealmHelper.persist()}
     * truncates the keyfile through a {@link FileOutputStream}. Through a
     * hardlink, that would change the source and every other slot too.
     */
    @Test
    void rewritingOneSlotsKeyfileLeavesTheSourceAndOtherSlotsAlone(@TempDir Path tmp) throws IOException {
        Path source = fakeInstall(tmp.resolve("source"));
        Path slot1 = tmp.resolve("slot-1");
        Path slot2 = tmp.resolve("slot-2");
        PoolSourceCloner.clone(source, slot1);
        PoolSourceCloner.clone(source, slot2);

        try (OutputStream out = new FileOutputStream(slot1.resolve(KEYFILE).toFile())) {
            out.write("student;{SSHA256}slot1;student\n".getBytes(UTF_8));
        }

        assertThat(Files.readString(source.resolve(KEYFILE)), equalTo(KEYFILE_CONTENT));
        assertThat(Files.readString(slot2.resolve(KEYFILE)), equalTo(KEYFILE_CONTENT));
    }

    /** A source install with one module and one domain. */
    private static Path fakeInstall(Path dir) throws IOException {
        write(dir.resolve(MODULE), "module");
        write(dir.resolve(KEYFILE), KEYFILE_CONTENT);
        return dir;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<String> list(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.map(entry -> entry.getFileName().toString()).collect(toList());
        }
    }

    private static void assumeHardlinks(Path dir) throws IOException {
        Path probe = Files.writeString(dir.resolve("probe"), "");
        boolean supported;
        try {
            Files.createLink(dir.resolve("probe-link"), probe);
            supported = true;
        } catch (UnsupportedOperationException | IOException e) {
            supported = false;
        }
        assumeTrue(supported, "the filesystem has no hardlinks");
    }
}
