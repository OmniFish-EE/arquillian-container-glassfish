/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ee.omnifish.glassfish.admin.embedded.EmbeddedProcesses;

/**
 * A second JVM recorded as hosting GlassFish Embedded on a pool slot, as a
 * test JVM, or a glassfish-pool:run's Maven, is: the pool can't take the
 * test's own JVM for another one.
 *
 * <p>The second JVM runs {@link #main} from the test classes and
 * glassfish-admin's.
 */
final class HostStandIn implements AutoCloseable {

    private static final String HOSTING = "hosting";
    private static final long TIMEOUT_SECONDS = 30;

    private final Process process;

    private HostStandIn(Process process) {
        this.process = process;
    }

    /** A JVM recorded in {@code stageDir} as hosting GlassFish on {@code slotDir}, as a run with {@code run}. */
    static HostStandIn start(Path stageDir, Path slotDir, boolean run) throws Exception {
        String java = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String classPath = Stream.of(HostStandIn.class, EmbeddedProcesses.class)
                .map(HostStandIn::location)
                .collect(Collectors.joining(File.pathSeparator));
        Process process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", java).toString(),
                "-cp", classPath, HostStandIn.class.getName(), stageDir.toString(), slotDir.toString(),
                String.valueOf(run))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8));
        if (!HOSTING.equals(out.readLine())) {
            process.destroyForcibly();
            throw new IllegalStateException("The JVM that stands in for a host didn't record itself");
        }
        return new HostStandIn(process);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** End the host, which removes its record: it exits once its stdin closes. */
    @Override
    public void close() throws Exception {
        process.getOutputStream().close();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) {
        try {
            return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code <stageDir> <slotDir> <run>}: record this JVM, print {@value #HOSTING}, and host until stdin closes. */
    public static void main(String[] args) throws IOException {
        try (EmbeddedProcesses.Host host = EmbeddedProcesses.host(Paths.get(args[0]), Paths.get(args[1]),
                Boolean.parseBoolean(args[2]))) {
            System.out.println(HOSTING);
            System.out.flush();
            while (System.in.read() != -1) {
                // Host until the parent closes our stdin.
            }
        }
    }
}
