/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import static ee.omnifish.arquillian.container.glassfish.pool.inprocess.it.Http.get;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.FileNotFoundException;
import java.net.URL;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Two archives on the one GlassFish the test JVM hosts, which deploys to it
 * while it runs: undeploying one leaves the other serving, on the port the
 * test JVM leased. From the command line, a slot runs one archive at a time.
 */
@ExtendWith(ArquillianExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@RunAsClient
public class TwoArchivesIT {

    private static URL alpha;

    @ArquillianResource
    private Deployer deployer;

    @Deployment(name = "alpha", managed = false, testable = false)
    public static WebArchive alpha() {
        return Archives.marked("alpha.war", "alpha");
    }

    @Deployment(name = "beta", managed = false, testable = false)
    public static WebArchive beta() {
        return Archives.marked("beta.war", "beta");
    }

    @Test
    @Order(1)
    public void bothDeployToTheOneGlassFish() {
        deployer.deploy("alpha");
        deployer.deploy("beta");
    }

    @Test
    @Order(2)
    public void bothServeOnTheLeasedPort(@ArquillianResource @OperateOnDeployment("alpha") URL alphaUrl,
            @ArquillianResource @OperateOnDeployment("beta") URL betaUrl) throws Exception {
        assertThat(get(alphaUrl, "marker"), equalTo("alpha"));
        assertThat(get(betaUrl, "marker"), equalTo("beta"));
        assertThat(alphaUrl.getPort(), equalTo(betaUrl.getPort()));
        alpha = alphaUrl;
    }

    @Test
    @Order(3)
    public void undeployingOneLeavesTheOtherServing(@ArquillianResource @OperateOnDeployment("beta") URL betaUrl)
            throws Exception {
        deployer.undeploy("alpha");

        assertThat(get(betaUrl, "marker"), equalTo("beta"));
        assertThrows(FileNotFoundException.class, () -> get(alpha, "marker"));
        deployer.undeploy("beta");
    }
}
