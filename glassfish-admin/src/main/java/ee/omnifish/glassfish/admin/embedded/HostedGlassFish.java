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
package ee.omnifish.glassfish.admin.embedded;

import java.io.IOException;
import java.lang.System.Logger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.glassfish.embeddable.BootstrapProperties;
import org.glassfish.embeddable.CommandResult;
import org.glassfish.embeddable.GlassFish;
import org.glassfish.embeddable.GlassFishException;
import org.glassfish.embeddable.GlassFishProperties;
import org.glassfish.embeddable.GlassFishRuntime;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * GlassFish Embedded in the JVM that asks for it, through GlassFish's
 * embeddable API, as the {@code glassfish-embedded} Arquillian Container runs
 * it: in a test JVM, from its class path, or in Maven, from a class loader
 * over the staged JAR or static shell. It starts on the given ports and
 * instance root, deploys and undeploys while it runs, and runs commands.
 *
 * <p>Closing it stops GlassFish, disposes of it, and shuts its runtime down,
 * so that the JVM can boot it again, as a test JVM that Failsafe reuses
 * does. GlassFish's own shutdown hook may have disposed of it first, as on
 * Ctrl+C, which closing tolerates. Then it puts back what GlassFish changed
 * in the JVM: the system properties, and the default TLS context and socket
 * factory, which GlassFish replaces with its own and leaves behind.
 *
 * <p>The JVM keeps one runtime per copy of GlassFish's API, so it hosts one
 * GlassFish at a time.
 *
 * <p>A JVM whose class path doesn't have GlassFish, as Maven's, loads it with
 * {@link #classLoader}.
 */
public final class HostedGlassFish implements AutoCloseable {

    private static final Logger LOG = System.getLogger(HostedGlassFish.class.getName());

    /** GlassFish's embeddable API, which this class calls, and so shares with the GlassFish it hosts. */
    private static final String API_PACKAGE = "org.glassfish.embeddable.";

    /** The GlassFish this copy of the class hosts, as GlassFish's runtime takes one at a time. */
    private static HostedGlassFish running;

    private final ClassLoader loader;
    private final GlassFishRuntime runtime;
    private final GlassFish glassfish;
    private final JvmSnapshot snapshot;

    private boolean closed;

    private HostedGlassFish(ClassLoader loader, GlassFishRuntime runtime, GlassFish glassfish, JvmSnapshot snapshot) {
        this.loader = loader;
        this.runtime = runtime;
        this.glassfish = glassfish;
        this.snapshot = snapshot;
    }

    /** What GlassFish boots with. */
    public static final class Settings {

        private Path installRoot;
        private Path instanceRoot;
        private Path configFile;
        private boolean configReadOnly = true;
        private int httpPort;
        private int httpsPort;
        private final Map<String, String> systemProperties = new LinkedHashMap<>();

        /** The install root, for an install's static shell that doesn't find it itself. */
        public Settings installRoot(Path installRoot) {
            this.installRoot = installRoot;
            return this;
        }

        /** The instance root, a domain directory, or none for a temporary one of Embedded's own. */
        public Settings instanceRoot(Path instanceRoot) {
            this.instanceRoot = instanceRoot;
            return this;
        }

        /** A {@code domain.xml} to run on instead of the instance root's. */
        public Settings configFile(Path configFile) {
            this.configFile = configFile;
            return this;
        }

        /** Whether GlassFish leaves its {@code domain.xml} as it is; {@code true} by default. */
        public Settings configReadOnly(boolean configReadOnly) {
            this.configReadOnly = configReadOnly;
            return this;
        }

        /** The HTTP listener's port, which applies whatever {@code domain.xml} says; 0 leaves it to that. */
        public Settings httpPort(int httpPort) {
            this.httpPort = httpPort;
            return this;
        }

        /** The HTTPS listener's port, which applies whatever {@code domain.xml} says; 0 leaves it to that. */
        public Settings httpsPort(int httpsPort) {
            this.httpsPort = httpsPort;
            return this;
        }

        /** A system property of the JVM while GlassFish runs. */
        public Settings systemProperty(String key, String value) {
            systemProperties.put(key, value);
            return this;
        }

        /** Each {@code key=value} of {@code properties} as a system property of the JVM while GlassFish runs. */
        public Settings systemProperties(List<String> properties) {
            for (String property : properties) {
                int equals = property.indexOf('=');
                systemProperty((equals < 0) ? property : property.substring(0, equals),
                        (equals < 0) ? "" : property.substring(equals + 1));
            }
            return this;
        }

        /**
         * The truststore that GlassFish's TLS context, which replaces the
         * JVM's default, trusts: GlassFish builds it from
         * {@code javax.net.ssl.trustStore}, and without one, it trusts
         * nothing. An install's static shell sets that to its instance's
         * {@code cacerts.p12} itself.
         */
        public Settings trustStore(Path trustStore, String password) {
            systemProperties.put("javax.net.ssl.trustStore", trustStore.toAbsolutePath().toString());
            systemProperties.put("javax.net.ssl.trustStorePassword", password);
            return this;
        }

        /**
         * What {@code glassfish} boots with on {@code ports}, from
         * {@code workDir}, as {@link EmbeddedWorkDir#forHost} made it: its
         * domain directory, HTTP, and HTTPS except for the JAR, which has no
         * server key, the RMI registry's port when the IIOP and JMS ports
         * move, and for the JAR, the truststore copy in the working directory.
         */
        public static Settings of(EmbeddedGlassFish glassfish, EmbeddedWorkDir workDir, EmbeddedPorts ports) {
            Settings settings = new Settings()
                    .instanceRoot(workDir.domainDir())
                    .httpPort(ports.http());
            if (!glassfish.isJar()) {
                settings.httpsPort(ports.https());
            }
            if (ports.movesOthers()) {
                settings.systemProperty(EmbeddedCommand.JMS_RMI_PORT_PROPERTY, String.valueOf(ports.jmsRmi()));
            }
            if (glassfish.isJar() && glassfish.trustStore() != null) {
                settings.trustStore(workDir.dir().resolve(EmbeddedWorkDir.TRUST_STORE),
                        EmbeddedWorkDir.TRUST_STORE_PASSWORD);
            }
            return settings;
        }
    }

    /**
     * A class loader over {@code classPath}, as {@link EmbeddedGlassFish#classPath}
     * gives it, for a JVM whose own class path doesn't have GlassFish, as
     * Maven's. Its parent is the platform class loader, so GlassFish and its
     * applications see neither Maven's libraries nor a plugin's, except for
     * GlassFish's embeddable API, which it takes from this class's own class
     * loader, so this class calls the API directly. Closing it, after the
     * GlassFish it loaded has closed, lets go of its JARs, as Windows needs.
     */
    public static URLClassLoader classLoader(List<Path> classPath) throws IOException {
        URL[] urls = new URL[classPath.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = classPath.get(i).toUri().toURL();
        }
        return new URLClassLoader("glassfish-embedded", urls, new EmbeddableApi(HostedGlassFish.class.getClassLoader()));
    }

    /** The platform class loader, and GlassFish's embeddable API from {@code api}. */
    private static final class EmbeddableApi extends ClassLoader {

        private final ClassLoader api;

        EmbeddableApi(ClassLoader api) {
            super("glassfish-embeddable-api", ClassLoader.getPlatformClassLoader());
            this.api = api;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith(API_PACKAGE)) {
                return api.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }

    /**
     * Boot GlassFish from {@code loader}, which has its classes, with
     * {@code settings}, and start it. When it doesn't start, what did is
     * shut down and the JVM restored.
     *
     * @throws IOException if it doesn't start, or the JVM hosts a
     *         GlassFish already
     */
    public static HostedGlassFish start(ClassLoader loader, Settings settings) throws IOException {
        synchronized (HostedGlassFish.class) {
            if (running != null) {
                throw new IOException("GlassFish Embedded already runs in this JVM, which hosts one at a time");
            }

            JvmSnapshot snapshot = JvmSnapshot.take();
            settings.systemProperties.forEach(System::setProperty);
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            GlassFishRuntime runtime = null;
            try {
                BootstrapProperties bootstrap = new BootstrapProperties();
                if (settings.installRoot != null) {
                    bootstrap.setInstallRoot(settings.installRoot.toString());
                }
                runtime = GlassFishRuntime.bootstrap(bootstrap, loader);
                GlassFishProperties properties = new GlassFishProperties();
                if (settings.instanceRoot != null) {
                    properties.setInstanceRoot(settings.instanceRoot.toString());
                }
                if (settings.configFile != null) {
                    properties.setConfigFile(settings.configFile.toFile());
                }
                properties.setConfigFileReadOnly(settings.configReadOnly);
                if (settings.httpPort > 0) {
                    properties.setPort("http-listener", settings.httpPort);
                }
                if (settings.httpsPort > 0) {
                    properties.setPort("https-listener", settings.httpsPort);
                }
                GlassFish glassfish = runtime.newGlassFish(properties);
                glassfish.start();
                running = new HostedGlassFish(loader, runtime, glassfish, snapshot);
                return running;
            } catch (GlassFishException | RuntimeException e) {
                if (runtime != null) {
                    shutDown(runtime);
                }
                snapshot.restore();
                throw new IOException("GlassFish Embedded didn't start: " + e.getMessage(), e);
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /** GlassFish itself, for what the methods here don't cover. */
    public GlassFish glassfish() {
        return glassfish;
    }

    /**
     * Deploy {@code archive}, with {@code options}, such as
     * {@code --libraries=a.jar}.
     *
     * @param name the application's name, or {@code null} for GlassFish's
     * @return the name GlassFish deployed it under
     */
    public String deploy(Path archive, String name, List<String> options) throws IOException {
        List<String> parameters = new ArrayList<>();
        if (name != null) {
            parameters.add("--name");
            parameters.add(name);
        }
        parameters.addAll(options);
        ClassLoader previous = useLoader();
        try {
            return glassfish.getDeployer().deploy(archive.toUri(), parameters.toArray(String[]::new));
        } catch (GlassFishException e) {
            throw new IOException("Deploying " + archive.getFileName() + " failed: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public void undeploy(String name) throws IOException {
        ClassLoader previous = useLoader();
        try {
            glassfish.getDeployer().undeploy(name);
        } catch (GlassFishException e) {
            throw new IOException("Undeploying " + name + " failed: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * Run the {@code asadmin} command {@code command} with {@code args}.
     *
     * @return its output
     * @throws IOException if it fails
     */
    public String run(String command, String... args) throws IOException {
        ClassLoader previous = useLoader();
        try {
            CommandResult result = glassfish.getCommandRunner().run(command, args);
            String output = (result.getOutput() == null) ? "" : result.getOutput();
            if (result.getExitStatus() == CommandResult.ExitStatus.FAILURE) {
                Throwable cause = result.getFailureCause();
                throw new IOException(command + " failed: " + output.trim()
                        + ((cause == null) ? "" : " (" + cause + ")"), cause);
            }
            if (result.getExitStatus() == CommandResult.ExitStatus.WARNING) {
                LOG.log(WARNING, command + ": " + output.trim());
            }
            return output;
        } catch (GlassFishException e) {
            throw new IOException(command + " failed: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** The context roots of the WAR deployed as {@code name}. */
    public List<String> contextRoots(String name) throws IOException {
        return EmbeddedStartup.contextRootsIn(run("get", "applications.application." + name + ".context-root"), name);
    }

    /**
     * The servlets of the application deployed as {@code name}, by each web
     * module's context root, starting with {@code /}, as GlassFish lists
     * them: for a WAR, with one {@code list-sub-components}; for an EAR, with
     * one that gives its web modules' context roots, and one per module.
     *
     * @param war whether the application is a WAR
     * @param webModules an EAR's web modules, by URI, each with the context
     *        root its descriptor gives, for when GlassFish doesn't say;
     *        empty for other archives
     */
    public Map<String, List<String>> servlets(String name, boolean war, Map<String, String> webModules)
            throws IOException {
        Map<String, List<String>> servlets = new LinkedHashMap<>();
        if (war) {
            List<String> contextRoots = contextRoots(name);
            servlets.put(contextRoots.isEmpty() ? "/" : EmbeddedStartup.rooted(contextRoots.get(0)),
                    EmbeddedStartup.servletsIn(run("list-sub-components", name)));
        } else if (!webModules.isEmpty()) {
            Map<String, String> reported = EmbeddedStartup.webModulesIn(run("list-sub-components", name));
            for (Map.Entry<String, String> module : webModules.entrySet()) {
                servlets.put(reported.getOrDefault(module.getKey(), EmbeddedStartup.rooted(module.getValue())),
                        EmbeddedStartup.servletsIn(run("list-sub-components", "--appname=" + name,
                                module.getKey())));
            }
        }

        return servlets;
    }

    /**
     * Stop GlassFish, dispose of it, and shut its runtime down, then put the
     * JVM's system properties and TLS defaults back as they were before it
     * started. A second call does nothing.
     */
    @Override
    public void close() {
        synchronized (HostedGlassFish.class) {
            if (closed) {
                return;
            }

            closed = true;
            ClassLoader previous = useLoader();
            try {
                // Each step on its own, as GlassFish's own shutdown hook may have taken some meanwhile.
                try {
                    glassfish.stop();
                } catch (GlassFishException | RuntimeException e) {
                    LOG.log(DEBUG, "Stopping GlassFish Embedded: " + e.getMessage());
                }
                try {
                    glassfish.dispose();
                } catch (GlassFishException | RuntimeException e) {
                    LOG.log(DEBUG, "Disposing of GlassFish Embedded: " + e.getMessage());
                }
                shutDown(runtime);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                snapshot.restore();
                running = null;
            }
        }
    }

    private static void shutDown(GlassFishRuntime runtime) {
        try {
            runtime.shutdown();
        } catch (GlassFishException | RuntimeException e) {
            LOG.log(DEBUG, "Shutting GlassFish Embedded's runtime down: " + e.getMessage());
        }
    }

    /** Make GlassFish's class loader the thread's context class loader, and return the one it replaces. */
    private ClassLoader useLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        return previous;
    }
}
