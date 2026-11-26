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
package ee.omnifish.glassfish.maven.staging;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import ee.omnifish.glassfish.admin.AsAdmin;
import ee.omnifish.glassfish.admin.AsAdmin.AsAdminException;
import ee.omnifish.glassfish.admin.DomainXmlEditor;
import ee.omnifish.glassfish.admin.PostBootCommands;

import static ee.omnifish.glassfish.admin.DomainXmlEditor.ADMIN_LISTENER;
import static ee.omnifish.glassfish.maven.staging.GlassFishStaging.DOMAIN_CONFIG;
import static java.util.stream.Collectors.toList;

/**
 * {@code domain1} of an install, as {@code glassfish:start} and
 * {@code glassfish:stop} run it: started and stopped with {@code asadmin}, and
 * known to run by the {@code config/pid} file GlassFish keeps while it runs,
 * naming a process that's alive, and that is this domain's server: GlassFish
 * passes its server the domain's directory. A stale {@code config/pid}, left
 * by a server that was killed, doesn't count, also when the system has given
 * its number to another process since. Without a {@code config/pid}, the
 * server is found by its command line, as after {@code mvn clean} deleted the
 * install it runs from.
 */
public final class GlassFishDomain {

    public static final String DOMAIN = "domain1";

    /** Deadline for {@code stop-domain}. */
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(2);

    /** Deadline for asking the running domain whether it needs a restart. */
    private static final Duration RESTART_QUERY_TIMEOUT = Duration.ofMinutes(1);

    /** How much longer {@code start-domain}'s own timeout is than the one this class enforces. */
    private static final Duration ASADMIN_TIMEOUT_MARGIN = Duration.ofMinutes(1);

    /** How long a stopped server's process may take to end. */
    private static final Duration DEFAULT_EXIT_WAIT = Duration.ofSeconds(10);

    private static final int PROBE_TIMEOUT_MILLIS = 1000;

    /** How GlassFish passes its server the domain's directory. */
    private static final String INSTANCE_ROOT = "-Dcom.sun.aas.instanceRoot=";


    /**
     * What GlassFish applies a change to only on a restart, without reporting that, by the name commands
     * use for it: no listener of its own takes the change, so the old port answers until the restart.
     */
    private static final Map<String, String> RESTART_ONLY = Map.of(
            ADMIN_LISTENER, "the admin listener",
            "jmx-connector", "the JMX connector",
            "iiop-listener", "an IIOP listener");

    /** Between the properties {@code create-system-properties} sets: a colon, unless escaped. */
    private static final Pattern PROPERTY_SEPARATOR = Pattern.compile("(?<!\\\\):");

    /** The dotted name {@code set} gives a system property's value, with dots in its name escaped. */
    private static final Pattern SYSTEM_PROPERTY_VALUE = Pattern.compile("(?:^|\\.)system-property\\.(.+)\\.value$");

    /** {@code asadmin} options that take a value, which a command line may start with. */
    private static final Set<String> VALUE_OPTIONS = Set.of("--host", "-H", "--port", "-p", "--user", "-u",
            "--passwordfile", "-W");

    private final Path home;
    private final Duration exitWait;

    /** @param home the install root, with {@code glassfish/bin/asadmin} under it */
    public GlassFishDomain(Path home) {
        this(home, DEFAULT_EXIT_WAIT);
    }

    /** Package-private for unit tests, which don't wait as long. */
    GlassFishDomain(Path home, Duration exitWait) {
        this.home = home;
        this.exitWait = exitWait;
    }

    public Path home() {
        return home;
    }

