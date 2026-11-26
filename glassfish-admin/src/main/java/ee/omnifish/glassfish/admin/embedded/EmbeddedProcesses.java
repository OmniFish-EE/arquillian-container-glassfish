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
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * class with the static shell of an install there.
 *
 * <p>A child starts, and gets its record, under a lock on
 * {@code .staging-glassfish.lock} in the stage directory, which staging holds
 * too, from its check of the children through the end of a replacement. So
 * no child starts from an install that staging checks or replaces. Within a
 * JVM, threads take turns before taking that lock.
 */
public final class EmbeddedProcesses {

    /** GlassFish Embedded's own main class, which the static shell runs. */
    public static final String MAIN_CLASS = "org.glassfish.runnablejar.UberMain";

    /** The lock that starting a child and staging take, in the stage directory. */
    public static final String LOCK_FILE = ".staging-glassfish.lock";

    static final String RECORD_PREFIX = "glassfish-embedded-";
    static final String RECORD_SUFFIX = ".pid";

    private static final String PID = "pid";
    private static final String START = "start";
    private static final String SLOT = "slot";

    /** How long to wait before trying again for a lock another class loader's copy of this class holds. */
    private static final long OVERLAP_WAIT_MILLIS = 50;

    private static final ConcurrentMap<Path, Object> TURNS = new ConcurrentHashMap<>();

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
     * @param slotDir the pool slot the child serves, or {@code null}
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

    /** The recorded children of {@code stageDir} that serve the pool slot {@code slotDir} and still run. */
    public static List<ProcessHandle> serving(Path stageDir, Path slotDir) {
        Path slot = slotDir.toAbsolutePath().normalize();
        List<ProcessHandle> serving = new ArrayList<>();
        for (Path file : recordFiles(stageDir)) {
            Optional<Properties> record = read(file);
            if (record.isPresent() && slot.toString().equals(record.get().getProperty(SLOT))) {
                running(record.get()).ifPresent(serving::add);
            }
        }

        return serving;
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
     * child takes too. A lock another class loader's copy of this class holds
     * in this JVM is waited for, on the same channel: closing it would
     * release that lock too.
     */
    public static <T> T withStageLock(Path stageDir, Section<T> section) throws IOException {
        Path lockFile = stageDir.resolve(LOCK_FILE).toAbsolutePath().normalize();
        synchronized (TURNS.computeIfAbsent(lockFile, file -> new Object())) {
            Files.createDirectories(stageDir);
            try (FileChannel channel = FileChannel.open(lockFile, CREATE, WRITE);
                 FileLock lock = lock(channel)) {
                return section.run();
            }
        }
    }

    private static FileLock lock(FileChannel channel) throws IOException {
        while (true) {
            try {
                return channel.lock();
            } catch (OverlappingFileLockException e) {
                try {
                    Thread.sleep(OVERLAP_WAIT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while waiting for the stage directory's lock");
                }
            }
        }
    }

    /** Record {@code child} in {@code stageDir}, as serving {@code slotDir}, if not {@code null}. */
    static void record(Path stageDir, ProcessHandle child, Path slotDir) throws IOException {
        Properties record = new Properties();
        record.setProperty(PID, String.valueOf(child.pid()));
        child.info().startInstant().ifPresent(start -> record.setProperty(START, String.valueOf(start.toEpochMilli())));
        if (slotDir != null) {
            record.setProperty(SLOT, slotDir.toAbsolutePath().normalize().toString());
        }

        Path file = recordFile(stageDir, child.pid());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            record.store(out, "A GlassFish Embedded child that runs from this directory");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
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
}
