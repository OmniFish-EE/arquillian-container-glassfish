/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import static ee.omnifish.arquillian.container.glassfish.pool.inprocess.it.Http.get;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Containers the test starts and stops itself, with arquillian-manual.xml,
 * one at a time, in one test JVM: GlassFish boots again after it stopped, as
 * in a failsafe JVM that runs another suite, it runs on a domain.xml or an
 * instance root of the user's own with the leased slot's ports, and the test
 * JVM hosts one GlassFish at a time.
 */
@ExtendWith(ArquillianExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@RunAsClient
public class ManualIT {

    private static final Path USER_CONFIG = Paths.get(System.getProperty("pool.it.userConfig"));

    @ArquillianResource
    private ContainerController controller;

    @ArquillianResource
    private Deployer deployer;

    @Deployment(name = "restarted", managed = false, testable = false)
    @TargetsContainer("restartable")
    public static WebArchive restarted() {
        return Archives.marked("restarted.war", "restarted");
    }

    @Deployment(name = "on-domain-xml", managed = false, testable = false)
    @TargetsContainer("with-domain-xml")
    public static WebArchive onDomainXml() {
        return Archives.marked("on-domain-xml.war", "on-domain-xml");
    }

    @Deployment(name = "on-instance-root", managed = false, testable = false)
    @TargetsContainer("with-instance-root")
    public static WebArchive onInstanceRoot() {
        return Archives.marked("on-instance-root.war", "on-instance-root");
    }

    /**
     * Embedded's own domain.xml, from the test JVM's GlassFish, in an instance
     * root, and as a user's domain.xml, whose listeners are named as a
     * distribution's domain1 names them.
     */
    @BeforeAll
    static void writeUserConfig() throws IOException {
        Path instanceConfig = Files.createDirectories(USER_CONFIG.resolve("instance/config"));
        try (InputStream in = ManualIT.class.getClassLoader().getResourceAsStream("org/glassfish/embed/domain.xml")) {
            String domainXml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(instanceConfig.resolve("domain.xml"), domainXml);
            Files.writeString(USER_CONFIG.resolve("domain.xml"), domainXml
                    .replace("https-listener", "http-listener-2")
                    .replaceAll("http-listener(?![-\\w])", "http-listener-1"));
        }
        Files.writeString(USER_CONFIG.resolve("instance/marker.txt"), "the user's");
    }

    @Test
    @Order(1)
    public void startTheFirstTime() {
        controller.start("restartable");
        deployer.deploy("restarted");
    }

    @Test
    @Order(2)
    public void servesTheFirstTime(@ArquillianResource @OperateOnDeployment("restarted") URL url) throws Exception {
        assertThat(get(url, "marker"), equalTo("restarted"));
    }

    /** Stopping shut GlassFish down, and gave the JVM back its system properties, so it boots again. */
    @Test
    @Order(3)
    public void startAgainAfterAStop() {
        deployer.undeploy("restarted");
        controller.stop("restartable");
        controller.start("restartable");
        deployer.deploy("restarted");
    }

    @Test
    @Order(4)
    public void servesAgain(@ArquillianResource @OperateOnDeployment("restarted") URL url) throws Exception {
        assertThat(get(url, "marker"), equalTo("restarted"));

        deployer.undeploy("restarted");
        controller.stop("restartable");
    }

    @Test
    @Order(5)
    public void startOnADomainXmlOfTheUsers() {
        controller.start("with-domain-xml");
        deployer.deploy("on-domain-xml");
    }

    /** Its listeners take the leased slot's ports, whatever the domain.xml says, and whatever it names them. */
    @Test
    @Order(6)
    public void servesOnTheLeasedPortWithADomainXmlOfTheUsers(
            @ArquillianResource @OperateOnDeployment("on-domain-xml") URL url) throws Exception {
        assertThat(get(url, "marker"), equalTo("on-domain-xml"));

        deployer.undeploy("on-domain-xml");
        controller.stop("with-domain-xml");
    }

    @Test
    @Order(7)
    public void startOnAnInstanceRootOfTheUsers() {
        controller.start("with-instance-root");
        deployer.deploy("on-instance-root");
    }

    /** On a copy, in the slot's directory, so test JVMs on other slots don't share it. */
    @Test
    @Order(8)
    public void servesOnACopyOfTheUsersInstanceRoot(
            @ArquillianResource @OperateOnDeployment("on-instance-root") URL url) throws Exception {
        assertThat(get(url, "marker"), equalTo("on-instance-root"));

        deployer.undeploy("on-instance-root");
        controller.stop("with-instance-root");
        try (Stream<Path> entries = Files.list(USER_CONFIG.resolve("instance"))) {
            assertThat(entries.map(entry -> entry.getFileName().toString()).collect(Collectors.toList()),
                    containsInAnyOrder("config", "marker.txt"));
        }
    }

    /** Containers that share GlassFish give themselves one slotGroup; one on another slot is refused. */
    @Test
    @Order(9)
    public void aSecondGlassFishOnAnotherSlotIsRefused() {
        controller.start("restartable");
        try {
            // Arquillian passes the container's LifecycleException on as it is.
            Exception e = assertThrows(Exception.class, () -> controller.start("with-domain-xml"));

            assertThat(messages(e), containsString("hosts one at a time"));
        } finally {
            controller.stop("restartable");
        }
    }

    private static String messages(Throwable e) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
