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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ee.omnifish.arquillian.container.glassfish.pool.PoolPaths.portsFile;
import static ee.omnifish.arquillian.container.glassfish.pool.PoolProvisioner.isPortHealthy;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;

/**
 * Public Java API used by the Maven plugin's mojos and by direct
 * {@code <java>} antrun invocations. Three operations:
 * <ul>
 *   <li>{@link #up} — provision N slots in parallel, start their domains,
 *       arm a JVM shutdown hook so {@code Ctrl+C} doesn't orphan them.</li>
 *   <li>{@link #down} — stop every slot's domain, failing if one keeps running.</li>
 *   <li>{@link #provisionSlot} — bootstrap one extra slot at a given index
 *       (the {@code provision} goal; {@link SlotLeaser} grows the pool
 *       through {@link PoolProvisioner} directly).</li>
 * </ul>
 *
 * <p>For one-off invocations from antrun's {@code <java>} task, {@link #main}
 * dispatches the same operations from system properties (so the consumer
 * pom passes everything via {@code <sysproperty>}).
 */
public final class PoolBootstrap {

    private static final Logger LOG = Logger.getLogger(PoolBootstrap.class.getName());

    // ANSI colors used for the [LIVE] tag — mirrors ProgressListener's [RUNNING]/[FAILED] styling.
    private static final String WHITE = "[37m";
    private static final String BOLD_GREEN = "[1;32m";
    private static final String RESET = "[0m";

    /**
     * Cap on parallel slot workers. Cold-start of a GlassFish domain is
     * dominated by JVM warmup and disk IO; beyond ~16 concurrent starts the
     * host's IO/CPU saturates and total wall-clock stops improving (and
     * regresses on smaller machines). Only relevant if {@link PoolConfig#size}
     * exceeds this — otherwise the executor sizes itself to the slot count.
     */
    private static final int MAX_PROVISION_PARALLELISM = 16;

    private static final AtomicBoolean shutdownHookInstalled = new AtomicBoolean(false);

    /** The pools {@link #up} started in this JVM and {@link #down} didn't stop yet, by directory. */
    private static final Map<Path, PoolConfig> STARTED_POOLS = new ConcurrentHashMap<>();

    /**
     * Serialises concurrent {@link #up} calls within a single JVM so two
     * threads cannot race on cloning and {@code start-domain} for the same
     * slot. Cross-JVM callers must coordinate externally — the lock is
     * JVM-wide only.
     */
    private static final Object UP_LOCK = new Object();

    private PoolBootstrap() {
    }

    /**
     * Provision and start {@code config.size()} slots. Existing healthy slots
     * are left alone (the underlying {@link PoolProvisioner#provisionSlot} is
     * idempotent), so this is safe to re-run after a partial failure.
     *
     * <p>Provisioning is parallel: each slot's clone + start-domain runs on
     * its own thread, so cold-start time scales with the slowest slot rather
     * than the sum.
     */
    public static void up(PoolConfig config) throws IOException {
        up(config, null);
    }

