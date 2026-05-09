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
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;

/**
 * Print one row per slot: index, admin port, alive state, lease state.
 * Read-only; never modifies pool state.
 *
 * <p>On an interactive terminal the table refreshes every {@link #refreshIntervalSeconds}
 * seconds until Ctrl+C, like {@code top}. When stdout is piped or redirected, or
 * Maven runs in batch mode ({@code -B}), the table is printed once and the goal
 * exits — so logs and CI output don't fill with frames. Force one-shot in a TTY
 * with {@code -Dgf.pool.once}.
 */
@Mojo(name = "status", threadSafe = true, requiresProject = false)
public class StatusMojo extends AbstractPoolMojo {

    /**
     * Force a single-shot print even on an interactive terminal. Useful when
     * scripting against the goal from a TTY (e.g. {@code mvn ...:status -Dgf.pool.once | grep ...}).
     */
    @Parameter(property = "gf.pool.once", defaultValue = "false")
    protected boolean once;

    /** Refresh interval in seconds for the live table. Ignored in one-shot mode. */
    @Parameter(property = "gf.pool.interval", defaultValue = "1")
    protected int refreshIntervalSeconds;

    /**
     * Maven's interactive-mode flag. Set to {@code false} by {@code -B} /
     * {@code --batch-mode} regardless of whether stdout is a TTY, so we use
     * it to suppress live frames in CI even when CI hands us a real terminal.
     */
    @Parameter(defaultValue = "${settings.interactiveMode}", readonly = true)
    protected boolean interactiveMode;

    @Override
    public void execute() {
        if (once || !interactiveMode || System.console() == null) {
            PoolBootstrap.status(poolConfig());
        } else {
            PoolBootstrap.statusWatch(poolConfig(), refreshIntervalSeconds * 1000L);
        }
    }
}
