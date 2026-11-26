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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The context roots of an EAR's web modules, as its
 * {@code META-INF/application.xml} gives them: a module without one, or an
 * EAR without that descriptor, has its file name without {@code .war}.
 * GlassFish Embedded's own listing of an application doesn't show them, and
 * its banner fails for EARs.
 */
public final class EarWebModules {

    private static final String APPLICATION_XML = "META-INF/application.xml";

    private EarWebModules() {
    }

    /** The context roots of {@code ear}'s web modules, each starting with {@code /}. */
    public static List<String> contextRoots(Path ear) throws IOException {
        List<String> contextRoots = new ArrayList<>();
        try (ZipFile zip = new ZipFile(ear.toFile())) {
            ZipEntry descriptor = zip.getEntry(APPLICATION_XML);
            if (descriptor == null) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    if (!entry.isDirectory() && entry.getName().endsWith(".war") && !entry.getName().contains("/")) {
                        contextRoots.add("/" + moduleName(entry.getName()));
                    }
                }
                return contextRoots;
            }
            try (InputStream in = zip.getInputStream(descriptor)) {
                NodeList webModules = parse(in).getElementsByTagNameNS("*", "web");
                for (int i = 0; i < webModules.getLength(); i++) {
                    Element web = (Element) webModules.item(i);
                    String contextRoot = text(web, "context-root");
                    String uri = text(web, "web-uri");
                    if (contextRoot == null && uri == null) {
                        continue;
                    }
                    String root = (contextRoot != null) ? contextRoot : moduleName(uri);
                    contextRoots.add(root.startsWith("/") ? root : "/" + root);
                }
            }
        }

        return contextRoots;
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
