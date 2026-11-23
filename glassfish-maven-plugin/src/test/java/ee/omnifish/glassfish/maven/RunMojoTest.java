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
package ee.omnifish.glassfish.maven;

import static ee.omnifish.glassfish.maven.AbstractStartMojo.PostBootRestart.AUTO;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.admin.rest.NodeAddress;
import ee.omnifish.glassfish.maven.staging.FakeDomain;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;
import ee.omnifish.glassfish.maven.staging.StagingCommands;

/** {@code run}'s ending, on an install whose {@code asadmin} is a shell script, and what it does before it starts. */
class RunMojoTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** {@code stop-domain}, slow enough for a second stop to come while it runs. */
    private static final String SLOW_STOP = "  *stop-domain*) sleep 1; kill $(cat \"$PID\") && rm -f \"$PID\" ;;\n";

    @TempDir
    Path home;

    private final RecordingLog log = new RecordingLog();

    @AfterEach
    void killServer() {
        FakeDomain.killServer(home);
    }

    /** Otherwise the JVM could exit, its shutdown hook done, while the stop finally called still runs. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void aSecondStopReturnsOnceTheFirstHasStoppedTheDomain() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, 4848, SLOW_STOP));
        FakeDomain.startServer(home);
        try (RunningDomain running = new RunningDomain(domain, 4848, "", log)) {
            Thread first = new Thread(running::stop);
            first.start();
            awaitCall("stop-domain");

            running.stop();

            assertThat(domain.isRunning(), is(false));
            assertThat(stops(), is(1L));
            first.join();
        }
    }

    /** As after glassfish:stop from another terminal. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void theRunEndsWhenTheDomainStopsOtherwise() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, 4848, FakeDomain.STOP));
        Process server = FakeDomain.startServer(home);
        RunningDomain running = new RunningDomain(domain, 4848, "", log);
        Thread run = new Thread(running::untilStopped);
        run.start();

        server.destroy();
        run.join(10_000);

        assertThat(run.isAlive(), is(false));
        assertThat(FakeDomain.calls(home), is(empty()));
    }

    /** As mvnd's Ctrl+C does. The interrupt is kept, for after the stop, which asadmin would give up on. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void anInterruptedRunStopsTheDomainAndKeepsTheInterrupt() throws Exception {
        GlassFishDomain domain = new GlassFishDomain(FakeDomain.install(home, 4848, FakeDomain.STOP));
        FakeDomain.startServer(home);
        RunningDomain running = new RunningDomain(domain, 4848, "", log);
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread run = new Thread(() -> {
            running.untilStopped();
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        run.start();
        while (run.getState() != Thread.State.WAITING) {
            Thread.sleep(10);
        }

        run.interrupt();
        run.join(10_000);

        assertThat(run.isAlive(), is(false));
        assertThat(domain.isRunning(), is(false));
        assertThat(stops(), is(1L));
        assertThat(interruptedAfter.get(), is(true));
    }

    /**
     * {@code run} learns that its domain starts after the check that nothing
     * answers on its admin port. So a second run, which fails there, doesn't
     * stop the domain of the first, which shares its install.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
    void aStartThatFailsOnTheOccupiedPortTellsNoOneThatTheDomainStarts() throws Exception {
        List<Integer> starting = new ArrayList<>();
        try (ServerSocket firstRun = new ServerSocket(0)) {
            GlassFishDomain domain = new GlassFishDomain(
                    FakeDomain.install(home, firstRun.getLocalPort(), FakeDomain.START));

            assertThrows(MojoExecutionException.class, () -> AbstractStartMojo.start(domain, StagingCommands.NONE,
                    AUTO, false, false, TIMEOUT, (started, adminPort) -> starting.add(adminPort), log));
        }

        assertThat(starting, is(empty()));
    }

    /** Without a distribution, it would fail on provisioning if it got that far. */
    @Test
    void aProjectThatIsNoApplicationStartsNothing() throws Exception {
        RunMojo run = runMojo("jar");

        run.execute();

        assertThat(log.infos, hasItem(containsString("nothing to deploy in a jar project")));
    }

    @Test
    void aMissingArtifactFailsTheRunBeforeAnythingStarts() throws Exception {
        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> runMojo("war").execute());

        assertThat(e.getMessage(), containsString("the package phase made no war for app"));
    }

    /** They skip the tests of the package that run runs. */
    @Test
    void skipTestsDoesNotSkipTheRun() throws Exception {
        RunMojo run = runMojo("war");
        set(run, AbstractStagingMojo.class, "skipTests", true);
        set(run, AbstractStagingMojo.class, "mavenTestSkip", true);

        MojoExecutionException e = assertThrows(MojoExecutionException.class, run::execute);

        assertThat(e.getMessage(), containsString("the package phase made no war"));
    }

    @Test
    void theUrlIsHttpsWithoutAnHttpPort() {
        assertThat(RunMojo.url(new NodeAddress("server", "localhost", 8080, 8181), "/run-it"),
                is("http://localhost:8080/run-it/"));
        assertThat(RunMojo.url(new NodeAddress("server", "localhost", -1, 8181), "/run-it"),
                is("https://localhost:8181/run-it/"));
    }

    private RunMojo runMojo(String packaging) throws ReflectiveOperationException {
        MavenProject project = new MavenProject();
        project.setArtifactId("app");
        project.setPackaging(packaging);
        RunMojo run = new RunMojo();
        run.setLog(log);
        set(run, RunMojo.class, "executedProject", project);
        return run;
    }

    private static void set(Object target, Class<?> declaringClass, String field, Object value)
            throws ReflectiveOperationException {
        Field declared = declaringClass.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private void awaitCall(String command) throws IOException, InterruptedException {
        for (int i = 0; i < 100 && FakeDomain.calls(home).stream().noneMatch(call -> call.contains(command)); i++) {
            Thread.sleep(50);
        }
    }

    private long stops() throws IOException {
        return FakeDomain.calls(home).stream().filter(call -> call.contains("stop-domain")).count();
    }
}
