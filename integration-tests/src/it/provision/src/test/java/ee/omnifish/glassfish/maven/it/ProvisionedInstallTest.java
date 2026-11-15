/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;

/**
 * Checks what {@code glassfish:provision} left behind, as a test JVM sees it.
 * The pom configures no {@code systemPropertyVariables}, so
 * {@code glassfish.home} only arrives through the plugin's extension. Runs
 * under surefire; {@link ProvisionedInstallIT} repeats it under failsafe.
 */
public class ProvisionedInstallTest {

    @Test
    public void glassFishHomeIsForwarded() {
        String home = System.getProperty("glassfish.home");

        assertThat(home, notNullValue());
        assertThat(Path.of(home).isAbsolute(), is(true));
        // The parent pom's glassfish.home=glassfish7 (a directory name) is replaced.
        assertThat(Path.of(home).getParent().toString(), endsWith("dist"));
    }

    @Test
    public void distributionIsUnpacked() {
        assertThat(Files.isRegularFile(home().resolve("glassfish/bin/asadmin")), is(true));
    }

    @Test
    public void overlayLandsInModules() {
        assertThat(Files.isRegularFile(home().resolve("glassfish/modules/overlay-probe.jar")), is(true));
    }

    @Test
    public void trustedCertLandsInTheDomainTruststore() throws Exception {
        Path trustStore = home().resolve("glassfish/domains/domain1/config/cacerts.p12");

        KeyStore store = KeyStore.getInstance(trustStore.toFile(), "changeit".toCharArray());

        assertThat(store.containsAlias("provision-it"), is(true));
    }

    @Test
    public void postBootCommandsRanOnTheStagedDomain() throws Exception {
        assertThat(domainXml("//system-property[@name='provision.it.postboot']/@value"), equalTo("applied"));
    }

    @Test
    public void theBriefStartGaveTheDomainItsPortsBack() throws Exception {
        String port = "//config[@name='server-config']//network-listener[@name='%s']/@port";

        assertThat(domainXml(String.format(port, "admin-listener")), equalTo("4848"));
        assertThat(domainXml(String.format(port, "http-listener-1")), equalTo("8080"));
        // A post-boot command set this one.
        assertThat(domainXml(String.format(port, "http-listener-2")), equalTo("18181"));
    }

    /** What {@code xpath} selects in the staged domain's {@code domain.xml}. */
    private static String domainXml(String xpath) throws Exception {
        Path domainXml = home().resolve("glassfish/domains/domain1/config/domain.xml");
        return XPathFactory.newInstance().newXPath()
                .evaluate(xpath, DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(domainXml.toFile()));
    }

    private static Path home() {
        return Path.of(System.getProperty("glassfish.home"));
    }
}
