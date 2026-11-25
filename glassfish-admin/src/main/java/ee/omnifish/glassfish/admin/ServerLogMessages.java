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

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passes on only the messages of the records in a GlassFish server log, in
 * its default ODL format, to read on a console. Of a record such as
 *
 * <pre>
 * [2026-11-24T19:21:21.649283+01:00] [GF 9.0.0] [INFO] [] [org.glassfish.soteria.servlet.SamRegistrationInstaller] [tid: _ThreadID=77 _ThreadName=admin-listener(4)] [levelValue: 800] [[
 *   Initializing Soteria 5.0.0 for context '/app-mem-basic']]
 * </pre>
 *
 * only {@code Initializing Soteria 5.0.0 for context '/app-mem-basic'} is
 * passed on: without the header up to {@code [[}, the indent of the message's
 * first line, the {@code ]]} that ends the record, and the empty line after
 * it. A message of several lines, such as one with a stack trace, keeps its
 * other lines as they are.
 *
 * <ul>
 *   <li>A record ends at the first line that ends with {@code ]]}. A message
 *       line that ends so itself ends its record early: the lines after it
 *       are passed on as they are, with the record's own {@code ]]}.</li>
 *   <li>Lines outside a record, as in another format, are passed on as they
 *       are.</li>
 * </ul>
 *
 * <p>It takes the lines of one log in order, as {@link ServerLogTailer} hands
 * them over, from one thread.
 */
public final class ServerLogMessages implements Consumer<String> {

    /** A record's header: at least three fields in brackets, then the first {@code [[}. */
    private static final Pattern HEADER = Pattern.compile("(?:\\[[^\\]]*\\] ){3,}?\\[\\[");
    private static final String END = "]]";
    private static final String INDENT = "  ";

    private final Consumer<String> messages;

    private boolean inRecord;
    private boolean firstLine;
    private boolean afterRecord;

    /** Pass on the messages of the lines this takes to {@code messages}. */
    public ServerLogMessages(Consumer<String> messages) {
        this.messages = messages;
    }

    @Override
    public void accept(String line) {
        String message = line;
        Matcher header = HEADER.matcher(line);
        if (header.lookingAt()) {
            inRecord = true;
            firstLine = true;
            message = line.substring(header.end());
            if (message.isEmpty()) {
                return;
            }
        } else if (!inRecord) {
            boolean separator = afterRecord && line.isEmpty();
            afterRecord = false;
            if (!separator) {
                messages.accept(line);
            }
            return;
        }

        if (firstLine && message.startsWith(INDENT)) {
            message = message.substring(INDENT.length());
        }
        firstLine = false;
        if (message.endsWith(END)) {
            inRecord = false;
            afterRecord = true;
            message = message.substring(0, message.length() - END.length());
            if (message.isEmpty()) {
                // The record's end on a line of its own, as after a stack trace.
                return;
            }
        }
        messages.accept(message);
    }
}
