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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * The GlassFish Embedded children that run from a stage directory, as staging
 * must know them: it doesn't wipe what a child runs from.
 *
 * <p>Each child has a record in the stage directory, as {@code config/pid}
 * names {@code domain1}'s server: {@code glassfish-embedded-<pid>.pid}, with
 * its pid, its start, and the pool slot it serves, if any. Where the system
 * doesn't say what a process runs, as on Windows, the record is the only way
 * to find a child. Otherwise, a child is also found by its command line:
 * {@code -jar} with the JAR staged in a stage directory, or Embedded's main
 * class with the static shell of an install there. A child that serves a
 * pool slot names its stage directory in the slot's directory too, in
 * {@code embedded-stage.properties}, so the slot finds the child's record
 * also once the pool moved to another stage directory.
 *
 * <p>A JVM that hosts GlassFish Embedded itself, from a stage directory's
 * JAR or static shell, has a record there too, which says it's a host
 * ({@link #host}), and whether it's a test JVM or a
 * {@code glassfish-pool:run}'s Maven: staging doesn't wipe what it runs from
 * either, but the pool never ends it.
 *
 * <p>A child starts, and gets its record, under a lock on
 * {@code .staging-glassfish.lock} in the stage directory, which staging holds
 * too, from its check of the children through the end of a replacement. So
 * no child starts from an install that staging checks or replaces. Within a
 * JVM, threads take turns before they open a channel on the lock file, also
 * those of another class loader's copy of this class, as another Maven
 * plugin has: on Linux and macOS, closing a channel on the file releases
 * whatever lock the JVM holds on it, through any channel.
 */
public final class EmbeddedProcesses {

    /** GlassFish Embedded's own main class, which the static shell runs. */
    public static final String MAIN_CLASS = "org.glassfish.runnablejar.UberMain";

    /** The lock that starting a child and staging take, in the stage directory. */
    public static final String LOCK_FILE = ".staging-glassfish.lock";

    /** In a pool slot's directory: the stage directory its last child started from, which has that child's record. */
    public static final String SLOT_STAGE_FILE = "embedded-stage.properties";

    static final String RECORD_PREFIX = "glassfish-embedded-";
    static final String RECORD_SUFFIX = ".pid";

    private static final String PID = "pid";
    private static final String START = "start";
    private static final String SLOT = "slot";
    private static final String HOST = "host";
    private static final String STAGE_DIR = "stageDir";

    /** What a host's record says it is: a {@code glassfish-pool:run}'s Maven, or a test JVM. */
    private static final String RUN = "run";
    private static final String TESTS = "tests";

    /** Starts a child. */
    @FunctionalInterface
    public interface Starter {
        Process start() throws IOException;
    }

    /** Work done while holding the stage directory's lock. */
    @FunctionalInterface
    public interface Section<T> {
        T run() throws IOException;
    }

    private EmbeddedProcesses() {
    }

    /**
     * Start a child from {@code stageDir} with {@code starter}, and record
     * it, under the stage directory's lock. Records of children that ended
     * are removed first.
     *
     * @param slotDir the pool slot the child serves, or {@code null}; it
     *        gets the name of {@code stageDir} before the child starts
     * @param exclusive whether to refuse while another child runs from
     *        {@code stageDir}, as for {@code glassfish:run}, whose second run
     *        would replace the first one's, also while that one waits for a
     *        debugger, with its HTTP port still free
     * @throws IOException if the child doesn't start, or can't be recorded,
     *         which ends it, or, with {@code exclusive}, if another child runs
     */
    public static Process start(Path stageDir, Path slotDir, boolean exclusive, Starter starter) throws IOException {
        return withStageLock(stageDir, () -> {
            removeEnded(stageDir);
            if (exclusive && !recordedChildren(stageDir).isEmpty()) {
                throw new IOException("GlassFish Embedded already runs from " + stageDir + ", for another "
                        + "glassfish:run. End that one first.");
            }
            if (slotDir != null) {
                nameStageDir(slotDir, stageDir);
            }

            Process process = starter.start();
            try {
                record(stageDir, process.toHandle(), slotDir);
            } catch (IOException | RuntimeException e) {
                process.destroyForcibly();
                throw e;
            }
            return process;
        });
    }

    /** Remove the record of {@code child}, which ended. */
    public static void forget(Path stageDir, ProcessHandle child) throws IOException {
        Files.deleteIfExists(recordFile(stageDir, child.pid()));
    }

    /**
     * Record this JVM as a host of GlassFish Embedded that runs from
     * {@code stageDir}, under the stage directory's lock, as a child is
     * recorded when it starts. Closing the result removes the record.
     *
     * @param slotDir the pool slot it serves, or {@code null}; it gets the
     *        name of {@code stageDir}, as for a child
     * @param run whether this JVM is a {@code glassfish-pool:run}'s Maven,
     *        which {@code glassfish-pool:down} asks to stop, rather than a
     *        test JVM
     */
    public static Host host(Path stageDir, Path slotDir, boolean run) throws IOException {
        ProcessHandle self = ProcessHandle.current();
        withStageLock(stageDir, () -> {
            removeEnded(stageDir);
            if (slotDir != null) {
                nameStageDir(slotDir, stageDir);
            }
            record(stageDir, self, slotDir, run ? RUN : TESTS);
            return null;
        });

        return new Host(stageDir, self);
    }

    /** A GlassFish Embedded child that runs from {@code stageDir}, if there is one. */
    public static Optional<ProcessHandle> runningFrom(Path stageDir) {
        List<ProcessHandle> recorded = recordedChildren(stageDir);
        if (!recorded.isEmpty()) {
            return Optional.of(recorded.get(0));
        }

        List<Path> dirs = names(stageDir);
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine()
                        .map(commandLine -> runsFrom(commandLine, dirs))
                        .orElse(false))
                .findFirst();
    }

    /**
     * The stage directory that the last child that served the pool slot
     * {@code slotDir} started from, which has that child's record, if one did.
     */
    public static Optional<Path> stageDirOf(Path slotDir) {
        return read(slotDir.resolve(SLOT_STAGE_FILE))
                .map(stage -> stage.getProperty(STAGE_DIR, ""))
                .filter(dir -> !dir.isEmpty())
                .map(Paths::get);
    }

    /**
     * The recorded children of {@code stageDir} that serve the pool slot
     * {@code slotDir} and still run. A JVM that hosts GlassFish for the
     * slot isn't one of them: see {@link #hosts}.
     */
    public static List<ProcessHandle> serving(Path stageDir, Path slotDir) {
        return recorded(stageDir, slotDir, record -> record.getProperty(HOST) == null);
    }

    /** The JVMs that {@code stageDir} records as hosting GlassFish for the pool slot {@code slotDir}, and still run. */
    public static List<ProcessHandle> hosts(Path stageDir, Path slotDir) {
        return recorded(stageDir, slotDir, record -> record.getProperty(HOST) != null);
    }

    /** Of the {@link #hosts}, the {@code glassfish-pool:run}s' Mavens. */
    public static List<ProcessHandle> hostingRuns(Path stageDir, Path slotDir) {
        return recorded(stageDir, slotDir, record -> RUN.equals(record.getProperty(HOST)));
    }

    private static List<ProcessHandle> recorded(Path stageDir, Path slotDir, Predicate<Properties> kind) {
        Path slot = slotDir.toAbsolutePath().normalize();
        List<ProcessHandle> recorded = new ArrayList<>();
        for (Path file : recordFiles(stageDir)) {
            Optional<Properties> record = read(file);
            if (record.isPresent() && slot.toString().equals(record.get().getProperty(SLOT))
                    && kind.test(record.get())) {
                running(record.get()).ifPresent(recorded::add);
            }
        }

        return recorded;
    }

    /**
     * End {@code child}: a SIGTERM, then, if it hasn't ended within
     * {@code timeout}, a kill, and wait until it has.
     */
    public static void end(ProcessHandle child, Duration timeout) throws IOException {
        if (!child.isAlive()) {
            return;
        }

        child.destroy();
        try {
            try {
                child.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                child.destroyForcibly();
                child.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while GlassFish Embedded's child " + child.pid() + " ended");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("GlassFish Embedded's child " + child.pid() + " didn't end", e);
        }
    }

    /**
     * Run {@code section} holding {@code stageDir}'s lock, which starting a
     * child takes too. Within the JVM, it waits for its turn first, so no
     * other thread has a channel on the lock file open meanwhile, whichever
     * copy of this class it runs.
     */
    public static <T> T withStageLock(Path stageDir, Section<T> section) throws IOException {
        Path lockFile = stageDir.resolve(LOCK_FILE).toAbsolutePath().normalize();
        synchronized (turnsOf(lockFile)) {
            Files.createDirectories(stageDir);
            try (FileChannel channel = FileChannel.open(lockFile, CREATE, WRITE);
                 FileLock lock = channel.lock()) {
                return section.run();
            }
        }
    }

    /**
     * The monitor on which this JVM's threads take turns for {@code lockFile}:
     * an interned string, of which the JVM keeps one, which every class
     * loader's copy of this class shares.
     */
    private static Object turnsOf(Path lockFile) {
        return (EmbeddedProcesses.class.getName() + ":" + lockFile).intern();
    }

    /** Record {@code child} in {@code stageDir}, as serving {@code slotDir}, if not {@code null}. */
    static void record(Path stageDir, ProcessHandle child, Path slotDir) throws IOException {
        record(stageDir, child, slotDir, null);
    }

    /**
     * As {@link #record(Path, ProcessHandle, Path)}, for a JVM that hosts
     * GlassFish itself with {@code host}, which says what it is, or else a child.
     */
    static void record(Path stageDir, ProcessHandle process, Path slotDir, String host) throws IOException {
        Properties record = new Properties();
        record.setProperty(PID, String.valueOf(process.pid()));
        process.info().startInstant().ifPresent(start -> record.setProperty(START,
                String.valueOf(start.toEpochMilli())));
        if (slotDir != null) {
            record.setProperty(SLOT, slotDir.toAbsolutePath().normalize().toString());
        }
        if (host != null) {
            record.setProperty(HOST, host);
        }

        store(record, recordFile(stageDir, process.pid()), (host != null)
                ? "A JVM that hosts GlassFish Embedded from this directory"
                : "A GlassFish Embedded child that runs from this directory");
    }

    /** Name {@code stageDir} in the pool slot {@code slotDir}, as the stage directory its next child starts from. */
    private static void nameStageDir(Path slotDir, Path stageDir) throws IOException {
        Properties stage = new Properties();
        stage.setProperty(STAGE_DIR, stageDir.toAbsolutePath().normalize().toString());
        Files.createDirectories(slotDir);
        store(stage, slotDir.resolve(SLOT_STAGE_FILE), "The stage directory the slot's last GlassFish Embedded "
                + "child started from");
    }

    /** Write {@code properties} to {@code file} through a file of its own, so a reader gets all of them or none. */
    private static void store(Properties properties, Path file, String comment) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                properties.store(out, comment);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The recorded children of {@code stageDir} that still run. */
    static List<ProcessHandle> recordedChildren(Path stageDir) {
        List<ProcessHandle> children = new ArrayList<>();
        for (Path file : recordFiles(stageDir)) {
            read(file).flatMap(EmbeddedProcesses::running).ifPresent(children::add);
        }

        return children;
    }

    /** Remove the records of children that ended. The caller holds the stage directory's lock. */
    private static void removeEnded(Path stageDir) throws IOException {
        for (Path file : recordFiles(stageDir)) {
            if (read(file).flatMap(EmbeddedProcesses::running).isEmpty()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** The child a record names, if it runs: a process with its pid that started at another time isn't it. */
    private static Optional<ProcessHandle> running(Properties record) {
        try {
            Optional<ProcessHandle> child = ProcessHandle.of(Long.parseLong(record.getProperty(PID, "")))
                    .filter(ProcessHandle::isAlive);
            String start = record.getProperty(START);
            if (start != null) {
                long started = Long.parseLong(start);
                child = child.filter(process -> process.info().startInstant().map(Instant::toEpochMilli)
                        .map(millis -> millis == started).orElse(true));
            }
            return child;
        } catch (NumberFormatException e) {
            // Not written by this class.
            return Optional.empty();
        }
    }

    private static Optional<Properties> read(Path file) {
        Properties record = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            record.load(in);
            return Optional.of(record);
        } catch (IOException | IllegalArgumentException e) {
            // Gone meanwhile, or not written by this class.
            return Optional.empty();
        }
    }

    private static List<Path> recordFiles(Path stageDir) {
        if (!Files.isDirectory(stageDir)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(stageDir)) {
            return entries.filter(entry -> {
                String name = entry.getFileName().toString();
                return name.startsWith(RECORD_PREFIX) && name.endsWith(RECORD_SUFFIX);
            }).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    static Path recordFile(Path stageDir, long pid) {
        return stageDir.resolve(RECORD_PREFIX + pid + RECORD_SUFFIX);
    }

    /** {@code dir}, and its real path if that differs, as a process may have been given either. */
    private static List<Path> names(Path dir) {
        Path absolute = dir.toAbsolutePath().normalize();
        try {
            Path real = absolute.toRealPath();
            return real.equals(absolute) ? List.of(absolute) : List.of(absolute, real);
        } catch (IOException e) {
            return List.of(absolute);
        }
    }

    /** Whether {@code commandLine} runs GlassFish Embedded from one of {@code dirs}. Package-private for unit tests. */
    static boolean runsFrom(String commandLine, List<Path> dirs) {
        for (Path dir : dirs) {
            String within = dir + File.separator;
            if (commandLine.contains("-jar " + within)
                    || (commandLine.contains(MAIN_CLASS) && commandLine.contains(within))) {
                return true;
            }
        }

        return false;
    }

    /** The record of a JVM that hosts GlassFish Embedded, which closing removes. */
    public static final class Host implements AutoCloseable {

        private final Path stageDir;
        private final ProcessHandle process;

        private Host(Path stageDir, ProcessHandle process) {
            this.stageDir = stageDir;
            this.process = process;
        }

        @Override
        public void close() {
            try {
                forget(stageDir, process);
            } catch (IOException e) {
                // Once this JVM has ended, the next start or staging removes it.
            }
        }
    }
}
