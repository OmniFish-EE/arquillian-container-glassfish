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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Optional pre-step for {@link PoolBootstrap#up}: resolve and unpack the
 * GlassFish distribution zip, then copy overlay artifacts into the staged
 * install.
 *
 * <p>Pure Java: artifact lookup is delegated to a caller-supplied
 * {@link ArtifactResolver} so the {@code glassfish-pool} module does not
 * depend on Aether. The Maven plugin module wires Aether in.
 *
 * <p>Concurrency: callers are expected to invoke {@link #stage} from inside
 * {@link PoolBootstrap}'s {@code UP_LOCK} synchronized block. The marker
 * check makes a second concurrent run a no-op even without that, but the
 * unpack itself is not safe to interleave.
 */
public final class PoolStaging {

    private static final Logger LOG = Logger.getLogger(PoolStaging.class.getName());

    private static final String MARKER_FILE = ".staging-glassfish.properties";
    private static final String MARKER_KEY_DIST = "distribution";

    /** Resolves a GAV to a local file (typically the artifact under {@code ~/.m2/repository}). */
    @FunctionalInterface
    public interface ArtifactResolver {
        Path resolve(ArtifactCoord coord) throws IOException;
    }

    private PoolStaging() {
    }

    /**
     * Run the staging steps. Either side is a no-op when its skip flag is set
     * or its inputs are empty. The unpack step is marker-guarded: it skips
     * when the staged install already records the requested dist GAV, and
     * wipes + re-unpacks when the GAV differs. Overlays are always re-copied
     * (cheap; lets {@code -Dmojarra.version=…} take effect without a wipe).
     */
    public static void stage(StageSpec spec, ArtifactResolver resolver) throws IOException {
        if (!spec.unpackSkip() && spec.distribution() != null) {
            unpackDistribution(spec, resolver);
        }
        if (!spec.overlaySkip() && !spec.overlays().isEmpty()) {
            copyOverlays(spec, resolver);
        }
    }

    private static void unpackDistribution(StageSpec spec, ArtifactResolver resolver) throws IOException {
        Path stageDir = spec.stageDir();
        Path marker = stageDir.resolve(MARKER_FILE);
        String wantedGav = spec.distribution().toGav();
        if (Files.exists(marker)) {
            String stagedGav = readMarker(marker, MARKER_KEY_DIST);
            if (wantedGav.equals(stagedGav)) {
                LOG.fine("Dist already staged at " + stageDir + " (" + wantedGav + ")");
                return;
            }
            LOG.info("Re-staging dist (was " + stagedGav + ", now " + wantedGav + ")");
            wipe(stageDir);
        }
        Path zip = resolver.resolve(spec.distribution());
        Files.createDirectories(stageDir);
        LOG.info("Unpacking " + wantedGav + " to " + stageDir);
        unzip(zip, stageDir);
        writeMarker(marker, MARKER_KEY_DIST, wantedGav);
    }

    private static void copyOverlays(StageSpec spec, ArtifactResolver resolver) throws IOException {
        Path target = spec.overlayTargetDir();
        Files.createDirectories(target);
        for (OverlayCoord overlay : spec.overlays()) {
            Path source = resolver.resolve(overlay);
            String fileName = (overlay.getDestFileName() == null || overlay.getDestFileName().isEmpty())
                    ? source.getFileName().toString()
                    : overlay.getDestFileName();
            Path dest = target.resolve(fileName);
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Overlaid " + overlay.toGav() + " → " + dest);
        }
    }

    /**
     * Unzip preserving POSIX permissions. Uses commons-compress because
     * {@link java.util.zip.ZipFile} doesn't expose external file attributes,
     * which {@code asadmin}'s {@code +x} bit lives in.
     */
    private static void unzip(Path zip, Path targetDir) throws IOException {
        try (ZipFile zf = ZipFile.builder().setFile(zip.toFile()).get()) {
            var entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    throw new IOException("Zip entry escapes target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Files.createDirectories(resolved.getParent());
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                applyUnixMode(resolved, entry.getUnixMode());
            }
        }
    }

    private static void applyUnixMode(Path file, int unixMode) {
        if (unixMode == 0) {
            return;
        }
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        if ((unixMode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((unixMode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((unixMode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((unixMode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((unixMode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((unixMode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((unixMode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((unixMode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((unixMode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        try {
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows). Skip silently — Windows doesn't need +x.
        }
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var entries = Files.walk(dir)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    /* best-effort */
                }
            });
        }
    }

    private static String readMarker(Path marker, String key) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        return props.getProperty(key);
    }

    private static void writeMarker(Path marker, String key, String value) throws IOException {
        Properties props = new Properties();
        if (Files.exists(marker)) {
            try (InputStream in = Files.newInputStream(marker)) {
                props.load(in);
            }
        }
        props.setProperty(key, value);
        Files.createDirectories(marker.getParent());
        try (var out = Files.newOutputStream(marker)) {
            props.store(out, "glassfish-pool staging marker");
        }
    }
}
