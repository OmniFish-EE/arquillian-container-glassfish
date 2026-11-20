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

import java.util.List;
import java.util.Objects;

import static java.util.stream.Collectors.toUnmodifiableList;

/**
 * {@code asadmin} commands for {@link GlassFishStaging} to run on the staged
 * install, during a start after the overlays and certificates: a brief one of
 * their own, or the one {@code glassfish:start} makes. They're setup whose
 * effects persist, such as users, resources and {@code set} calls. They run
 * once per staging, so only on an install staging unpacks itself.
 */
public final class StagingCommands {

    /** No commands. */
    public static final StagingCommands NONE = new StagingCommands(null, true);

    private final List<String> lines;
    private final boolean failOnError;

    /**
     * @param commands one command per line; blank lines and lines starting
     *        with {@code #} are skipped
     * @param failOnError whether a failed command fails the staging, which is
     *        then redone by the next run; otherwise the failure is logged
     */
    public StagingCommands(String commands, boolean failOnError) {
        this.lines = (commands == null) ? List.of() : commands.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(toUnmodifiableList());
        this.failOnError = failOnError;
    }

    public List<String> lines() {
        return lines;
    }

    public boolean failOnError() {
        return failOnError;
    }

    /** True iff there is a command to run. */
    public boolean hasWork() {
        return !lines.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StagingCommands)) {
            return false;
        }
        StagingCommands other = (StagingCommands) o;
        return failOnError == other.failOnError
                && lines.equals(other.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lines, failOnError);
    }
}
