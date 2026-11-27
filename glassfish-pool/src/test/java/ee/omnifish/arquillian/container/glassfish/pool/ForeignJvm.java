/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * Takes or checks a file lock from a second JVM, which is how another test JVM
 * looks to the pool. A lock taken in the test's own JVM can't stand in for
 * one: there, an overlapping lock throws instead of reporting busy, and on
 * Linux and macOS closing a channel releases the JVM's other locks on the file.
 *
 * <p>The second JVM runs {@link #main} from the test classes directory, using
 * only the JDK.
 */
final class ForeignJvm {

    private static final String LOCKED = "locked";
    private static final long TIMEOUT_SECONDS = 30;

    private ForeignJvm() {
    }

    /** Whether another JVM could lock {@code file} right now. */
    static boolean canLock(Path file) throws Exception {
        Process process = start("check", file);
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Lock check on " + file + " timed out");
        }
        return process.exitValue() == 0;
    }

    /** Hold {@code file}'s lock in another JVM until the returned holder is closed or killed. */
    static Holder hold(Path file) throws Exception {
        Process process = start("hold", file);
        BufferedReader out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        do {
            line = out.readLine();
        } while (line != null && !LOCKED.equals(line));
        if (line == null) {
            process.destroyForcibly();
            throw new IllegalStateException("Another JVM could not lock " + file);
        }
        return new Holder(process, file);
    }

    /** A lock held by another JVM. */
    static final class Holder implements AutoCloseable {

        private final Process process;
        private final Path file;

        private Holder(Process process, Path file) {
            this.process = process;
            this.file = file;
        }

        /** Release the lock: the holder exits once its stdin closes. */
        @Override
        public void close() throws Exception {
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }

        /**
         * Kill the holder, as a crash or failsafe's fork timeout would, and
         * wait until the OS released its lock, as the pool then finds it: on
         * Windows, that can take a moment after the holder ended.
         */
        void kill() throws InterruptedException {
            process.destroyForcibly();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The JVM that holds " + file + " didn't end when killed");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (PoolLocks.isBusy(file)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("The OS kept the lock on " + file + " of a killed JVM");
                }
                Thread.sleep(20);
            }
        }
    }

    private static Process start(String mode, Path file) throws Exception {
        String java = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        Path javaExe = Paths.get(System.getProperty("java.home"), "bin", java);
        Path classes = Paths.get(ForeignJvm.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new ProcessBuilder(javaExe.toString(), "-cp", classes.toString(),
                ForeignJvm.class.getName(), mode, file.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    /**
     * {@code check <file>}: exit 0 if the lock was free, 1 if not.
     * {@code hold <file>}: lock, print {@value #LOCKED}, and hold the lock
     * until stdin closes.
     */
    public static void main(String[] args) throws IOException {
        Path file = Paths.get(args[1]);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if ("check".equals(args[0]) || lock == null) {
                System.exit(lock == null ? 1 : 0);
            }
            System.out.println(LOCKED);
            System.out.flush();
            while (System.in.read() != -1) {
                // Hold the lock until the parent closes our stdin.
            }
        }
    }
}
