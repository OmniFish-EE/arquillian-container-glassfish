/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import static ee.omnifish.arquillian.container.glassfish.pool.inprocess.it.Http.get;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.net.URL;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ArquillianExtension.class)
@RunAsClient
public class HostPidIT {

    @Deployment(testable = false)
    public static WebArchive deployment() {
        return ShrinkWrap.create(WebArchive.class, "pid.war")
                .addAsWebResource(new StringAsset("<%= ProcessHandle.current().pid() %>"), "pid.jsp");
    }

    /** The test, on the client side, and the application run in one JVM. */
    @Test
    public void glassFishRunsInTheTestJvm(@ArquillianResource URL base) throws Exception {
        assertThat(get(base, "pid.jsp").trim(), equalTo(String.valueOf(ProcessHandle.current().pid())));
    }
}
