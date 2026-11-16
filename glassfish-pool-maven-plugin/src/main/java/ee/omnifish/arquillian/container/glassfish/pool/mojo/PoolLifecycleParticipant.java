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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ee.omnifish.arquillian.container.glassfish.pool.PoolBootstrap;
import ee.omnifish.arquillian.container.glassfish.pool.PoolConfig;

import static ee.omnifish.glassfish.maven.LifecycleSupport.bind;
import static ee.omnifish.glassfish.maven.LifecycleSupport.forwardProperties;

/**
 * Binds {@code up} for every project that declares this plugin with
 * {@code <extensions>true</extensions>}, so the consumer pom needs no
 * {@code <executions>} block, and stops the pool once the build is done.
 * With {@code multiModule} off, it binds {@code down} instead.
 *
 * <p>A {@code lifecycle-mappings}/{@code LifecycleMapping} component could only
 * contribute bindings for a custom packaging type, which would force users off
 * {@code jar}/{@code war}. A lifecycle participant instead adds the
 * executions to the already-built project model, before Maven computes the
 * build plan. They carry no phase, so each goal lands on its mojo's
 * {@code defaultPhase} ({@code pre-}/{@code post-integration-test}).
 *
 * <p>Goals the user already binds in an explicit execution are left alone, so
 * existing poms that keep their {@code <executions>} block don't run a goal
 * twice. Registered via {@code META-INF/plexus/components.xml}; Maven only
 * loads it when the plugin is declared as an extension.
 *
 * <p>It also forwards the pool layout to the test JVMs: failsafe passes on
 * {@code -D} user properties and its own {@code systemPropertyVariables}, but
 * not project properties. So each of {@link UpMojo#FORWARDED_PROPERTIES} is
 * added to failsafe's {@code systemPropertyVariables} as a {@code ${...}}
 * reference, which Maven resolves when failsafe runs, after {@code up} has
 * published the values. A reference {@code up} never published (e.g.
 * {@code -Dgf.pool.skip}) resolves to an empty string, which the test-JVM side
 * treats as unset. Entries the user already configures are left alone.
 *
 * <p>With {@code multiModule}, the default, all modules share one pool, so it
 * doesn't bind {@code down}, which would stop the pool after the first
 * module's tests. Instead it stops the pools {@code up} started once the
 * whole build is done, in {@link #afterSessionEnd}, also in a single-module
 * build.
 *
 * <p>Binding and forwarding are shared with {@code glassfish-maven-plugin};
 * see {@link ee.omnifish.glassfish.maven.LifecycleSupport}.
 */
public class PoolLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    static final String GROUP_ID = "ee.omnifish.arquillian";
    static final String ARTIFACT_ID = "glassfish-pool-maven-plugin";
    static final String FAILSAFE_KEY = "org.apache.maven.plugins:maven-failsafe-plugin";

    private static final Logger LOG = LoggerFactory.getLogger(PoolLifecycleParticipant.class);

    /**
     * The pools {@code up} started with {@code multiModule} in this JVM, by
     * directory. All modules share one pool, so in practice it holds one.
     */
    private static final Map<Path, PoolConfig> SHARED_POOLS = new LinkedHashMap<>();

    /**
     * Stop the pool {@code config} describes once the build is done. Called
     * by {@code up} with {@code multiModule}. Maven loads a plugin that is an
     * extension, its mojos and this participant, from one class realm, so
     * they share {@link #SHARED_POOLS}.
     */
    static void stopAfterBuild(PoolConfig config) {
        synchronized (SHARED_POOLS) {
            SHARED_POOLS.put(config.poolDir().toAbsolutePath().normalize(), config);
        }
    }

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            Plugin plugin = project.getPlugin(GROUP_ID + ":" + ARTIFACT_ID);
            if (plugin != null) {
                bindGoals(plugin, properties(project, session));
                Plugin failsafe = project.getPlugin(FAILSAFE_KEY);
                if (failsafe != null) {
                    forwardPoolProperties(failsafe);
                }
            }
        }
    }

    /**
     * Stop the pools {@code up} started with {@code multiModule}, now that
     * all modules are done with them. A slot that keeps running fails the
     * build, as it does in {@code down}.
     */
    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        List<PoolConfig> pools;
        synchronized (SHARED_POOLS) {
            pools = new ArrayList<>(SHARED_POOLS.values());
            SHARED_POOLS.clear();
        }

        // All modules share one pool, so in practice this loops once.
        IllegalStateException stillRunning = null;
        for (PoolConfig pool : pools) {
            LOG.info("Stopping the pool at {}", pool.poolDir());
            try {
                PoolBootstrap.down(pool);
            } catch (IllegalStateException e) {
                LOG.error(e.getMessage());
                stillRunning = e;
            }
        }
        if (stillRunning != null) {
            throw new MavenExecutionException(stillRunning.getMessage(), stillRunning);
        }
    }

    /**
     * Bind {@code up}, and {@code down} unless all modules share the pool.
     * Package-private for unit tests.
     *
     * @param properties what a mojo's {@code property} resolves against
     */
    static void bindGoals(Plugin plugin, Properties properties) {
        bind(plugin, "pool-up", "up");
        if (!isMultiModule(plugin, properties)) {
            bind(plugin, "pool-down", "down");
        }
    }

    /**
     * What a mojo's {@code property} resolves against, in Maven's order:
     * {@code -D} values win over system properties, which win over the
     * pom's {@code <properties>}.
     */
    private static Properties properties(MavenProject project, MavenSession session) {
        Properties properties = new Properties();
        properties.putAll(project.getProperties());
        properties.putAll(session.getSystemProperties());
        properties.putAll(session.getUserProperties());
        return properties;
    }

    /**
     * Whether the plugin-level configuration sets {@code multiModule}, or
     * else the {@code gf.pool.multiModule} property does, as for the mojos.
     * The project model is interpolated by now, so a property reference in
     * the configuration works too. Unset, it's {@code true}, the mojos'
     * default, and so is a reference to a property that isn't defined, which
     * the mojos replace with their default.
     */
    private static boolean isMultiModule(Plugin plugin, Properties properties) {
        Xpp3Dom configuration = (Xpp3Dom) plugin.getConfiguration();
        Xpp3Dom multiModule = (configuration == null) ? null : configuration.getChild("multiModule");
        String value = (multiModule != null)
                ? multiModule.getValue()
                : properties.getProperty(AbstractPoolMojo.MULTI_MODULE_PROPERTY);
        if (value == null || value.isBlank() || value.contains("${")) {
            return true;
        }

        return Boolean.parseBoolean(value);
    }

    /**
     * Add a {@code ${key}} reference for each published pool property to the
     * failsafe plugin's {@code systemPropertyVariables}. Package-private for
     * unit tests.
     */
    static void forwardPoolProperties(Plugin failsafe) {
        forwardProperties(failsafe, UpMojo.FORWARDED_PROPERTIES);
    }
}
