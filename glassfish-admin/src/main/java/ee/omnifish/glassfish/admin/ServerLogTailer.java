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
package ee.omnifish.glassfish.admin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.READ;

/**
 * Follows a GlassFish server log, as {@code tail -F} does, and hands each
 * line written to it to a consumer, such as the console. The log on disk
 * stays as it is; this is a mirror for watching a local server while tests
 * run, not a lossless transport.
 *
 * <ul>
 *   <li>It starts at the file's end when following begins, so earlier output
 *       isn't repeated. A file that doesn't exist yet is followed from its
 *       start once it appears.</li>
 *   <li>Lines end at {@code \n}, without a {@code \r} before it, and are read
 *       as UTF-8. Bytes are kept until their line is complete, so a character
 *       split across two reads arrives whole. A line longer than
 *       {@value #MAX_LINE_BYTES} bytes is passed on in parts.</li>
 *   <li>When the log rotates, it reads what's left of the old file, and
 *       continues at the start of the new one. A file that shrinks was
 *       truncated, and is read again from its start.</li>
 *   <li>An I/O error, or a consumer that throws, stops following with one
 *       warning. Whatever is being tested doesn't depend on it.</li>
 * </ul>
 *
 * <p>A daemon thread polls the file. Only that thread touches the file after
 * {@link #follow} returns.
 */
public final class ServerLogTailer implements AutoCloseable {

    private static final Logger LOG = System.getLogger(ServerLogTailer.class.getName());

    private static final long POLL_MILLIS = 200;
    private static final long CLOSE_WAIT_MILLIS = 2_000;
    static final int MAX_LINE_BYTES = 64 * 1024;

    /** ANSI cyan, magenta, yellow, blue and green: distinct, and readable on dark and light backgrounds. */
    private static final String[] LABEL_COLORS = { "36", "35", "33", "34", "32" };

    private final Path file;
    private final Consumer<String> lines;
    private final ByteBuffer buffer = ByteBuffer.allocate(8192);
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final Object wakeUp = new Object();
    private Thread worker;

    private FileChannel channel;
    private Object fileKey;
    private long offset;
    private boolean failed;
    private volatile boolean closed;

    /**
     * Follow {@code file} from its current end, or from its start once it
     * appears, until {@link #close}. The position is fixed before this
     * returns, so a server started afterwards has all its output passed on.
     */
    public static ServerLogTailer follow(Path file, Consumer<String> lines) {
        ServerLogTailer tailer = new ServerLogTailer(file, lines);
        tailer.worker = new Thread(tailer::run, "server-log " + file);
        tailer.worker.setDaemon(true);
        tailer.worker.start();
        return tailer;
    }

    /**
     * {@code [label]} and a space, to put before each line passed on. With
     * {@code color}, the label gets the {@code n}th of a few ANSI colours,
     * which repeat, so lines of several servers can be told apart.
     */
    public static String prefix(String label, int n, boolean color) {
        if (!color) {
            return "[" + label + "] ";
        }

        return "\u001b[" + LABEL_COLORS[Math.floorMod(n, LABEL_COLORS.length)] + "m[" + label + "]\u001b[0m ";
    }

    /** Package-private for unit tests, which call {@link #poll} themselves. */
    ServerLogTailer(Path file, Consumer<String> lines) {
        this.file = file;
        this.lines = lines;
        try {
            open();
            if (channel != null) {
                offset = channel.size();
            }
        } catch (IOException e) {
            fail("Can't read " + file + ": " + e);
        }
    }

    /**
     * Pass on what's left of the file, including a last line without its
     * {@code \n}, and stop following. Waits a little for that, not for a
     * consumer that blocks.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        synchronized (wakeUp) {
            wakeUp.notifyAll();
        }
        try {
            if (worker != null) {
                worker.join(CLOSE_WAIT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (!closed && !failed) {
            poll();
            synchronized (wakeUp) {
                if (!closed) {
                    try {
                        wakeUp.wait(POLL_MILLIS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
        finish();
    }

    /** Pass on the lines written since the last poll. Package-private for unit tests. */
    void poll() {
        if (failed) {
            return;
        }

        try {
            if (channel == null) {
                open();
                if (channel == null) {
                    return;
                }
            }
            long size = channel.size();
            if (size < offset) {
                // Truncated: what was read is gone, and the file starts over.
                offset = 0;
                line.reset();
            }
            readTo(size);
            if (isReplaced()) {
                endLine();
                closeChannel();
                open();
                if (channel != null) {
                    readTo(channel.size());
                }
            }
        } catch (IOException e) {
            fail("Stopped following " + file + ": " + e);
        }
    }

    /** The last poll, and a last line without its {@code \n}. Package-private for unit tests. */
    void finish() {
        poll();
        endLine();
        closeChannel();
    }

    /** Open the file, if it exists, at its start. */
    private void open() throws IOException {
        try {
            channel = FileChannel.open(file, READ);
            fileKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
            offset = 0;
        } catch (NoSuchFileException e) {
            channel = null;
        }
    }

    /**
     * Whether another file now has the name, as after a rotation. Where the
     * file system has no file keys, a file shorter than what was read counts
     * as another. A file that's briefly missing is waited for.
     */
    private boolean isReplaced() throws IOException {
        BasicFileAttributes current;
        try {
            current = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return false;
        }
        if (fileKey != null && current.fileKey() != null) {
            return !fileKey.equals(current.fileKey());
        }

        return current.size() < offset;
    }

    /** Read up to {@code end}, which stays fixed, so a file that keeps growing can't hold this up. */
    private void readTo(long end) throws IOException {
        while (offset < end && !failed) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), end - offset));
            int read = channel.read(buffer, offset);
            if (read <= 0) {
                return;
            }
            offset += read;
            take(buffer.array(), read);
        }
    }

    private void take(byte[] bytes, int length) {
        int start = 0;
        for (int i = 0; i < length && !failed; i++) {
            if (bytes[i] == '\n') {
                line.write(bytes, start, i - start);
                passOn(line.toByteArray());
                start = i + 1;
            } else if (line.size() + (i + 1 - start) >= MAX_LINE_BYTES) {
                line.write(bytes, start, i + 1 - start);
                passOn(line.toByteArray());
                start = i + 1;
            }
        }
        if (!failed) {
            line.write(bytes, start, length - start);
        }
    }

    /** Pass on the line read so far, if any, as when the file ends without a {@code \n}. */
    private void endLine() {
        if (line.size() > 0) {
            passOn(line.toByteArray());
        }
    }

    /** Pass on {@code bytes} as a line, also an empty one, and start the next. */
    private void passOn(byte[] bytes) {
        line.reset();
        if (failed) {
            return;
        }

        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        try {
            lines.accept(new String(bytes, 0, length, UTF_8));
        } catch (RuntimeException e) {
            fail("Stopped following " + file + ", since passing a line on failed: " + e);
        }
    }

    private void fail(String message) {
        failed = true;
        LOG.log(WARNING, message);
        closeChannel();
    }

    private void closeChannel() {
        if (channel == null) {
            return;
        }

        try {
            channel.close();
        } catch (IOException e) {
            // Nothing more to read from it either way.
        }
        channel = null;
    }
}
