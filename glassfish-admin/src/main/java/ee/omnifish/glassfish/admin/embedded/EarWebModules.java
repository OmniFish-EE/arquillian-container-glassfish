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
package ee.omnifish.glassfish.admin.embedded;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The web modules of an EAR, and their context roots, as its
 * {@code META-INF/application.xml} gives them: a module without one, or an
 * EAR without that descriptor, has its file name, without its directory and
 * {@code .war}. Its {@code list-applications} doesn't show them, and
 * GlassFish Embedded's banner fails for EARs.
 */
public final class EarWebModules {

    private static final String APPLICATION_XML = "META-INF/application.xml";

    private EarWebModules() {
    }

    /** The context roots of {@code ear}'s web modules, each starting with {@code /}. */
    public static List<String> contextRoots(Path ear) throws IOException {
        return new ArrayList<>(webModules(ear).values());
    }

    /**
     * {@code ear}'s web modules, by URI, as GlassFish takes them: from its
     * descriptor, or without one, every WAR in it, also one in a directory,
     * as Jakarta EE's rule for an EAR without a descriptor has it. Each has
     * the context root its descriptor gives, starting with {@code /}, or else
     * its file name, without its directory and {@code .war}.
     */
    public static Map<String, String> webModules(Path ear) throws IOException {
        Map<String, String> webModules = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(ear.toFile())) {
            ZipEntry descriptor = zip.getEntry(APPLICATION_XML);
            if (descriptor == null) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    if (!entry.isDirectory() && entry.getName().endsWith(".war")) {
                        webModules.put(entry.getName(), "/" + moduleName(entry.getName()));
                    }
                }
                return webModules;
            }
            try (InputStream in = zip.getInputStream(descriptor)) {
                NodeList webs = parse(in).getElementsByTagNameNS("*", "web");
                for (int i = 0; i < webs.getLength(); i++) {
                    Element web = (Element) webs.item(i);
                    String contextRoot = text(web, "context-root");
                    String uri = text(web, "web-uri");
                    if (uri == null) {
                        continue;
                    }
                    String root = (contextRoot != null) ? contextRoot : moduleName(uri);
                    webModules.put(uri, root.startsWith("/") ? root : "/" + root);
                }
            }
        }

        return webModules;
    }

    /**
     * The {@code <application-name>} of {@code ear}'s descriptor, which
     * GlassFish names the application after when it isn't given a name.
     */
    public static Optional<String> applicationName(Path ear) throws IOException {
        try (ZipFile zip = new ZipFile(ear.toFile())) {
            ZipEntry descriptor = zip.getEntry(APPLICATION_XML);
            if (descriptor == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(descriptor)) {
                Element application = parse(in);
                NodeList names = application.getElementsByTagNameNS("*", "application-name");
                for (int i = 0; i < names.getLength(); i++) {
                    // Only the application's own, not one a nested element might have.
                    if (names.item(i).getParentNode() == application) {
                        String name = names.item(i).getTextContent().trim();
                        return name.isEmpty() ? Optional.empty() : Optional.of(name);
                    }
                }
                return Optional.empty();
            }
        }
    }

    /** {@code uri}'s file name without {@code .war}. */
    private static String moduleName(String uri) {
        String name = uri.substring(uri.lastIndexOf('/') + 1);
        return name.endsWith(".war") ? name.substring(0, name.length() - ".war".length()) : name;
    }

    private static String text(Element parent, String name) {
        NodeList children = parent.getElementsByTagNameNS("*", name);
        if (children.getLength() == 0) {
            return null;
        }

        String text = children.item(0).getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    /** {@code in} as a namespace-aware document, without loading a DTD or external entities. */
    private static Element parse(InputStream in) throws IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setExpandEntityReferences(false);
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Older descriptors name a DTD, which isn't needed to read them.
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return dbf.newDocumentBuilder().parse(in).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException e) {
            throw new IOException("Could not read " + APPLICATION_XML + ": " + e.getMessage(), e);
        }
    }
}