    /**
     * The process of the running domain: by its {@code config/pid}, if that
     * process is alive and this domain's, or, without a {@code config/pid},
     * by its command line. That finds a server whose install {@code mvn clean}
     * deleted while it ran, and one that waits for a debugger and has no
     * {@code config/pid} yet. Where the system doesn't say what a process
     * runs, only {@code config/pid} finds it; see {@link #isThisDomains}.
     */
    public Optional<ProcessHandle> process() {
        Path pidFile = pidFile();
        if (!Files.exists(pidFile)) {
            // The oldest: a process the server forks has its command line until it runs what it forked for.
            return ProcessHandle.allProcesses()
                    .filter(process -> process.info().commandLine().map(this::runsThisDomain).orElse(false))
                    .min(Comparator.comparing(process -> process.info().startInstant().orElse(Instant.MAX)));
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(process -> isThisDomains(process.info(), pidFile));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether the process {@code info} describes, which {@code pidFile}
     * names, is this domain's server: by its command line, or, where the
     * system doesn't say what a process runs, as on Windows, by when it
     * started. GlassFish writes {@code config/pid} after its server started,
     * while a process that got the number of a server killed since started
     * after that. Package-private for unit tests.
     */
    boolean isThisDomains(ProcessHandle.Info info, Path pidFile) {
        if (info.commandLine().isPresent()) {
            return runsThisDomain(info.commandLine().get());
        }
        try {
            Instant written = Files.getLastModifiedTime(pidFile).toInstant();
            return info.startInstant().map(started -> !started.isAfter(written)).orElse(false);
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether {@code commandLine} is that of this domain's server, which GlassFish passes the domain's directory. */
    private boolean runsThisDomain(String commandLine) {
        String args = commandLine + " ";
        return names(home.resolve(DOMAIN_CONFIG).getParent()).stream()
                .anyMatch(domainDir -> args.contains(INSTANCE_ROOT + domainDir + " "));
    }

    /**
     * {@code domain1} of an install directly under {@code stageDir}, if its
     * server runs: whichever distribution was unpacked there. It's found by
     * its {@code config/pid}, and otherwise by its command line, also after
     * {@code mvn clean} deleted the install. Where the system doesn't say
     * what a process runs, as on Windows, only {@code config/pid} finds it.
     */
    public static Optional<GlassFishDomain> runningIn(Path stageDir) {
        Optional<GlassFishDomain> byPidFile = runningByPidFileIn(stageDir);
        if (byPidFile.isPresent()) {
            return byPidFile;
        }

        List<Pattern> servers = new ArrayList<>();
        for (Path dir : names(stageDir)) {
            // The install's directory, then the domain's within it.
            servers.add(Pattern.compile(Pattern.quote(INSTANCE_ROOT + dir + File.separator) + "([^/\\\\]+)"
                    + Pattern.quote(File.separator + DOMAIN_CONFIG.getParent() + " ")));
        }

        return ProcessHandle.allProcesses()
                .map(process -> process.info().commandLine())
                .flatMap(Optional::stream)
                .flatMap(commandLine -> servers.stream().map(server -> server.matcher(commandLine + " ")))
                .filter(Matcher::find)
                .findFirst()
                .map(server -> new GlassFishDomain(stageDir.resolve(server.group(1))));
    }

    /** {@code domain1} of an install directly under {@code stageDir}, if its {@code config/pid} names its server. */
    private static Optional<GlassFishDomain> runningByPidFileIn(Path stageDir) {
        try (Stream<Path> installs = Files.list(stageDir)) {
            return installs.filter(install -> Files.exists(install.resolve(DOMAIN_CONFIG).resolve("pid")))
                    .map(GlassFishDomain::new)
                    .filter(GlassFishDomain::isRunning)
                    .findFirst();
        } catch (IOException e) {
            // Gone, as after mvn clean, or not a directory: no install to look in.
            return Optional.empty();
        }
    }

    /** {@code path}, and its real path if that differs, as a process may have been given either. */
    static List<Path> names(Path path) {
        List<Path> names = new ArrayList<>();
        names.add(path.toAbsolutePath().normalize());
        try {
            Path real = path.toRealPath();
            if (!real.equals(names.get(0))) {
                names.add(real);
            }
        } catch (IOException e) {
            // Gone, as after mvn clean, so there's no other name for it.
        }

        return names;
    }

    public boolean isRunning() {
        return process().isPresent();
    }

    /** The admin port in the domain's {@code domain.xml}, also when a system property sets it. */
    public int adminPort() throws IOException {
        Path domainXml = domainXml();
        String port = DomainXmlEditor.resolve(domainXml, DomainXmlEditor.ports(domainXml).get(ADMIN_LISTENER));
        if (port == null || !port.matches("\\d+")) {
            throw new IOException("No admin port in the domain.xml of " + home + ": " + port);
        }

        return Integer.parseInt(port);
    }

    /** Where the debugger attaches with {@code start-domain --debug}, from the domain's debug options. */
    public String debugAddress() {
        try {
            String address = DomainXmlEditor.debugAddress(domainXml());
            return (address == null) ? "9009" : address;
        } catch (IOException e) {
            return "9009";
        }
    }

    /**
     * Have the debugger listen on localhost only, where the domain's debug
     * options have it listen on every interface, as GlassFish's own
     * {@code *:9009} does: a debugger can run any code in the server. An
     * address with another host stays as it is.
     */
    public void keepDebuggerLocal() throws IOException {
        Path domainXml = domainXml();
        String address = DomainXmlEditor.debugAddress(domainXml);
        if (address != null && (address.startsWith("*:") || address.startsWith("0.0.0.0:"))) {
            DomainXmlEditor.setDebugAddress(domainXml, "localhost" + address.substring(address.indexOf(':')));
        }
    }

    /**
     * Run {@code start-domain}, which returns once the admin endpoint
     * answers. If it doesn't within {@code timeout}, {@code asadmin} is
     * killed with what it started, so a server JVM that waits for a debugger
     * doesn't stay behind. {@code start-domain}'s own timeout is set past
     * that: when it gives up first, it exits, and leaves such a JVM running,
     * without a {@code config/pid} yet to find it by. GlassFish 7.0 doesn't
     * have that option, and reads its timeout from {@code AS_START_TIMEOUT}
     * instead, in milliseconds.
     */
    public void start(boolean debug, boolean suspend, Duration timeout) {
        List<String> args = new ArrayList<>();
        if (debug) {
            args.add("--debug");
        }
        if (suspend) {
            args.add("--suspend");
        }
        args.add(DOMAIN);

        AsAdmin asadmin = new AsAdmin(home);
        Duration asadminTimeout = timeout.plus(ASADMIN_TIMEOUT_MARGIN);
        List<String> withTimeout = new ArrayList<>(List.of("--timeout", String.valueOf(asadminTimeout.toSeconds())));
        withTimeout.addAll(args);
        try {
            asadmin.run(timeout, "start-domain", withTimeout.toArray(String[]::new));
        } catch (AsAdminException e) {
            if (!e.getMessage().contains("Invalid option: --timeout")) {
                throw e;
            }
            // GlassFish 7.0 rejects the option before it starts anything.
            asadmin.withEnvironment("AS_START_TIMEOUT", String.valueOf(asadminTimeout.toMillis()))
                    .run(timeout, "start-domain", args.toArray(String[]::new));
        }
    }

    /**
     * Why the running domain needs a restart for {@code commands}, which ran
     * on it: that GlassFish reports one with {@code _get-restart-required},
     * for a change it can't apply while it runs, and the changes it needs a
     * restart for without reporting them; see {@link #unreportedRestart}.
     * Empty when it needs none. GlassFish's own reasons are left out: they
     * can hold values, such as a password in a {@code -D} option.
     *
     * @throws AsAdminException if {@code asadmin} fails
     * @throws IOException if {@code domain.xml} or GlassFish's answer can't be read
     */
    public List<String> restartReasons(int adminPort, List<String> commands) throws IOException {
        List<String> reasons = new ArrayList<>();
        if (isRestartRequired(adminPort)) {
            reasons.add("GlassFish reports that a change needs a restart");
        }
        String domainXml = Files.readString(domainXml());
        for (String command : commands) {
            String reason = unreportedRestart(command, domainXml);
            if (reason != null) {
                reasons.add(reason);
            }
        }

        return reasons;
    }

    /**
     * Why the domain needs a restart for {@code command}, which GlassFish
     * doesn't report, or null. It applies a change to the admin listener, the
     * JMX connector or an IIOP listener only on a restart. A system property
     * it sets at once, but what {@code domain.xml} sets with
     * {@code ${NAME}}, such as a JVM option, keeps the old value until then.
     * Only {@code set}, {@code create-} and {@code delete-} commands change
     * anything. The command is read as it runs, with its
     * {@code --passwordfile} in front. The reason names what changed, not the
     * command, whose values may be secrets. Package-private for unit tests.
     */
    static String unreportedRestart(String command, String domainXml) {
        List<String> words;
        try {
            words = PostBootCommands.passwordFileFirst(PostBootCommands.words(command));
        } catch (IllegalArgumentException e) {
            // It failed as a command, so it changed nothing.
            return null;
        }
        int at = subcommandAt(words);
        if (at < 0) {
            return null;
        }
        String subcommand = words.get(at);
        if (!subcommand.equals("set") && !subcommand.startsWith("create-") && !subcommand.startsWith("delete-")) {
            return null;
        }

        List<String> operands = words.subList(at + 1, words.size());
        String changes = "a " + subcommand + " command changes ";
        for (Map.Entry<String, String> restartOnly : RESTART_ONLY.entrySet()) {
            // In what an operand names, not in a value it sets.
            if (subcommand.contains(restartOnly.getKey())
                    || operands.stream().anyMatch(operand -> operand.split("=", 2)[0].contains(restartOnly.getKey()))) {
                return changes + restartOnly.getValue();
            }
        }
        for (String name : systemProperties(subcommand, operands)) {
            if (domainXml.contains("${" + name + "}")) {
                return changes + name + ", which domain.xml refers to";
            }
        }

        return null;
    }

    /** Where the subcommand is in {@code words}, after any {@code asadmin} options; -1 if there's none. */
    private static int subcommandAt(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (!word.startsWith("-")) {
                return i;
            }
            if (VALUE_OPTIONS.contains(word)) {
                // Its value.
                i++;
            }
        }

        return -1;
    }

    /** The names of the system properties {@code subcommand} sets or deletes. */
    private static List<String> systemProperties(String subcommand, List<String> operands) {
        List<String> names = new ArrayList<>();
        if (subcommand.equals("set")) {
            for (String operand : operands) {
                Matcher property = SYSTEM_PROPERTY_VALUE.matcher(operand.split("=", 2)[0]);
                if (property.find()) {
                    names.add(property.group(1).replace("\\.", "."));
                }
            }
        } else if ((subcommand.equals("create-system-properties") || subcommand.equals("delete-system-property"))
                && !operands.isEmpty()) {
            for (String property : PROPERTY_SEPARATOR.split(operands.get(operands.size() - 1))) {
                names.add(property.split("=", 2)[0]);
            }
        }

        return names;
    }

    /**
     * Whether GlassFish reports with {@code _get-restart-required} that it
     * needs a restart. Java may print notices before its answer, such as for
     * {@code JDK_JAVA_OPTIONS}.
     *
     * @throws IOException if the answer can't be read
     */
    private boolean isRestartRequired(int adminPort) throws IOException {
        List<String> lines = new AsAdmin(home, adminPort).run(RESTART_QUERY_TIMEOUT, "_get-restart-required")
                .lines()
                .map(String::trim)
                .collect(toList());
        if (lines.contains("true")) {
            return true;
        }
        if (lines.contains("false")) {
            return false;
        }

        // Without the output: it may hold what JDK_JAVA_OPTIONS sets.
        throw new IOException("GlassFish's answer to _get-restart-required can't be read");
    }

    /**
     * Stop the domain with a local {@code stop-domain}, which checks that it
     * reaches this domain, and with {@code stop-domain --kill} if this
     * domain's process still runs after that. Without a {@code config/pid},
     * which {@code asadmin} needs to find the process, it's asked to end,
     * and killed if it doesn't. A domain that doesn't run is left alone, also
     * when another server answers on its admin port.
     *
     * @return whether the domain ran
     * @throws IOException if the domain's process still runs afterwards
     */
    public boolean stop() throws IOException {
        return stopThrough(null);
    }

    /**
     * {@link #stop()}, through {@code adminPort}, where the domain answers:
     * {@code stop-domain} otherwise goes by the admin port in
     * {@code domain.xml}, which a command may have changed since the domain
     * started.
     */
    public boolean stop(int adminPort) throws IOException {
        return stopThrough(adminPort);
    }

    /**
     * Clears the thread's interrupt status meanwhile, which a cancelled
     * {@code asadmin} call leaves set, as with mvnd's Ctrl+C during a
     * post-start command, so the stop still runs; and restores it after.
     */
    private boolean stopThrough(Integer adminPort) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            Optional<ProcessHandle> process = process();
            if (process.isEmpty()) {
                return false;
            }

            if (Files.exists(pidFile())) {
                stopDomain(process.get(), adminPort);
            } else {
                process.get().destroy();
                if (!hasEnded(process.get())) {
                    process.get().destroyForcibly();
                }
            }
            if (!hasEnded(process.get())) {
                throw new IOException(DOMAIN + " of " + home + " still runs, as process " + process.get().pid()
                        + ", after it was stopped and killed");
            }

            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** {@code stop-domain}, and {@code stop-domain --kill} if {@code process} still runs after that. */
    private void stopDomain(ProcessHandle process, Integer adminPort) {
        AsAdmin asadmin = new AsAdmin(home, adminPort);
        try {
            asadmin.run(STOP_TIMEOUT, "stop-domain", DOMAIN);
        } catch (AsAdminException e) {
            // Killed below, if it still runs.
        }
        if (hasEnded(process)) {
            return;
        }

        try {
            asadmin.run(STOP_TIMEOUT, "stop-domain", "--kill", DOMAIN);
        } catch (AsAdminException e) {
            // Reported by stop, if it still runs.
        }
    }

    /** Whether something answers on {@code port} on this machine. */
    public static boolean isAnswering(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The domain's {@code server.log}, which may not exist yet. */
    public Path serverLog() {
        return home.resolve(DOMAIN_CONFIG).resolveSibling("logs").resolve("server.log");
    }

    private Path pidFile() {
        return home.resolve(DOMAIN_CONFIG).resolve("pid");
    }

    private Path domainXml() {
        return home.resolve(DOMAIN_CONFIG).resolve("domain.xml");
    }

    private boolean hasEnded(ProcessHandle process) {
        try {
            process.onExit().get(exitWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Still running: decided by isAlive below.
        }

        return !process.isAlive();
    }
}
