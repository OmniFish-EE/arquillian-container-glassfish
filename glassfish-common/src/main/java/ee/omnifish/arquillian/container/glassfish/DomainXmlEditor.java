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
package ee.omnifish.arquillian.container.glassfish;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
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
 * DOM-based editor for a GlassFish {@code domain.xml}: rewrites the
 * {@code port} attribute on the admin/http/https {@code <network-listener>}
 * elements, and upserts {@code <jvm-options>} children under each
 * {@code <java-config>}.
 *
 * <p>Writes go through a sibling tmp file + atomic move so the underlying
 * inode is replaced rather than truncated. That matters for the pool
 * container, which produces per-slot installs by hardlinking the source
 * install ({@code Files.createLink} per file); truncating in place would
 * also rewrite the source's {@code domain.xml}, the one that must stay
 * pristine for the next slot.
 */
public final class DomainXmlEditor {

    private static final Logger LOG = Logger.getLogger(DomainXmlEditor.class.getName());

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
    public static void setPorts(Path domainXml, int admin, int http, int https) throws IOException {
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
     * <p>Inputs are expected to be {@code key=value} (or bare {@code key}) without
     * the {@code -D} prefix; the editor prepends it. Empty/null inputs are no-ops.
     * Lines starting with {@code #} and blank lines have already been filtered
     * upstream (mirrors the {@code glassfish.systemProperties} parsing convention
     * in {@code CommonGlassFishConfiguration}).
     *
     * <p>Writes go through the same {@link #atomicWrite atomic-move} path as
     * {@link #setPorts}, so the source install's hardlinked {@code domain.xml}
     * stays intact.
     */
    public static void setSystemProperties(Path domainXml, List<String> properties) throws IOException {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        Document doc = parse(domainXml);
        boolean changed = false;
        NodeList javaConfigs = doc.getElementsByTagName("java-config");
        for (int i = 0; i < javaConfigs.getLength(); i++) {
            Element javaConfig = (Element) javaConfigs.item(i);
            for (String prop : properties) {
                if (upsertChild(javaConfig, systemPropertyPrefix(prop), "-D" + prop)) {
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
     * Upsert raw {@code <jvm-options>} entries into every {@code <java-config>}
     * block. Each map entry is {@code matchPrefix -> fullOption}:
     * <ul>
     *   <li>existing child whose text starts with {@code matchPrefix} and equals
     *       {@code fullOption}: left alone (idempotent)</li>
     *   <li>existing child whose text starts with {@code matchPrefix} but differs:
     *       textContent replaced with {@code fullOption}</li>
     *   <li>no existing child starting with {@code matchPrefix}: {@code fullOption}
     *       appended as a new child</li>
     * </ul>
     *
     * <p>Use this for options that don't follow the {@code -Dkey=value} convention,
     * e.g. {@code Map.of("-Xmx", "-Xmx512m", "-ea", "-ea")}. For system properties,
     * prefer {@link #setSystemProperties}.
     *
     * <p>Empty/null inputs are no-ops.
     */
    public static void setJvmOptions(Path domainXml, Map<String, String> options) throws IOException {
        if (options == null || options.isEmpty()) {
            return;
        }
        Document doc = parse(domainXml);
        boolean changed = false;
        NodeList javaConfigs = doc.getElementsByTagName("java-config");
        for (int i = 0; i < javaConfigs.getLength(); i++) {
            Element javaConfig = (Element) javaConfigs.item(i);
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (upsertChild(javaConfig, entry.getKey(), entry.getValue())) {
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
     * Match-prefix for a {@code -Dkey=value} option: {@code "-Dkey="} so the
     * value gets overwritten on update. For a bare {@code key} (no value), the
     * prefix is the full {@code "-Dkey"} so we only collide with that exact flag.
     */
    private static String systemPropertyPrefix(String keyValue) {
        int eq = keyValue.indexOf('=');
        return (eq < 0) ? "-D" + keyValue : "-D" + keyValue.substring(0, eq + 1);
    }

    /**
     * Insert or update one {@code <jvm-options>} child under a single
     * {@code <java-config>}, matching by {@code prefix}. Returns true iff the
     * document changed.
     */
    private static boolean upsertChild(Element javaConfig, String prefix, String desired) {
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
        // domain.xml is plain config — no DTDs, no external entities. Harden the
        // parser per the OWASP XXE cheatsheet so a tampered file can't trigger
        // entity expansion or external resource fetches.
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setValidating(false);
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            unsetAttributeIgnoringIAE(dbf, XMLConstants.ACCESS_EXTERNAL_DTD);
            unsetAttributeIgnoringIAE(dbf, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
            return dbf.newDocumentBuilder().parse(domainXml.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Could not parse " + domainXml, e);
        }
    }

    private static void unsetAttributeIgnoringIAE(DocumentBuilderFactory dbf, String name) {
        try {
            dbf.setAttribute(name, "");
        } catch (IllegalArgumentException e) {
            LOG.log(Level.FINE, () -> "Cannot unset attribute '" + name + "'; falling back to default");
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
