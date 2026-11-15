/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fake asadmin is a shell script")
class AsAdminTest {

    @Test
    void returnsTheOutput(@TempDir Path tmp) throws Exception {
        Path home = FakeGlassFish.install(tmp, "echo true\n");

        String output = new AsAdmin(home, 4848).run(Duration.ofSeconds(30), "_get-restart-required");

        assertThat(output, equalTo("true\n"));
    }

    /**
     * Regression: the output was read to its end before the timed wait, and
     * a hung asadmin keeps its output open, so the deadline never fired. The
     * sleep runs in a child of the script, which would keep running, and
     * keep the output open, if only the script were killed.
     */
    @Test
    void hungAsadminHitsItsDeadline(@TempDir Path tmp) throws Exception {
        Path home = FakeGlassFish.install(tmp,
                "echo starting\nsleep 60 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nwait\n");

        long start = System.nanoTime();
        AsAdmin.AsAdminException e = assertThrows(AsAdmin.AsAdminException.class,
                () -> new AsAdmin(home).run(Duration.ofMillis(500), "start-domain", "domain1"));

        assertThat(e.getMessage(), containsString("timed out"));
        assertThat(e.getMessage(), containsString("starting"));
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start), lessThan(10L));
        long child = Long.parseLong(Files.readString(home.resolve("glassfish/bin/child.pid")).trim());
        assertThat(isAliveAfterAWhile(child), equalTo(false));
    }

    /**
     * A cancelled call must not leave asadmin, or what it started, running:
     * during a start, the admin port may not answer yet, so nothing else
     * would stop them.
     */
    @Test
    void interruptedCallKillsAsadminAndWhatItStarted(@TempDir Path tmp) throws Exception {
        Path home = FakeGlassFish.install(tmp, "echo $$ > \"$(dirname \"$0\")/launcher.pid\"\n"
                + "sleep 60 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nwait\n");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                new AsAdmin(home).run(Duration.ofMinutes(1), "start-domain", "domain1");
            } catch (Throwable t) {
                thrown.set(t);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "asadmin-caller");
        caller.start();
        long launcher = awaitPid(home.resolve("glassfish/bin/launcher.pid"));
        long child = awaitPid(home.resolve("glassfish/bin/child.pid"));

        caller.interrupt();
        caller.join(10_000);

        assertThat(caller.isAlive(), equalTo(false));
        assertThat(thrown.get(), instanceOf(AsAdmin.AsAdminException.class));
        assertThat(stillInterrupted.get(), equalTo(true));
        assertThat(isAliveAfterAWhile(launcher), equalTo(false));
        assertThat(isAliveAfterAWhile(child), equalTo(false));
    }

    /** The pid the fake asadmin writes to {@code file}, once it has, waiting up to 10 s. */
    private static long awaitPid(Path file) throws Exception {
        for (int i = 0; i < 100; i++) {
            String pid = Files.exists(file) ? Files.readString(file).trim() : "";
            if (!pid.isEmpty()) {
                return Long.parseLong(pid);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No pid in " + file);
    }

    /** Whether process {@code pid} is still alive after up to 5 s, as killing it takes a moment. */
    private static boolean isAliveAfterAWhile(long pid) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }
}
