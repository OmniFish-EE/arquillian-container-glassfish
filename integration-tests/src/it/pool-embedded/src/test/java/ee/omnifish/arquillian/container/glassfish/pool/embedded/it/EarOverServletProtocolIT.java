/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.embedded.it;

import static ee.omnifish.arquillian.container.glassfish.pool.embedded.it.Http.get;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.Testable;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * An EAR with two WARs, the one the test runs in in a directory, over
 * Arquillian's Servlet protocol: the child lists each web module's servlets,
 * at the context root GlassFish gave it, so the protocol finds its runner in
 * the WAR the test runs in. Without a descriptor, every WAR in an EAR is a
 * web module, also one in a directory, at its file name. Arquillian adds
 * the test class to a WAR or a JAR, not to an EAR, so the WAR to test has it
 * already. It runs with JUnit's own assertions, which the deployment has,
 * unlike Hamcrest.
 */
@ExtendWith(ArquillianExtension.class)
public class EarOverServletProtocolIT {

    @Deployment
    public static EnterpriseArchive deployment() {
        WebArchive alpha = ShrinkWrap.create(WebArchive.class, "alpha.war")
                .addClasses(HelloServlet.class, Http.class, EarOverServletProtocolIT.class);
        WebArchive beta = ShrinkWrap.create(WebArchive.class, "beta.war").addClass(HelloServlet.class);
        return ShrinkWrap.create(EnterpriseArchive.class, "pair.ear")
                .add(Testable.archiveToTest(alpha), "some", ZipExporter.class)
                .addAsModule(beta);
    }

    /** In the child, which has the slot setup's system property. */
    @Test
    public void theTestRunsInTheChild() {
        assertEquals("present", System.getProperty("pool.it.sysprop"));
    }

    @Test
    @RunAsClient
    public void bothWebModulesAnswerAtTheirContextRoots(@ArquillianResource URL base) throws Exception {
        URL server = new URL(base, "/");

        assertEquals("hello from /alpha", get(server, "alpha/hello"));
        assertEquals("hello from /beta", get(server, "beta/hello"));
    }
}
