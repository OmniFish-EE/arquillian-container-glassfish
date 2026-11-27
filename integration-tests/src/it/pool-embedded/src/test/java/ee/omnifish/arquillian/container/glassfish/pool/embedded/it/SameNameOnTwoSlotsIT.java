/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.embedded.it;

import static ee.omnifish.arquillian.container.glassfish.pool.embedded.it.Http.get;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import java.net.URL;

import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Two containers in one test JVM lease two slots, and deploy a WAR of the
 * same name to each, as a child of its own on its slot's ports. Each URL
 * reaches its own application; ending the first leaves the second serving,
 * and a third deployment serves on the first one's port. Arquillian takes no
 * two archives of the same name for one container, so the third has a name
 * of its own.
 */
@ExtendWith(ArquillianExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@RunAsClient
public class SameNameOnTwoSlotsIT {

    private static int firstPort;

    @ArquillianResource
    private ContainerController controller;

    @ArquillianResource
    private Deployer deployer;

    @Deployment(name = "first-shop", managed = false, testable = false)
    @TargetsContainer("first")
    public static WebArchive firstShop() {
        return Archives.shop("first");
    }

    @Deployment(name = "second-shop", managed = false, testable = false)
    @TargetsContainer("second")
    public static WebArchive secondShop() {
        return Archives.shop("second");
    }

    @Deployment(name = "third-shop", managed = false, testable = false)
    @TargetsContainer("first")
    public static WebArchive thirdShop() {
        return Archives.marked("third.war", "third");
    }

    @Test
    @Order(1)
    public void twoContainersDeployTheSameNameOnTwoSlots() {
        controller.start("second");
        deployer.deploy("first-shop");
        deployer.deploy("second-shop");
    }

    @Test
    @Order(2)
    public void eachUrlReachesItsOwnApplication(@ArquillianResource @OperateOnDeployment("first-shop") URL first,
            @ArquillianResource @OperateOnDeployment("second-shop") URL second) throws Exception {
        assertThat(get(first, "marker"), equalTo("first"));
        assertThat(get(second, "marker"), equalTo("second"));
        assertThat(first.getPort(), not(equalTo(second.getPort())));
        firstPort = first.getPort();
    }

    @Test
    @Order(3)
    public void endingTheFirstLeavesTheSecondServing(
            @ArquillianResource @OperateOnDeployment("second-shop") URL second) throws Exception {
        deployer.undeploy("first-shop");

        assertThat(get(second, "marker"), equalTo("second"));
    }

    @Test
    @Order(4)
    public void aThirdDeploymentStartsOnTheFirstsSlot() {
        deployer.deploy("third-shop");
    }

    @Test
    @Order(5)
    public void theThirdServesOnTheFirstsPort(@ArquillianResource @OperateOnDeployment("third-shop") URL third)
            throws Exception {
        assertThat(third.getPort(), equalTo(firstPort));
        assertThat(get(third, "marker"), equalTo("third"));

        deployer.undeploy("third-shop");
        deployer.undeploy("second-shop");
        controller.stop("second");
    }
}