    /**
     * Variant that runs a caller-supplied staging step inside the same
     * {@code UP_LOCK} window, before the "already provisioned?" fast-path.
     * The Maven plugin uses this to resolve + unpack the GlassFish dist and
     * overlay artifacts before slot provisioning runs.
     */
    public static void up(PoolConfig config, Runnable preStage) throws IOException {
        synchronized (UP_LOCK) {
            Files.createDirectories(config.poolDir());
            installShutdownHook(config);
            if (preStage != null) {
                preStage.run();
            }
            if (config.size() <= 0) {
                LOG.info("Pool size is 0 — nothing to provision");
                return;
            }
            if (isAlreadyProvisioned(config)) {
                LOG.fine("Pool already healthy — nothing to do");
                return;
            }
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(config.size(), MAX_PROVISION_PARALLELISM));
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                PoolProvisioner provisioner = new PoolProvisioner(config);
                for (int i = 1; i <= config.size(); i++) {
                    final int slot = i;
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            provisioner.provisionSlot(slot);
                        } catch (IOException | RuntimeException e) {
                            throw new CompletionException(
                                    new IOException("Slot " + slot + " provisioning failed: " + e.getMessage(), e));
                        }
                    }, pool));
                }
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (CompletionException e) {
                    if (e.getCause() instanceof IOException) {
                        throw (IOException) e.getCause();
                    }
                    throw e;
                }
            } finally {
                pool.shutdown();
            }
            LOG.info("Pool ready at " + config.poolDir() + " with " + config.size() + " slot(s)");
        }
    }

    /**
     * Pool is "already provisioned" when every requested slot is owned by
     * another JVM, or has a {@code ports.properties} pointing at a live admin
     * port. Used by {@link #up} to fast-exit on a second concurrent (or
     * repeated) call so the heavy clone/start-domain work isn't re-run.
     */
    private static boolean isAlreadyProvisioned(PoolConfig config) {
        for (int idx = 1; idx <= config.size(); idx++) {
            // A busy slot lock means another JVM owns the slot — running tests
            // against a live GF, or setting it up or restarting it, with the
            // port closed and ports.properties not written yet. Either way the
            // slot is not ours to reprovision.
            if (isLockBusy(config.poolDir(), idx)) {
                continue;
            }

            Path portsFile = portsFile(config.poolDir(), idx);
            if (!exists(portsFile)) {
                return false;
            }

            try {
                SlotPorts ports = SlotPorts.readFrom(portsFile);
                if (!isPortHealthy(ports.adminPort())) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Stop every {@code slot-N} under {@code config.poolDir()}, with
     * {@code stop-domain}, and with {@code stop-domain --kill} if that fails.
     * Slots without an install dir are skipped.
     *
     * @throws IllegalStateException naming the slots whose admin port still
     *         answers afterwards, because the next {@link #up} would reuse
     *         them as they are
     */
    public static void down(PoolConfig config) {
        // Stopped now, or reported as still running, so the shutdown hook leaves it alone.
        STARTED_POOLS.remove(poolKey(config));
        Path poolDir = config.poolDir();
        if (!isDirectory(poolDir)) {
            return;
        }

        List<Integer> slots = listSlotIndices(poolDir);
        if (slots.isEmpty()) {
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(slots.size(), MAX_PROVISION_PARALLELISM));
        try {
            PoolProvisioner provisioner = new PoolProvisioner(config);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int slot : slots) {
                futures.add(CompletableFuture.runAsync(() -> provisioner.stopSlot(slot), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }

        List<String> running = new ArrayList<>();
        for (int slot : slots) {
            int adminPort = adminPort(config, slot);
            if (isDirectory(slotInstall(poolDir, slot)) && isPortHealthy(adminPort)) {
                running.add(slot + " (admin port " + adminPort + ")");
            }
        }
        if (!running.isEmpty()) {
            throw new IllegalStateException("Pool slots still running after stopping them: "
                    + String.join(", ", running) + ". The next up would reuse them as they are.");
        }
    }

    /** The admin port slot {@code slot} published, or else the one {@code config} gives it. */
    private static int adminPort(PoolConfig config, int slot) {
        Path portsFile = portsFile(config.poolDir(), slot);
        if (exists(portsFile)) {
            try {
                return SlotPorts.readFrom(portsFile).adminPort();
            } catch (IOException | RuntimeException e) {
                // Half-written; fall back to the config's port.
            }
        }

        return config.adminPort(slot);
    }

    private static Path slotInstall(Path poolDir, int slot) {
        return PoolPaths.slotDir(poolDir, slot).resolve(PoolProvisioner.SLOT_INSTALL_DIRNAME);
    }

    /**
     * Provision a single slot. Exposed via the {@code provision} mojo for
     * ad-hoc additions. Returns {@code null} when another JVM owns the slot
     * and hasn't published its ports yet.
     */
    public static SlotPorts provisionSlot(PoolConfig config, int idx) throws IOException {
        return provisionSlot(config, idx, null);
    }

    /**
     * Variant that runs an optional staging step inside {@code UP_LOCK} before
     * provisioning, mirroring {@link #up(PoolConfig, Runnable)}.
     */
    public static SlotPorts provisionSlot(PoolConfig config, int idx, Runnable preStage) throws IOException {
        synchronized (UP_LOCK) {
            Files.createDirectories(config.poolDir());
            if (preStage != null) {
                preStage.run();
            }
            return new PoolProvisioner(config).provisionSlot(idx);
        }
    }

    /** Print a one-line-per-slot status to stdout: idx, adminPort, alive/dead, leased/idle. */
    public static void status(PoolConfig config) {
        status(config, System.out);
    }

    /**
     * Variant that writes to a caller-supplied stream. {@link #statusWatch} uses this
     * to capture each frame into a buffer so it can count rows for the redraw escape.
     */
    public static void status(PoolConfig config, PrintStream out) {
        Path poolDir = config.poolDir();
        if (!isDirectory(poolDir)) {
            out.println("Pool dir does not exist: " + poolDir);
            return;
        }
        List<Integer> slots = listSlotIndices(poolDir);
        if (slots.isEmpty()) {
            out.println("Pool dir " + poolDir + " is empty");
            return;
        }
        // Markdown-pipe table; widths match the longest expected value per column
        // (alive padded to 10 for breathing room around "bootstrap"; adminPort
        // 9 covers a 5-digit port and "(io err)").
        String rowFormat = "| %-4s | %-9s | %-10s | %-6s |%n";
        out.printf(rowFormat, "slot", "adminPort", "alive", "leased");
        out.println("|------|-----------|------------|--------|");
        for (int slot : slots) {
            Path portsFile = portsFile(poolDir, slot);
            String adminPort = "?";
            String alive = "?";
            String leased;
            boolean lockBusy = isLockBusy(poolDir, slot);
            if (Files.exists(portsFile)) {
                try {
                    SlotPorts ports = SlotPorts.readFrom(portsFile);
                    adminPort = String.valueOf(ports.adminPort());
                    alive = isPortHealthy(ports.adminPort()) ? "yes" : "no";
                } catch (IOException e) {
                    adminPort = "(io err)";
                }
            } else {
                alive = lockBusy ? "bootstrap" : "orphan";
            }
            leased = lockBusy ? "yes" : "no";
            out.printf(rowFormat, slot, adminPort, alive, leased);
        }
    }

    /**
     * Live-refreshing variant of {@link #status}: redraws the table every
     * {@code intervalMs} milliseconds until the JVM is interrupted (Ctrl+C).
     *
     * <p>Frame redraw is anchored on the previous frame's line count: cursor
     * up by that many lines, then per-line clear-to-EOL during reprint, then
     * a tail clear-to-end-of-screen iff the new frame is shorter. The Maven
     * banner above the first frame stays untouched.
     */
    public static void statusWatch(PoolConfig config, long intervalMs) {
        int previousLines = 0;
        // Leading \033[K wipes the line in place as it's rewritten; lets the
        // redraw stay non-blank instead of erasing then reprinting.
        String linePrefix = "\033[K" + WHITE + "[" + BOLD_GREEN + "LIVE" + WHITE + "]" + RESET + " ";
        while (true) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream framePrinter = new PrintStream(buf, false, StandardCharsets.UTF_8);
            status(config, framePrinter);
            framePrinter.println();
            framePrinter.println("Refreshing every " + (intervalMs / 1000) + "s — press Ctrl+C to exit");
            framePrinter.flush();
            String frame = prefixEveryLine(buf.toString(StandardCharsets.UTF_8), linePrefix);
            if (previousLines > 0) {
                System.out.print("\033[" + previousLines + "A");
            }
            System.out.print(frame);
            int currentLines = countLines(frame);
            if (currentLines < previousLines) {
                // Erase rows beyond the new frame so a shorter frame doesn't
                // leave old slot rows visible underneath.
                System.out.print("\033[J");
            }
            System.out.flush();
            previousLines = currentLines;
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Prepend {@code prefix} to every line in {@code text}. The trailing newline
     * after the last content line is preserved without spawning a phantom empty
     * prefixed line at the end.
     */
    private static String prefixEveryLine(String text, String prefix) {
        StringBuilder out = new StringBuilder(text.length() + prefix.length() * 16);
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.append(prefix).append(text, start, i).append('\n');
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.append(prefix).append(text, start, text.length());
        }
        return out.toString();
    }

    private static int countLines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * Stop everything and remove the pool dir entirely. Forceful reset. Like
     * {@link #down}, throws {@link IllegalStateException} when a slot keeps
     * running, and then leaves the pool dir in place.
     */
    public static void nuke(PoolConfig config) throws IOException {
        down(config);
        Path poolDir = config.poolDir();
        if (!isDirectory(poolDir)) {
            return;
        }
        try (var entries = Files.walk(poolDir)) {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    /* best-effort */
                }
            });
        }
    }

    /**
     * Antrun-friendly entrypoint. Reads {@link PoolConfig#fromSystemProperties}
     * and dispatches on the first arg: {@code up}, {@code down}, {@code status},
     * {@code nuke}, or {@code provision <idx>}.
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: PoolBootstrap up|down|status|nuke|provision <idx>");
            System.exit(1);
        }
        PoolConfig config = PoolConfig.fromSystemProperties();
        switch (args[0]) {
            case "up":
                up(config);
                break;
            case "down":
                down(config);
                break;
            case "status":
                status(config);
                break;
            case "nuke":
                nuke(config);
                break;
            case "provision":
                if (args.length < 2) {
                    throw new IllegalArgumentException("provision requires <idx>");
                }
                provisionSlot(config, Integer.parseInt(args[1]));
                break;
            default:
                System.err.println("Unknown command: " + args[0]);
                System.exit(1);
        }
    }

    /**
     * Arm a JVM shutdown hook that calls {@link #down}. Set on the JVM that
     * called {@link #up}, so a Ctrl+C of the Maven process — or a hard build
     * failure — still releases GlassFish processes. No-op when called outside
     * a Maven JVM (e.g. inside a forked failsafe JVM, where the test JVM
     * doesn't own the pool).
     *
     * <p>The one hook stops every pool {@code up} started in this JVM that
     * {@code down} didn't stop: one per module, or, under mvnd, whose JVM
     * outlives the build, one per build.
     */
    private static void installShutdownHook(PoolConfig config) {
        STARTED_POOLS.put(poolKey(config), config);
        if (!shutdownHookInstalled.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(PoolBootstrap::stopStartedPools, "glassfish-pool-shutdown"));
    }

    /** What the shutdown hook runs. Package-private for unit tests. */
    static void stopStartedPools() {
        for (PoolConfig started : STARTED_POOLS.values()) {
            try {
                down(started);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Pool shutdown hook failed", e);
            }
        }
    }

    private static Path poolKey(PoolConfig config) {
        return config.poolDir().toAbsolutePath().normalize();
    }

    private static List<Integer> listSlotIndices(Path poolDir) {
        List<Integer> slots = new ArrayList<>();
        try (var entries = Files.list(poolDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Integer idx = PoolPaths.slotIndex(entry.getFileName().toString());
                if (idx != null) {
                    slots.add(idx);
                }
            }
        } catch (IOException e) {
            return slots;
        }
        slots.sort(Comparator.naturalOrder());
        return slots;
    }

    /**
     * Probe whether the per-slot lock is held, by this JVM or another one.
     * Read-only: never creates the lock file. Status callers may run
     * concurrently with code that deletes slot dirs, and creating the lock
     * file would both falsely signal an in-flight bootstrap and resurrect a
     * file inside a directory being torn down.
     */
    static boolean isLockBusy(Path poolDir, int slot) {
        return PoolLocks.isBusy(PoolPaths.lockFile(poolDir, slot));
    }
}
