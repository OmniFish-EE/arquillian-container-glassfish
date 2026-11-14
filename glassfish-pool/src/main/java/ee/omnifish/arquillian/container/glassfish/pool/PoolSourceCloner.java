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
package ee.omnifish.arquillian.container.glassfish.pool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.logging.Logger;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;

/**
 * Clones a source GlassFish install tree into a slot directory. Files are
 * {@link Files#createLink hardlinked}, so N slots cost ~one source's worth of
 * disk space — the {@code cp -al} idiom in pure Java — except under
 * {@code glassfish/domains/}, which is copied, so each slot gets a domain of
 * its own.
 *
 * <p>Domain files can't be shared, because GlassFish rewrites some of them in
 * place: {@code FileRealmHelper.persist()} truncates {@code keyfile}, so
 * {@code create-file-user} on one slot would also change the source and every
 * other slot. The copy is small, since a domain's runtime directories are
 * created empty instead of copied: the OSGi cache, generated code, logs and
 * the JMS broker's instance data. A started source fills them, and the OSGi
 * cache alone is most of a started domain's size.
 *
 * <p>{@link Files#createLink} is supported on Linux, macOS (HFS+/APFS) and
 * Windows (NTFS). When it isn't (cross-filesystem boundary, FAT, etc.) we
 * fall back to a regular copy so the clone still succeeds; the slot just
 * costs full disk.
 */
final class PoolSourceCloner {

    private static final Logger LOG = Logger.getLogger(PoolSourceCloner.class.getName());

    /** Where an install keeps its domains, relative to the install root. */
    private static final Path DOMAINS = Paths.get("glassfish", "domains");

    /** The directories a running server fills, relative to its domain. */
    private static final List<Path> RUNTIME_DIRS = List.of(
            Paths.get("osgi-cache"),
            Paths.get("generated"),
            Paths.get("logs"),
            Paths.get("imq", "instances"));

    private PoolSourceCloner() {
    }

    static void clone(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Source is not a directory: " + source);
        }

        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(target.resolve(rel.toString()));
                return isRuntimeDir(rel) ? SKIP_SUBTREE : CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Path dest = target.resolve(rel.toString());
                if (Files.exists(dest)) {
                    return CONTINUE;
                }

                if (rel.startsWith(DOMAINS)) {
                    Files.copy(file, dest, COPY_ATTRIBUTES);
                } else {
                    link(file, dest);
                }
                return CONTINUE;
            }
        });
    }

    private static void link(Path file, Path dest) throws IOException {
        try {
            Files.createLink(dest, file);
        } catch (UnsupportedOperationException | IOException e) {
            // Cross-filesystem, FAT, or hardlinks disabled — fall back to copy.
            // (We log once per slot at most; chatter would dominate the build log.)
            LOG.fine(() -> "Hardlink failed (" + e.getMessage() + "), copying " + file);
            Files.copy(file, dest, COPY_ATTRIBUTES);
        }
    }

    /** Whether {@code path}, relative to the install root, is one of a domain's runtime directories. */
    private static boolean isRuntimeDir(Path path) {
        int domainDepth = DOMAINS.getNameCount() + 1;

        return
            path.startsWith(DOMAINS) &&
            path.getNameCount() > domainDepth &&
            RUNTIME_DIRS.contains(path.subpath(domainDepth, path.getNameCount()));
    }
}
