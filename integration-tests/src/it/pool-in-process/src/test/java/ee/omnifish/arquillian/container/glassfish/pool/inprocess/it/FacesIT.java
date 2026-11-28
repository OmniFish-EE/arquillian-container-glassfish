/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import static ee.omnifish.arquillian.container.glassfish.pool.inprocess.it.Http.get;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;

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

/**
 * Which Faces the application loads: with an overlay, the overlaid one, from
 * the staged install's modules, which the test JVM runs through the static
 * shell; without, the one in GlassFish Embedded's JAR. The project's own
 * Jakarta EE API, which failsafe puts before GlassFish, is left out, so it
 * masks neither.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
public class FacesIT {

    /** Where the Faces API comes from, and the version its JAR's first pom.properties gives. */
    private static final String FACES_JSP = "<%@ page import=\"java.io.File, java.util.Enumeration, "
            + "java.util.Properties, java.util.jar.JarEntry, java.util.jar.JarFile\" %><%\n"
            + "    java.net.URL location = jakarta.faces.context.FacesContext.class.getProtectionDomain()"
            + ".getCodeSource().getLocation();\n"
            + "    String version = \"?\";\n"
            + "    try (JarFile jar = new JarFile(new File(location.toURI()))) {\n"
            + "        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {\n"
            + "            JarEntry entry = entries.nextElement();\n"
            + "            if (entry.getName().startsWith(\"META-INF/maven/\") "
            + "&& entry.getName().endsWith(\"/pom.properties\")) {\n"
            + "                Properties pom = new Properties();\n"
            + "                pom.load(jar.getInputStream(entry));\n"
            + "                version = pom.getProperty(\"version\");\n"
            + "                break;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "%><%= location %> <%= version %>";

    @Deployment(testable = false)
    public static WebArchive deployment() {
        return ShrinkWrap.create(WebArchive.class, "faces.war").addAsWebResource(new StringAsset(FACES_JSP),
                "faces.jsp");
    }

    @Test
    public void theApplicationLoadsTheStagedFaces(@ArquillianResource URL base) throws Exception {
        String faces = get(base, "faces.jsp").trim();

        if (Boolean.getBoolean("pool.it.overlay")) {
            assertThat(faces, containsString("/glassfish/modules/jakarta.faces"));
            assertThat(faces, endsWith(" " + System.getProperty("pool.it.mojarra")));
        } else {
            assertThat(faces, containsString("glassfish-embedded-all-"));
        }
    }
}
