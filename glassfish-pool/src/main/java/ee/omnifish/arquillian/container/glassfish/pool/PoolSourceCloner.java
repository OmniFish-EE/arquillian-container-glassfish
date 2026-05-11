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
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;

import ee.omnifish.arquillian.container.glassfish.DomainXmlEditor;

/**
 * Clones a source GlassFish install tree into a slot directory using
 * {@link Files#createLink hardlinks} so N slots cost ~one source's worth
 * of disk space — the {@code cp -al} idiom in pure Java.
 *
 * <p>{@link Files#createLink} is supported on Linux, macOS (HFS+/APFS) and
 * Windows (NTFS). When it isn't (cross-filesystem boundary, FAT, etc.) we
 * fall back to a regular copy so the clone still succeeds; the slot just
 * costs full disk.
 *
 * <p>Hardlinks are safe for the GlassFish tree only because the provisioner
 * uses inode-replacing writes via {@link DomainXmlEditor#setPorts} when it
 * patches the slot's {@code domain.xml}. A naive in-place truncate would
 * also rewrite the source's {@code domain.xml} through its hardlinked twin.
 */
final class PoolSourceCloner {

    private static final Logger LOG = Logger.getLogger(PoolSourceCloner.class.getName());

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
                Path dest = target.resolve(rel.toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Path dest = target.resolve(rel.toString());
                if (Files.exists(dest)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    Files.createLink(dest, file);
                } catch (UnsupportedOperationException | IOException e) {
                    // Cross-filesystem, FAT, or hardlinks disabled — fall back to copy.
                    // (We log once per slot at most; chatter would dominate the build log.)
                    LOG.fine(() -> "Hardlink failed (" + e.getMessage() + "), copying " + rel);
                    Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
