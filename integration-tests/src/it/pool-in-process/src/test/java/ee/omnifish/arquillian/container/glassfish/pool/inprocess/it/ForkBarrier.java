/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import static ee.omnifish.arquillian.container.glassfish.pool.inprocess.it.Http.get;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Two test JVMs at once, each hosting a GlassFish on a slot of its own: each
 * says where its GlassFish answers, then waits for the other's, so both run
 * at once, and each reaches its own and the other's. Each keeps its GlassFish up
 * until the other has reached it.
 */
final class ForkBarrier {

    private static final long WAIT_MILLIS = 180_000;

    private ForkBarrier() {
    }

    static void reachEachOther(String own, String other, URL base) throws Exception {
        Path barrier = Files.createDirectories(Paths.get(System.getProperty("pool.it.barrier")));
        Files.writeString(barrier.resolve(own + ".url"), base.toString());

        URL others = new URL(Files.readString(await(barrier.resolve(other + ".url"))));
        assertThat(get(base, "marker"), equalTo(own));
        assertThat(get(others, "marker"), equalTo(other));
        assertThat(others.getPort() == base.getPort(), equalTo(false));

        Files.writeString(barrier.resolve(own + ".done"), "done");
        await(barrier.resolve(other + ".done"));
    }

    private static Path await(Path file) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (!Files.exists(file)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("The other test JVM wrote no " + file);
            }
            Thread.sleep(200);
        }
        return file;
    }
}
