/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import java.net.URL;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Runs in a test JVM of its own, at the same time as ForkOneIT; see {@link ForkBarrier}. */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
public class ForkTwoIT {

    @Deployment(testable = false)
    public static WebArchive deployment() {
        return Archives.shop("fork-two");
    }

    @Test
    public void bothGlassFishesRunAtOnce(@ArquillianResource URL base) throws Exception {
        ForkBarrier.reachEachOther("fork-two", "fork-one", base);
    }
}
