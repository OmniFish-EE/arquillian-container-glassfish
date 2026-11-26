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
package ee.omnifish.glassfish.admin.embedded;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddedProcessesTest {

    private static final Path STAGE = Path.of(File.separator + "build", "target", "dist").toAbsolutePath();
    private static final List<Path> DIRS = List.of(STAGE);

    @TempDir
    Path tmp;

    private Process child;

    @AfterEach
    void endChild() {
        if (child != null) {
            child.destroyForcibly();
        }
    }

    /** Also where the system doesn't say what a process runs: this test's JVM runs nothing from tmp. */
    @Test
    void aRecordedChildRunsFromTheStageDirectory() throws Exception {
        EmbeddedProcesses.record(tmp, ProcessHandle.current(), null);

        assertThat(EmbeddedProcesses.runningFrom(tmp), equalTo(Optional.of(ProcessHandle.current())));
    }

    @Test
    void aRecordedChildThatEndedDoesntRun() throws Exception {
        EmbeddedProcesses.record(tmp, ended(), null);

        assertThat(EmbeddedProcesses.runningFrom(tmp), equalTo(Optional.empty()));
    }

    /** A process that got the pid of the child later isn't it. */
    @Test
    void aProcessThatStartedAtAnotherTimeIsntTheRecordedChild() throws Exception {
        Files.writeString(EmbeddedProcesses.recordFile(tmp, ProcessHandle.current().pid()),
                "pid=" + ProcessHandle.current().pid() + "\nstart=0\n");

        assertThat(EmbeddedProcesses.runningFrom(tmp), equalTo(Optional.empty()));
    }

    /** A pool's slots run children from one stage directory at once. */
    @Test
    void eachChildIsRecordedWithTheSlotItServes() throws Exception {
        ProcessHandle parent = ProcessHandle.current().parent().orElseThrow();
        EmbeddedProcesses.record(tmp, ProcessHandle.current(), tmp.resolve("pool/slot-1"));
        EmbeddedProcesses.record(tmp, parent, tmp.resolve("pool/slot-2"));

        assertThat(EmbeddedProcesses.serving(tmp, tmp.resolve("pool/slot-1")), contains(ProcessHandle.current()));
        assertThat(EmbeddedProcesses.serving(tmp, tmp.resolve("pool/./slot-2")), contains(parent));
        assertThat(EmbeddedProcesses.serving(tmp, tmp.resolve("pool/slot-3")), empty());
    }

    @Test
    void aStartedChildIsRecordedForItsSlot() throws Exception {
        child = EmbeddedProcesses.start(tmp, tmp.resolve("pool/slot-1"), false, this::sleeper);

        assertThat(EmbeddedProcesses.serving(tmp, tmp.resolve("pool/slot-1")), contains(child.toHandle()));
        assertThat(EmbeddedProcesses.runningFrom(tmp), equalTo(Optional.of(child.toHandle())));
        assertThat(EmbeddedProcesses.stageDirOf(tmp.resolve("pool/slot-1")),
                equalTo(Optional.of(tmp.toAbsolutePath().normalize())));
    }

    /**
     * As a second glassfish:run, also while the first child waits for a
     * debugger, with its HTTP port still free. The refused child doesn't start.
     */
    @Test
    void anExclusiveStartIsRefusedWhileAnotherChildRuns() throws Exception {
        EmbeddedProcesses.record(tmp, ProcessHandle.current(), null);
        AtomicBoolean started = new AtomicBoolean();

        IOException e = assertThrows(IOException.class, () -> EmbeddedProcesses.start(tmp, null, true, () -> {
            started.set(true);
            return sleeper();
        }));

        assertThat(e.getMessage(), containsString("GlassFish Embedded already runs from " + tmp));
        assertThat(started.get(), is(false));
    }

    @Test
    void aChildStartsBesideAnotherWhenNotExclusive() throws Exception {
        EmbeddedProcesses.record(tmp, ProcessHandle.current(), tmp.resolve("pool/slot-1"));

        child = EmbeddedProcesses.start(tmp, tmp.resolve("pool/slot-2"), false, this::sleeper);

        assertThat(EmbeddedProcesses.serving(tmp, tmp.resolve("pool/slot-2")), contains(child.toHandle()));
    }

    @Test
    void forgettingAChildLeavesAnothersRecord() throws Exception {
        ProcessHandle parent = ProcessHandle.current().parent().orElseThrow();
        EmbeddedProcesses.record(tmp, ProcessHandle.current(), null);
        EmbeddedProcesses.record(tmp, parent, null);

        EmbeddedProcesses.forget(tmp, parent);

        assertThat(Files.exists(EmbeddedProcesses.recordFile(tmp, ProcessHandle.current().pid())), is(true));
        assertThat(Files.exists(EmbeddedProcesses.recordFile(tmp, parent.pid())), is(false));
    }

    @Test
    void theRecordsOfChildrenThatEndedAreRemovedWhenAChildStarts() throws Exception {
        ProcessHandle ended = ended();
        EmbeddedProcesses.record(tmp, ended, tmp.resolve("pool/slot-1"));

        child = EmbeddedProcesses.start(tmp, tmp.resolve("pool/slot-1"), false, this::sleeper);

        assertThat(Files.exists(EmbeddedProcesses.recordFile(tmp, ended.pid())), is(false));
    }

    /** A child doesn't start while staging checks or replaces what it would run from. */
    @Test
    void aStartWaitsForTheStageDirectorysLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong startedAt = new AtomicLong();
        AtomicLong releasedAt = new AtomicLong();
        Thread staging = new Thread(() -> {
            try {
                EmbeddedProcesses.withStageLock(tmp, () -> {
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                    releasedAt.set(System.nanoTime());
                    return null;
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        staging.start();
        locked.await(30, TimeUnit.SECONDS);
        Thread starting = new Thread(() -> {
            try {
                child = EmbeddedProcesses.start(tmp, null, false, () -> {
                    startedAt.set(System.nanoTime());
                    return sleeper();
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        starting.start();

        Thread.sleep(500);
        assertThat(startedAt.get(), is(0L));
        release.countDown();
        staging.join(30_000);
        starting.join(30_000);

        assertThat(startedAt.get() > releasedAt.get(), is(true));
        assertThat(Files.exists(tmp.resolve(EmbeddedProcesses.LOCK_FILE)), is(true));
    }

    /**
     * A copy of this class in another class loader, as another Maven plugin
     * has, that waits for the lock and is interrupted, leaves the lock to its
     * holder: on Linux and macOS, closing a channel on the lock file releases
     * whatever lock this JVM holds on it, which another JVM could then take
     * while the holder still works.
     */
    @Test
    void anInterruptedWaiterLeavesTheLockToItsHolderInAnotherClassLoader() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> withStageLockOfACopy(() -> {
            locked.countDown();
            release.await(30, TimeUnit.SECONDS);
        }));
        holder.start();
        assertThat(locked.await(30, TimeUnit.SECONDS), is(true));
        Thread waiter = new Thread(() -> withStageLockOfACopy(() -> {
        }));
        waiter.start();
        try {
            awaitWaiting(waiter);
            waiter.interrupt();
            waiter.join(1_000);

            assertThat(anotherJvmCanLock(tmp.resolve(EmbeddedProcesses.LOCK_FILE)), is(false));
        } finally {
            release.countDown();
            holder.join(30_000);
            waiter.join(30_000);
        }
    }

    @Test
    void anEndedChildHasExited() throws Exception {
        child = sleeper();

        EmbeddedProcesses.end(child.toHandle(), Duration.ofSeconds(30));

        assertThat(child.isAlive(), is(false));
    }

    @Test
    void theJarsChildRunsFromTheStageDirectory() {
        assertThat(EmbeddedProcesses.runsFrom("java -agentlib:jdwp=… -jar " + STAGE.resolve(
                "glassfish-embedded-all-8.0.5.jar") + " --httpPort=8080 --noInfo=true deploy shop.war", DIRS), is(true));
    }

    @Test
    void theStaticShellsChildRunsFromTheStageDirectory() {
        assertThat(EmbeddedProcesses.runsFrom("java --add-opens=java.base/java.lang=ALL-UNNAMED -cp "
                + STAGE.resolve("glassfish8/glassfish/lib/embedded/glassfish-embedded-static-shell.jar")
                + " org.glassfish.runnablejar.UberMain --domainDir=domain", DIRS), is(true));
    }

    @Test
    void othersDontRunGlassFishEmbeddedFromIt() {
        Path sibling = Path.of(STAGE + "2");

        assertThat(EmbeddedProcesses.runsFrom("java -jar " + sibling.resolve("embedded.jar"), DIRS), is(false));
        assertThat(EmbeddedProcesses.runsFrom("java -Dcom.sun.aas.instanceRoot="
                + STAGE.resolve("glassfish8/glassfish/domains/domain1")
                + " com.sun.enterprise.glassfish.bootstrap.ASMain", DIRS), is(false));
    }

    /** Work under the stage lock, which may wait. */
    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    /**
     * Run {@code work} under the stage lock of {@code tmp}, through a copy of
     * {@link EmbeddedProcesses} in a class loader of its own, as another
     * Maven plugin's realm has. A failure, as an interrupted wait's, ends it.
     */
    private void withStageLockOfACopy(Work work) {
        URL classes = EmbeddedProcesses.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Class<?> copy = loader.loadClass(EmbeddedProcesses.class.getName());
            Class<?> section = loader.loadClass(EmbeddedProcesses.Section.class.getName());
            Object run = Proxy.newProxyInstance(loader, new Class<?>[] {section}, (proxy, method, args) -> {
                work.run();
                return null;
            });
            copy.getMethod("withStageLock", Path.class, section).invoke(null, tmp, run);
        } catch (ReflectiveOperationException | IOException e) {
            // As the interrupted waiter's.
        }
    }

    /** Wait until {@code thread} waits, as for a lock. */
    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (thread.getState() == Thread.State.NEW || thread.getState() == Thread.State.RUNNABLE) {
            assertThat("Still not waiting", System.nanoTime() < deadline, is(true));
            Thread.sleep(10);
        }
    }

    /**
     * Whether another JVM could take {@code file}'s lock now. It lets go of
     * the lock before it exits: Windows releases a lock an ended process
     * holds only in time.
     */
    private boolean anotherJvmCanLock(Path file) throws Exception {
        Path check = Files.writeString(tmp.resolve("LockCheck.java"), "import java.nio.channels.*;\n"
                + "import java.nio.file.*;\n"
                + "class LockCheck {\n"
                + "    public static void main(String[] args) throws Exception {\n"
                + "        try (FileChannel channel = FileChannel.open(Path.of(args[0]),\n"
                + "                StandardOpenOption.WRITE)) {\n"
                + "            FileLock lock = channel.tryLock();\n"
                + "            if (lock == null) {\n"
                + "                System.exit(1);\n"
                + "            }\n"
                + "            lock.release();\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        Process process = new ProcessBuilder(java(), check.toString(), file.toString()).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS), is(true));
        return process.exitValue() == 0;
    }

    /** A process that ran, and ended. */
    private static ProcessHandle ended() throws Exception {
        Process ended = new ProcessBuilder(java(), "-version").start();
        ended.waitFor();
        return ended.toHandle();
    }

    /** A process that sleeps until it's ended. */
    private Process sleeper() throws IOException {
        Path sleeper = Files.writeString(tmp.resolve("Sleeper.java"),
                "class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(120_000); } }");
        return new ProcessBuilder(java(), sleeper.toString()).start();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
