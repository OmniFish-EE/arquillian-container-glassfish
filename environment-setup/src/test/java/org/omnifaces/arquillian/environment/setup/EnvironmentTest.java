/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.omnifaces.arquillian.environment.setup;

import static junit.framework.Assert.assertEquals;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *
 * @author lprimak
 */
@RunWith(Arquillian.class)
public class EnvironmentTest {
    @BeforeClass
    public static void before() {
        check();
    }

    @Before
    public void beforeEach() {
        check();
    }

    @Test
    public void environment() {
        check();
    }

    private static void check() {
        assertEquals("me", System.getenv("who"));
        assertEquals("here", System.getProperty("where"));
    }

    @Deployment
    static JavaArchive createDeployment() {
        JavaArchive jar = ShrinkWrap.create(JavaArchive.class).addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
        return jar;
    }
}
