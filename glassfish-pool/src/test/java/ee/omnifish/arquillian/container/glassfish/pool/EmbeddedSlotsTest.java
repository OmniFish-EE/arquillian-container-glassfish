/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ee.omnifish.glassfish.admin.embedded.EmbeddedGlassFish;
import ee.omnifish.glassfish.admin.embedded.EmbeddedProcesses;

/**
 * A pool for GlassFish Embedded: its slots are blocks of ports, with nothing
 * to clone or start, and the children a test fork that died left running end
 * before their slot is leased again.
 */
class EmbeddedSlotsTest {

    @TempDir
    Path tmp;

    private final int basePort = freePort();
    private final List<Process> children = new ArrayList<>();
    private Path pool;
    private Path stage;

    @BeforeEach
    void describePool() throws Exception {
        pool = tmp.resolve("pool");
        stage = Files.createDirectories(tmp.resolve("dist"));
        EmbeddedPool.write(pool, EmbeddedGlassFish.jar(stage, stage.resolve("glassfish-embedded-all-9.0.0.jar"),
                null, null), "embedded");
    }

    @AfterEach
    void endChildren() {
        children.forEach(Process::destroyForcibly);
    }

    @Test
    void upCreatesSlotsWithoutCloningOrStarting() throws Exception {
        PoolConfig config = config(2);

        PoolBootstrap.up(config);

        for (int slot = 1; slot <= 2; slot++) {
            SlotPorts ports = SlotPorts.readFrom(PoolPaths.portsFile(pool, slot));
            assertThat(ports.isEmbedded(), is(true));
            assertThat(ports.httpPort(), equalTo(config.httpPort(slot)));
            assertThat(ports.embeddedPorts(0).iiop(), equalTo(config.iiopPort(slot)));
            assertThat(Files.exists(PoolPaths.slotDir(pool, slot).resolve(PoolProvisioner.SLOT_INSTALL_DIRNAME)),
                    is(false));
        }
        assertThat(status(config), containsString("| 1    | -         | idle       | no     |"));
    }

    /** Nothing listens on an idle slot, which is no reason to skip it. */
    @Test
    void anIdleSlotIsLeasedWithNothingListening() throws Exception {
        PoolBootstrap.up(config(1));

        try (SlotLease lease = new SlotLeaser(config(0), 10L).lease()) {
            assertThat(lease.slotIndex(), equalTo(1));
            assertThat(lease.ports().isEmbedded(), is(true));
            assertThat(lease.slotDir(), equalTo(PoolPaths.slotDir(pool, 1)));
        }
    }

    /** A slot for GlassFish Embedded needs no source install to grow by. */
    @Test
    void thePoolGrowsWithoutASource() throws Exception {
        Files.createDirectories(pool);

        try (SlotLease lease = new SlotLeaser(config(0), 10L).lease()) {
            assertThat(lease.slotIndex(), equalTo(1));
            assertThat(SlotPorts.readFrom(PoolPaths.portsFile(pool, 1)).isEmbedded(), is(true));
        }
    }

    /** A domain slot of an earlier build is recycled into one for GlassFish Embedded. */
    @Test
    void aDomainSlotIsRecycled() throws Exception {
        Files.createDirectories(PoolPaths.slotDir(pool, 1));
        new SlotPorts(freePort(), 14849, 14850, "/fake").writeTo(PoolPaths.portsFile(pool, 1));

        try (SlotLease lease = new SlotLeaser(config(0), 10L).lease()) {
            assertThat(lease.slotIndex(), equalTo(1));
            assertThat(lease.ports().isEmbedded(), is(true));
        }
    }

