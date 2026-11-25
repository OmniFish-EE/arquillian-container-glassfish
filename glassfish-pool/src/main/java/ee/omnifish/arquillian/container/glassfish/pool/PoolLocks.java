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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static ee.omnifish.arquillian.container.glassfish.pool.PoolPaths.growLock;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * The pool's file locks. On Linux and macOS, closing any channel on a file
 * releases every lock the JVM holds on that file, including locks taken
 * through another channel (see {@link FileChannel}). A second channel opened
 * only to probe a slot lock this JVM holds would drop that lock when it
 * closes, and another JVM could then lease the slot.
 *
 * <p>Slot locks therefore all go through this registry, which keeps one
 * channel per lock file and is consulted before a channel is opened. A channel
 * is closed only while it holds its lock, the only lock the JVM can then hold
 * on that file, or right after {@code tryLock} found the file locked by
 * another process. After an {@link OverlappingFileLockException}, which means
 * a lock taken in this JVM outside the registry, the channel is kept, and the
 * next call for that file retries on it.
 *
 * <p>{@code grow.lock} is only held for short sections. Threads of this JVM
 * take turns before taking it, so at most one channel on it is open here.
 *
 * <p>The registry is static, so it covers one class loader's copy of the
 * pool; in a test JVM, that is all of it.
 */
final class PoolLocks {

    /** Work done while holding {@code grow.lock}. */
    @FunctionalInterface
    interface Section<T> {
        T run() throws IOException;
    }

    private static final Object LOCK = new Object();

    /** Channels on slot lock files, by absolute path. Guarded by {@link #LOCK}. */
    private static final Map<Path, Channel> CHANNELS = new HashMap<>();

    /** One monitor per {@code grow.lock} file, for the turn-taking within this JVM. */
    private static final ConcurrentMap<Path, Object> GROW_TURNS = new ConcurrentHashMap<>();

    private PoolLocks() {
    }

    /**
     * Lock {@code lockFile}, creating it if needed. Returns {@code null} when
     * the lock is busy, whether this JVM or another one holds it, or when the
     * file's directory is gone (a slot removed between a scan and this call).
     */
    static Held tryLock(Path lockFile) throws IOException {
        Path key = key(lockFile);
        synchronized (LOCK) {
            try {
                return lock(key, true) ? new Held(key) : null;
            } catch (NoSuchFileException e) {
                return null;
            }
        }
    }

    /**
     * Whether {@code lockFile} is locked, by this JVM or another one.
     * Read-only: a missing file is not busy, and is not created.
     */
    static boolean isBusy(Path lockFile) {
        Path key = key(lockFile);
        synchronized (LOCK) {
            try {
                if (!lock(key, false)) {
                    return true;
                }
            } catch (IOException e) {
                // Missing or unreadable, so nobody holds it either.
                return false;
            }
            unlock(key);
            return false;
        }
    }

    /**
     * Lock {@code lockFile} unless another JVM holds it, creating it if
     * needed. Returns {@code null} when another JVM holds it. When this JVM
     * holds it already, or the file's directory is gone, the result holds
     * nothing, and closing it releases nothing.
     */
    static Held tryLockUnlessHeldElsewhere(Path lockFile) throws IOException {
        Path key = key(lockFile);
        synchronized (LOCK) {
            if (CHANNELS.containsKey(key)) {
                // Held here, or, after an overlap, by this JVM outside the registry.
                return Held.nothing();
            }
            try {
                if (lock(key, true)) {
                    return new Held(key);
                }
            } catch (NoSuchFileException e) {
                return Held.nothing();
            }
            // An overlap keeps its channel; another JVM's lock doesn't.
            return CHANNELS.containsKey(key) ? Held.nothing() : null;
        }
    }

    /**
     * Run {@code section} holding {@code poolDir}'s {@code grow.lock}, which
     * serializes slot claims across JVMs.
     */
    static <T> T withGrowLock(Path poolDir, Section<T> section) throws IOException {
        Path key = key(growLock(poolDir));
        synchronized (GROW_TURNS.computeIfAbsent(key, k -> new Object())) {
            try (FileChannel channel = FileChannel.open(key,
                    CREATE, READ, WRITE);
                 FileLock ignored = channel.lock()) {
                return section.run();
            }
        }
    }

    /**
     * Try to lock {@code key} for the registry, reusing a channel kept from an
     * earlier attempt. Returns {@code false} when the lock is busy. Caller
     * holds {@link #LOCK}.
     */
    private static boolean lock(Path key, boolean create) throws IOException {
        Channel kept = CHANNELS.get(key);
        if (kept != null && kept.lock != null) {
            return false;
        }

        FileChannel channel = (kept != null) ? kept.channel : open(key, create);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // Held in this JVM through a channel opened elsewhere. Closing ours
            // would release that lock as well, so keep it for the next attempt.
            CHANNELS.put(key, new Channel(channel, null));
            return false;
        } catch (IOException | RuntimeException e) {
            // No lock in this JVM is involved (that would have been an
            // OverlappingFileLockException), so the channel can go.
            CHANNELS.remove(key);
            closeQuietly(channel);
            throw e;
        }

        if (lock == null) {
            // Another JVM holds it. None of this JVM's channels does, or tryLock
            // would have thrown OverlappingFileLockException, so closing is safe.
            CHANNELS.remove(key);
            closeQuietly(channel);
            return false;
        }
        CHANNELS.put(key, new Channel(channel, lock));
        return true;
    }

    /** Release the registry's lock on {@code key}. Caller holds {@link #LOCK}. */
    private static void unlock(Path key) {
        Channel held = CHANNELS.remove(key);
        if (held != null) {
            // Closing releases the lock, the only one this JVM holds on the file.
            closeQuietly(held.channel);
        }
    }

    private static FileChannel open(Path key, boolean create) throws IOException {
        return create
                ? FileChannel.open(key, CREATE, READ, WRITE)
                : FileChannel.open(key, READ, WRITE);
    }

    private static Path key(Path file) {
        return file.toAbsolutePath().normalize();
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // The descriptor is released either way.
        }
    }

    /** A slot lock this JVM holds; {@link #close} releases it. */
    static final class Held implements AutoCloseable {

        private final Path key;
        private boolean released;

        private Held(Path key) {
            this.key = key;
        }

        /** A lock this JVM doesn't hold through it, so closing it releases nothing. */
        private static Held nothing() {
            Held nothing = new Held(null);
            nothing.released = true;
            return nothing;
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (!released) {
                    released = true;
                    unlock(key);
                }
            }
        }
    }

    /** A channel on a slot lock file, with its lock, or none while kept after an overlap. */
    private static final class Channel {

        final FileChannel channel;
        final FileLock lock;

        Channel(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
    }
}
