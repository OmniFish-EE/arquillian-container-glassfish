/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/** Which project's build directory holds the pool, for a project in a multi-module layout. */
class PoolRootTest {

    /** As the Jakarta Security TCK, with the plugin in its parent, built from a module's directory. */
    @Test
    void aBuildStartedInAModuleUsesTheParentThatConfiguresThePlugin() {
        MavenProject tck = project("/tck", null, Configures.PLUGIN_MANAGEMENT);
        MavenProject module = project("/tck/app-mem-basic", tck, Configures.NOTHING);

        assertSame(tck, PoolRoot.of(module, session(module, module)));
    }

    @Test
    void aPluginConfiguredInAProfileCounts() {
        MavenProject tck = project("/tck", null, Configures.PROFILE);
        MavenProject module = project("/tck/app-mem-basic", tck, Configures.NOTHING);

        assertSame(tck, PoolRoot.of(module, session(module, module)));
    }

    /** A parent from a repository isn't part of the project on disk. */
    @Test
    void aParentOutsideTheProjectsDirectoryDoesNotCount() {
        MavenProject corporate = project("/home/me/.m2/repository/corp/parent/1", null, Configures.BUILD);
        MavenProject app = project("/work/app", corporate, Configures.NOTHING);

        assertSame(app, PoolRoot.of(app, session(app, app)));
    }

    /** As today: where Maven runs stays the root, when that's higher than the poms that configure the plugin. */
    @Test
    void anAggregatorAboveThePomsThatConfigureThePluginStaysTheRoot() {
        MavenProject aggregator = project("/build", null, Configures.NOTHING);
        MavenProject group = project("/build/tests", aggregator, Configures.BUILD);
        MavenProject module = project("/build/tests/a", group, Configures.NOTHING);

        assertSame(aggregator, PoolRoot.of(module, session(aggregator, aggregator, group, module)));
    }

    @Test
    void withPlLeavingTheRootOutTheRootStays() {
        MavenProject tck = project("/tck", null, Configures.BUILD);
        MavenProject module = project("/tck/app-mem-basic", tck, Configures.NOTHING);

        assertSame(tck, PoolRoot.of(module, session(tck, module)));
    }

    @Test
    void theExtensionPublishesTheRootDirectoryAndLeavesTheUsersValue() {
        MavenProject tck = project("/tck", null, Configures.BUILD);
        MavenProject module = project("/tck/app-mem-basic", tck, Configures.NOTHING);
        MavenProject own = project("/tck/app-own", tck, Configures.NOTHING);
        own.getProperties().setProperty(PoolRoot.PROPERTY, "/elsewhere");

        PoolLifecycleParticipant.publishRootDirectory(module, session(module, module));
        PoolLifecycleParticipant.publishRootDirectory(own, session(own, own));

        assertEquals(new File("/tck").getAbsolutePath(), module.getProperties().getProperty(PoolRoot.PROPERTY));
        assertEquals("/elsewhere", own.getProperties().getProperty(PoolRoot.PROPERTY));
    }

    private enum Configures {
        NOTHING, BUILD, PLUGIN_MANAGEMENT, PROFILE
    }

    private static MavenProject project(String dir, MavenProject parent, Configures configures) {
        Model model = new Model();
        Build build = new Build();
        model.setBuild(build);
        Plugin plugin = new Plugin();
        plugin.setGroupId(PoolLifecycleParticipant.GROUP_ID);
        plugin.setArtifactId(PoolLifecycleParticipant.ARTIFACT_ID);
        switch (configures) {
            case BUILD:
                build.addPlugin(plugin);
                break;
            case PLUGIN_MANAGEMENT:
                build.setPluginManagement(new PluginManagement());
                build.getPluginManagement().addPlugin(plugin);
                break;
            case PROFILE:
                Profile profile = new Profile();
                profile.setBuild(new Build());
                profile.getBuild().addPlugin(plugin);
                model.addProfile(profile);
                break;
            default:
                break;
        }
        MavenProject project = new MavenProject();
        project.setFile(new File(dir, "pom.xml"));
        project.setOriginalModel(model);
        project.setParent(parent);
        project.getBuild().setDirectory(new File(dir, "target").getPath());
        return project;
    }

    /** A session that Maven runs in {@code executionRoot}, building {@code projects}. */
    private static MavenSession session(MavenProject executionRoot, MavenProject... projects) {
        executionRoot.setExecutionRoot(true);
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());
        List<MavenProject> all = new ArrayList<>(List.of(projects));
        if (!all.contains(executionRoot)) {
            all.add(0, executionRoot);
        }
        session.setProjects(List.of(projects));
        session.setAllProjects(all);
        return session;
    }
}