    /**
     * The OS releases a dead fork's lock, but its child can outlive it. The
     * next lease of its slot ends that child, by the stage directory's record
     * of it, and leaves the child of another slot alone.
     */
    @Test
    void aChildADeadForkLeftIsEndedBeforeItsSlotIsLeasedAgain() throws Exception {
        PoolBootstrap.up(config(2));
        ForeignJvm.Holder deadFork = ForeignJvm.hold(PoolPaths.lockFile(pool, 1));
        ForeignJvm.Holder otherFork = ForeignJvm.hold(PoolPaths.lockFile(pool, 2));
        try {
            Process orphan = child(1);
            Process other = child(2);
            deadFork.kill();

            try (SlotLease lease = new SlotLeaser(config(0), 30L).lease()) {
                assertThat(lease.slotIndex(), equalTo(1));
                assertThat(orphan.isAlive(), is(false));
                assertThat(other.isAlive(), is(true));
                assertThat(EmbeddedProcesses.serving(stage, PoolPaths.slotDir(pool, 1)).isEmpty(), is(true));
            }
        } finally {
            deadFork.kill();
            otherFork.close();
        }
    }

    /**
     * After up moved the pool to another stage directory, a child that a
     * fork that died left is still found, by its slot, which names the stage
     * directory the child started from, and ended before the slot's next lease.
     */
    @Test
    void aChildOfTheStageDirectoryBeforeIsEndedBeforeItsSlotIsLeasedAgain() throws Exception {
        PoolBootstrap.up(config(1));
        Process orphan = child(1);
        Path moved = Files.createDirectories(tmp.resolve("moved"));
        EmbeddedPool.write(pool, EmbeddedGlassFish.jar(moved, moved.resolve("glassfish-embedded-all-9.0.0.jar"),
                null, null), "embedded");

        try (SlotLease lease = new SlotLeaser(config(0), 30L).lease()) {
            assertThat(lease.slotIndex(), equalTo(1));
            assertThat(orphan.isAlive(), is(false));
        }
    }

    /** Also once up removed the pool's description, as it does when the pool runs domains again. */
    @Test
    void downEndsAChildAlsoWithoutTheDescription() throws Exception {
        PoolConfig config = config(1);
        PoolBootstrap.up(config);
        Process orphan = child(1);
        EmbeddedPool.remove(pool);

        PoolBootstrap.down(config);

        assertThat(orphan.isAlive(), is(false));
    }

    /** As after the build: what's left of the slots' children ends with the pool. */
    @Test
    void downEndsTheSlotsChildren() throws Exception {
        PoolConfig config = config(1);
        PoolBootstrap.up(config);
        Process child = child(1);
        assertThat(status(config), containsString("| 1    | -         | child      | no     |"));

        PoolBootstrap.down(config);

        assertThat(child.isAlive(), is(false));
    }

    /**
     * A slot a build set up with a debugger, as a glassfish-pool:run's, doesn't
     * keep it for the next build: the stop after the build has the next one
     * set the slot up afresh, as it does a domain slot.
     */
    @Test
    void theNextBuildSetsASlotUpAfresh() throws Exception {
        PoolConfig debugging = new PoolConfig(pool, null, 1, basePort, PoolConfig.DEFAULT_PORT_STRIDE,
                SlotSetup.NONE, true, false, true);
        PoolBootstrap.up(debugging);
        assertThat(SlotPorts.readFrom(PoolPaths.portsFile(pool, 1)).debugPort(), equalTo(debugging.debugPort(1)));

        PoolBootstrap.down(debugging);
        assertThat(Files.exists(PoolPaths.portsFile(pool, 1)), is(false));

        PoolBootstrap.up(config(1));
        assertThat(SlotPorts.readFrom(PoolPaths.portsFile(pool, 1)).debugPort(), equalTo(0));
    }

    /** A stand-in child, recorded in the stage directory as serving {@code slot}. */
    private Process child(int slot) throws Exception {
        Process child = EmbeddedProcesses.start(stage, PoolPaths.slotDir(pool, slot), false,
                () -> FakeEmbedded.sleeper(tmp));
        children.add(child);
        return child;
    }

    /** A pool of {@code size} slots for GlassFish Embedded, on ports nothing listens on. */
    private PoolConfig config(int size) throws Exception {
        return new PoolConfig(pool, null, size, basePort, PoolConfig.DEFAULT_PORT_STRIDE, SlotSetup.NONE, false,
                false, true);
    }

    private static String status(PoolConfig config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PoolBootstrap.status(config, new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
