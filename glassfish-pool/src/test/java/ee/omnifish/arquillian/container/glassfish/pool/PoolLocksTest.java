/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link PoolLocks}, checking from a second JVM ({@link ForeignJvm})
 * that the locks this JVM holds stay held.
 */
class PoolLocksTest {

    /**
     * Regression: probing a lock this JVM held opened a second channel and
     * closed it, which on Linux and macOS released the lock for other JVMs.
     */
    @Test
    void probeInTheHoldingJvmKeepsTheLock(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("lock");
        try (PoolLocks.Held held = PoolLocks.tryLock(file)) {
            assertThat(held, notNullValue());
            assertThat(PoolLocks.isBusy(file), equalTo(true));
            assertThat(PoolLocks.tryLock(file), nullValue());
            assertThat(ForeignJvm.canLock(file), equalTo(false));
        }
        assertThat(PoolLocks.isBusy(file), equalTo(false));
        assertThat(ForeignJvm.canLock(file), equalTo(true));
    }

    @Test
    void lockHeldByAnotherJvmIsBusy(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("lock");
        try (AutoCloseable foreign = ForeignJvm.hold(file)) {
            assertThat(PoolLocks.isBusy(file), equalTo(true));
            assertThat(PoolLocks.tryLock(file), nullValue());
        }
        try (PoolLocks.Held held = PoolLocks.tryLock(file)) {
            assertThat(held, notNullValue());
        }
    }

    @Test
    void missingLockFileIsNotBusyAndIsNotCreated(@TempDir Path tmp) {
        Path file = tmp.resolve("lock");
        assertThat(PoolLocks.isBusy(file), equalTo(false));
        assertThat(Files.exists(file), equalTo(false));
    }

    @Test
    void lockFileInAMissingDirectoryCannotBeLocked(@TempDir Path tmp) throws Exception {
        assertThat(PoolLocks.tryLock(tmp.resolve("removed-slot").resolve("lock")), nullValue());
    }

    /**
     * A lock this JVM took outside the registry makes {@code tryLock} throw
     * {@link java.nio.channels.OverlappingFileLockException}. The registry
     * must keep its channel, since closing it would release that lock, and
     * close it on a later call once the file is free.
     */
    @Test
    void overlapKeepsTheOtherChannelsLock(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("lock");
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock outside = channel.lock()) {
            assertThat(PoolLocks.isBusy(file), equalTo(true));
            assertThat(PoolLocks.tryLock(file), nullValue());
            assertThat(ForeignJvm.canLock(file), equalTo(false));
        }
        assertThat(PoolLocks.isBusy(file), equalTo(false));
        assertThat(ForeignJvm.canLock(file), equalTo(true));
    }
}
