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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import ee.omnifish.glassfish.admin.ServerLogTailer;
import ee.omnifish.glassfish.admin.rest.AdminConnection;
import ee.omnifish.glassfish.admin.rest.AdminRestClient;
import ee.omnifish.glassfish.admin.rest.DeployOptions;
import ee.omnifish.glassfish.admin.rest.GlassFishClientException;
import ee.omnifish.glassfish.admin.rest.NodeAddress;
import ee.omnifish.glassfish.maven.staging.GlassFishDomain;

/**
 * Package the project, start {@code domain1} of the GlassFish install as
 * {@code glassfish:start} does, deploy the project's artifact to it, and keep
 * running, with GlassFish's {@code server.log} on the console, until Ctrl+C
 * stops GlassFish. When GlassFish stops otherwise, as with
 * {@code glassfish:stop} from another terminal, this goal returns.
 *
 * <p>The artifact replaces an application deployed with the same name, so a
 * run after a change needs no {@code clean}. Only {@code war}, {@code ear},
 * {@code ejb} and {@code rar} projects are deployed; for others, this goal
 * does nothing. {@code -DskipTests} skips the tests of the {@code package}
 * this goal runs, not this goal.
 */
@Mojo(name = "run", threadSafe = true)
@Execute(phase = LifecyclePhase.PACKAGE)
public class RunMojo extends AbstractStartMojo {

    /** The packagings GlassFish deploys. */
    static final Set<String> DEPLOYABLE = Set.of("war", "ear", "ejb", "rar");

    /**
     * The application's name. GlassFish takes it from an EAR's
     * {@code application.xml}, or else from the artifact's file name, without
     * its extension. Set it to keep one deployment when the version in that
     * file name changes.
     */
    @Parameter(property = "glassfish.run.name")
    private String name;

    /**
     * The context root of a WAR. GlassFish takes it from its
     * {@code glassfish-web.xml}, or else from its name.
     */
    @Parameter(property = "glassfish.run.contextRoot")
    private String contextRoot;

    /** The project as the {@code package} this goal runs left it, with its artifact. */
    @Parameter(defaultValue = "${executedProject}", readonly = true, required = true)
    private MavenProject executedProject;

    private RunningDomain running;

    @Override
    public void execute() throws MojoExecutionException {
        if (!DEPLOYABLE.contains(executedProject.getPackaging())) {
            getLog().info("glassfish:run has nothing to deploy in a " + executedProject.getPackaging()
                    + " project; it deploys war, ear, ejb and rar projects");
            return;
        }

        Path archive = artifact(executedProject);
        try {
            deploy(startDomain(), archive);
        } catch (MojoExecutionException | RuntimeException e) {
            if (running != null) {
                running.close();
            }
            throw e;
        }

        getLog().info("Ctrl+C stops GlassFish, as glassfish:stop does");
        running.untilStopped();
    }

    /**
     * Mirror the domain's log from its first start on, and from then on, stop
     * it when this goal ends: after the check that nothing answers on its
     * admin port, so a domain another run started is left alone.
     */
    @Override
    protected void starting(GlassFishDomain domain, int adminPort) {
        if (running == null) {
            String prefix = ServerLogTailer.prefix("glassfish:" + GlassFishDomain.DOMAIN, 0,
                    LifecycleSupport.isConsoleColored());
            running = new RunningDomain(domain, adminPort, prefix, getLog());
        }
        running.adminPort(adminPort);
    }

    /**
     * The file {@code project}'s {@code package} made. Package-private for
     * unit tests.
     */
    static Path artifact(MavenProject project) throws MojoExecutionException {
        Artifact artifact = project.getArtifact();
        File file = (artifact == null) ? null : artifact.getFile();
        if (file == null || !file.isFile()) {
            throw new MojoExecutionException("glassfish:run has nothing to deploy: the package phase made no "
                    + project.getPackaging() + " for " + project.getArtifactId());
        }

        return file.toPath();
    }

    /** Deploy {@code archive} to the domain on {@code adminPort}, and log where it answers. */
    private void deploy(int adminPort, Path archive) throws MojoExecutionException {
        AdminRestClient client = new AdminRestClient(AdminConnection.local(adminPort));
        try (InputStream in = Files.newInputStream(archive)) {
            NodeAddress node = client.startUp();
            String deployed = client.deploy(in, archive.getFileName().toString(),
                    new DeployOptions().name(name).contextRoot(contextRoot).force(true));
            List<String> urls = new ArrayList<>();
            for (String webContextRoot : client.webContextRoots(deployed)) {
                urls.add(url(node, webContextRoot));
            }
            getLog().info("Deployed " + deployed + (urls.isEmpty() ? "" : ", at " + String.join(", ", urls)));
        } catch (GlassFishClientException | IOException e) {
            throw new MojoExecutionException("Deploying " + archive + " failed: " + e.getMessage(), e);
        }
    }

    /** The URL of {@code contextRoot} on {@code node}: over HTTP, or HTTPS when it has no HTTP port. */
    static String url(NodeAddress node, String contextRoot) {
        boolean secure = node.getHttpPort() <= 0 && node.getHttpsPort() > 0;
        return node.getURI(secure) + (contextRoot.endsWith("/") ? contextRoot : contextRoot + "/");
    }
}
