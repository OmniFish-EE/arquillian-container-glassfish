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
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import ee.omnifish.glassfish.admin.rest.AdminConnection;
import ee.omnifish.glassfish.admin.rest.AdminRestClient;
import ee.omnifish.glassfish.admin.rest.DeployOptions;
import ee.omnifish.glassfish.admin.rest.GlassFishClientException;
import ee.omnifish.glassfish.admin.rest.NodeAddress;

/**
 * Deploys the project a run runs, and says where it answers. Shared by
 * {@code glassfish:run} and {@code glassfish-pool:run}.
 */
public final class RunDeployer {

    /** The packagings GlassFish deploys. */
    static final Set<String> DEPLOYABLE = Set.of("war", "ear", "ejb", "rar");

    private RunDeployer() {
    }

    /** Whether GlassFish deploys what {@code project} packages. */
    public static boolean isDeployable(MavenProject project) {
        return DEPLOYABLE.contains(project.getPackaging());
    }

    /** What {@code goal} logs for a project that isn't {@link #isDeployable deployable}. */
    public static String nothingToDeploy(MavenProject project, String goal) {
        return goal + " has nothing to deploy in a " + project.getPackaging()
                + " project; it deploys war, ear, ejb and rar projects";
    }

    /** The file {@code project}'s {@code package} made, for {@code goal}. */
    public static Path artifact(MavenProject project, String goal) throws MojoExecutionException {
        Artifact artifact = project.getArtifact();
        File file = (artifact == null) ? null : artifact.getFile();
        if (file == null || !file.isFile()) {
            throw new MojoExecutionException(goal + " has nothing to deploy: the package phase made no "
                    + project.getPackaging() + " for " + project.getArtifactId());
        }

        return file.toPath();
    }

    /**
     * Deploy {@code archive} to the domain on {@code adminPort}, replacing an
     * application of the same name, and log where it answers.
     *
     * @param name the application's name, or {@code null} for GlassFish's
     * @param contextRoot the context root of a WAR, or {@code null} for GlassFish's
     * @return the name GlassFish deployed it under
     */
    public static String deploy(int adminPort, Path archive, String name, String contextRoot, Log log)
            throws MojoExecutionException {
        AdminRestClient client = new AdminRestClient(AdminConnection.local(adminPort));
        try (InputStream in = Files.newInputStream(archive)) {
            NodeAddress node = client.startUp();
            String deployed = client.deploy(in, archive.getFileName().toString(),
                    new DeployOptions().name(name).contextRoot(contextRoot).force(true));
            List<String> urls = new ArrayList<>();
            for (String webContextRoot : client.webContextRoots(deployed)) {
                urls.add(url(node, webContextRoot));
            }
            log.info(deployed(deployed, urls));
            return deployed;
        } catch (GlassFishClientException | IOException e) {
            throw new MojoExecutionException("Deploying " + archive + " failed: " + e.getMessage(), e);
        }
    }

    /** The URL of {@code contextRoot} on {@code node}: over HTTP, or HTTPS when it has no HTTP port. */
    static String url(NodeAddress node, String contextRoot) {
        boolean secure = node.getHttpPort() <= 0 && node.getHttpsPort() > 0;
        return node.getURI(secure) + (contextRoot.endsWith("/") ? contextRoot : contextRoot + "/");
    }

    /** The URL of {@code contextRoot} over HTTP on {@code port} of this host. */
    public static String url(int port, String contextRoot) {
        String root = contextRoot.startsWith("/") ? contextRoot : "/" + contextRoot;
        return "http://localhost:" + port + (root.endsWith("/") ? root : root + "/");
    }

    /** What a run logs once it deployed {@code name}, which answers at {@code urls}. */
    public static String deployed(String name, List<String> urls) {
        return "Deployed " + name + (urls.isEmpty() ? "" : ", at " + String.join(", ", urls));
    }
}
