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
package ee.omnifish.arquillian.container.glassfish.pool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * DOM-based editor for the per-slot {@code domain.xml}: rewrites the
 * {@code port} attribute on the admin/http/https {@code <network-listener>}
 * elements.
 *
 * <p>Writes go through a sibling tmp file + atomic move so the underlying
 * inode is replaced rather than truncated. That matters because pool slots
 * are produced by hardlinking the source install ({@code Files.createLink}
 * per file); truncating in place would also rewrite the source's
 * {@code domain.xml}, the one that must stay pristine for the next slot.
 */
final class DomainXmlEditor {

    private DomainXmlEditor() {
    }

    /**
     * Set the admin/http/https ports on the given {@code domain.xml}. Listeners
     * whose port is already correct or set to a {@code ${...}} placeholder are
     * left alone.
     *
     * @param domainXml path to {@code config/domain.xml}
     * @param admin admin-listener port
     * @param http http-listener-1 port
     * @param https http-listener-2 port
     * @throws IOException if the file can't be parsed, rewritten, or moved into place
     */
    static void setPorts(Path domainXml, int admin, int http, int https) throws IOException {
        Map<String, Integer> wanted = Map.of(
                "admin-listener", admin,
                "http-listener-1", http,
                "http-listener-2", https);

        Document doc = parse(domainXml);
        boolean changed = false;
        NodeList listeners = doc.getElementsByTagName("network-listener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            Integer target = wanted.get(listener.getAttribute("name"));
            if (target == null) {
                continue;
            }
            String current = listener.getAttribute("port");
            if (current.startsWith("${") || current.equals(target.toString())) {
                continue;
            }
            listener.setAttribute("port", target.toString());
            changed = true;
        }
        if (!changed) {
            return;
        }
        atomicWrite(domainXml, doc);
    }

    /**
     * Upsert {@code -D<key>=<value>} entries into every {@code <java-config>}
     * block. Each {@code key=value} input becomes/overwrites a
     * {@code <jvm-options>-Dkey=value</jvm-options>} child of {@code <java-config>}:
     * <ul>
     *   <li>existing {@code -Dkey=…} child with same value: left alone (idempotent)</li>
     *   <li>existing {@code -Dkey=…} child with different value: textContent replaced</li>
     *   <li>no existing {@code -Dkey=…}: appended as a new child</li>
     * </ul>
     *
     * <p>Empty/null inputs are no-ops. Lines starting with {@code #} and blank
     * lines have already been filtered upstream (mirrors managed's
     * {@code glassfish.systemProperties} parsing convention).
     *
     * <p>Writes go through the same {@link #atomicWrite atomic-move} path as
     * {@link #setPorts}, so the source install's hardlinked {@code domain.xml}
     * stays intact.
     */
    static void setJvmOptions(Path domainXml, List<String> properties) throws IOException {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        Document doc = parse(domainXml);
        boolean changed = false;
        NodeList javaConfigs = doc.getElementsByTagName("java-config");
        for (int i = 0; i < javaConfigs.getLength(); i++) {
            Element javaConfig = (Element) javaConfigs.item(i);
            for (String prop : properties) {
                if (upsertJvmOption(javaConfig, prop)) {
                    changed = true;
                }
            }
        }
        if (!changed) {
            return;
        }
        atomicWrite(domainXml, doc);
    }

    /**
     * Insert or update one {@code -Dkey=value} entry under a single
     * {@code <java-config>}. Returns true iff the document changed.
     */
    private static boolean upsertJvmOption(Element javaConfig, String keyValue) {
        String desired = "-D" + keyValue;
        // Bare key (no value) becomes a flag; match it exactly.
        // key=value matches by prefix so a stale value gets overwritten.
        int eq = keyValue.indexOf('=');
        String prefix = (eq < 0) ? desired : "-D" + keyValue.substring(0, eq + 1);
        for (Element existing : directChildren(javaConfig, "jvm-options")) {
            String text = existing.getTextContent();
            if (text != null && text.startsWith(prefix)) {
                if (desired.equals(text)) {
                    return false;
                }
                existing.setTextContent(desired);
                return true;
            }
        }
        Element option = javaConfig.getOwnerDocument().createElement("jvm-options");
        option.setTextContent(desired);
        javaConfig.appendChild(option);
        return true;
    }

    /**
     * Direct-child elements with the given tag name. {@code Element.getElementsByTagName}
     * is recursive; this helper restricts the scan so two {@code <java-config>}
     * blocks (one per profile) don't see each other's options.
     */
    private static List<Element> directChildren(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private static Document parse(Path domainXml) throws IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // domain.xml has no namespaces and pulls no external entities; harden
        // the parser so a tampered file can't trigger XXE or DOCTYPE expansion.
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            return dbf.newDocumentBuilder().parse(domainXml.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Could not parse " + domainXml, e);
        }
    }

    private static void atomicWrite(Path target, Document doc) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.transform(new DOMSource(doc), new StreamResult(tmp.toFile()));
        } catch (TransformerException e) {
            throw new IOException("Could not serialize " + target, e);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // ATOMIC_MOVE is unsupported on some filesystems (e.g. cross-FS, exotic
            // network mounts). Fall back to a non-atomic replace — still inode-
            // replacing on POSIX, just not guaranteed crash-safe.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
