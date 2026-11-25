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

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Model;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;

/**
 * The project whose build directory holds a build's pool, by default: the
 * topmost project, around the one being built, whose own pom configures this
 * plugin, or else the project where Maven runs, when that's higher. So a
 * build started in a module of a multi-module project, such as a TCK with
 * the plugin in its parent, uses the same pool as one started at the root.
 *
 * <p>Only parents in a directory around the project count, not one from a
 * repository. The extension publishes the root's directory as
 * {@value #PROPERTY}, for paths in the configuration, such as a trusted
 * certificate's, that are relative to it.
 */
final class PoolRoot {

    /** The root's directory, as a property, close to Maven 4's {@code project.rootDirectory}. */
    static final String PROPERTY = "gf.pool.rootDirectory";

    private static final String KEY = PoolLifecycleParticipant.GROUP_ID + ":" + PoolLifecycleParticipant.ARTIFACT_ID;

    private PoolRoot() {
    }

    /** The root for {@code project}. */
    static MavenProject of(MavenProject project, MavenSession session) {
        MavenProject root = executionRoot(session);
        for (MavenProject ancestor = project; ancestor != null && contains(ancestor, project);
                ancestor = ancestor.getParent()) {
            if (configuresPlugin(ancestor.getOriginalModel()) && contains(ancestor, root)) {
                root = ancestor;
            }
        }

        return root;
    }

    /**
     * The project where Maven runs, also when {@code -pl} leaves it out of
     * the build. {@code getTopLevelProject()} then falls back to the first
     * module of the build.
     */
    static MavenProject executionRoot(MavenSession session) {
        List<MavenProject> allProjects = session.getAllProjects();
        if (allProjects != null) {
            for (MavenProject candidate : allProjects) {
                if (candidate.isExecutionRoot()) {
                    return candidate;
                }
            }
        }

        return session.getTopLevelProject();
    }

    /** Whether {@code outer}'s directory is {@code inner}'s, or one around it. */
    private static boolean contains(MavenProject outer, MavenProject inner) {
        if (outer == inner) {
            return true;
        }
        File outerDir = (outer == null) ? null : outer.getBasedir();
        File innerDir = (inner == null) ? null : inner.getBasedir();
        if (outerDir == null || innerDir == null) {
            return false;
        }

        return path(innerDir).startsWith(path(outerDir));
    }

    private static Path path(File dir) {
        return dir.toPath().toAbsolutePath().normalize();
    }

    /** Whether the pom itself, not a parent, configures this plugin: in its build, plugin management or a profile. */
    private static boolean configuresPlugin(Model model) {
        if (model == null) {
            return false;
        }
        if (declares(model.getBuild())) {
            return true;
        }
        for (Profile profile : model.getProfiles()) {
            if (declares(profile.getBuild())) {
                return true;
            }
        }

        return false;
    }

    private static boolean declares(BuildBase build) {
        if (build == null) {
            return false;
        }
        if (build.getPluginsAsMap().containsKey(KEY)) {
            return true;
        }
        PluginManagement management = build.getPluginManagement();

        return management != null && management.getPluginsAsMap().containsKey(KEY);
    }
}
