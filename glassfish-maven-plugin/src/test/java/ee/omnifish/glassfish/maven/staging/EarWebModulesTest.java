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
package ee.omnifish.glassfish.maven.staging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EarWebModulesTest {

    @TempDir
    Path tmp;

    @Test
    void theDescriptorGivesTheContextRoots() throws IOException {
        Path ear = ear("META-INF/application.xml",
                "<application xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"10\">"
                + "<module><web><web-uri>alpha.war</web-uri><context-root>alpha-site</context-root></web></module>"
                + "<module><web><web-uri>web/beta.war</web-uri></web></module>"
                + "<module><ejb>bean.jar</ejb></module>"
                + "</application>",
                "alpha.war", "web/beta.war", "bean.jar");

        assertThat(EarWebModules.contextRoots(ear), contains("/alpha-site", "/beta"));
    }

    /** An old descriptor names its DTD, which isn't fetched. */
    @Test
    void anOldDescriptorIsReadWithoutItsDtd() throws IOException {
        Path ear = ear("META-INF/application.xml",
                "<!DOCTYPE application PUBLIC \"-//Sun Microsystems, Inc.//DTD J2EE Application 1.3//EN\" "
                + "\"http://java.sun.com/dtd/application_1_3.dtd\">"
                + "<application><module><web><web-uri>shop.war</web-uri><context-root>/shop</context-root></web>"
                + "</module></application>");

        assertThat(EarWebModules.contextRoots(ear), contains("/shop"));
    }

    @Test
    void withoutADescriptorEachWarInTheRootIsNamedAfterItsFile() throws IOException {
        Path ear = ear("alpha.war", "war", "lib/util.war", "bean.jar");

        assertThat(EarWebModules.contextRoots(ear), contains("/alpha"));
    }

    @Test
    void anEarWithoutWebModulesHasNoContextRoots() throws IOException {
        Path ear = ear("bean.jar", "jar");

        assertThat(EarWebModules.contextRoots(ear), empty());
    }

    /** An EAR with {@code first} holding {@code content}, and empty {@code others}. */
    private Path ear(String first, String content, String... others) throws IOException {
        Path ear = tmp.resolve("shop.ear");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(ear))) {
            out.putNextEntry(new ZipEntry(first));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (String other : others) {
                out.putNextEntry(new ZipEntry(other));
                out.closeEntry();
            }
        }
        return ear;
    }
}
