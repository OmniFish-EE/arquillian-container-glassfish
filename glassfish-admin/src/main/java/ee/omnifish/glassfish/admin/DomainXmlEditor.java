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
package ee.omnifish.glassfish.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * {@code port} attribute on the server's admin/http/https
 * {@code <network-listener>} elements, and upserts {@code <jvm-options>}
 * children under each {@code <java-config>}.
 *
 * <p>Writes go through a sibling tmp file + atomic move so the underlying
 * inode is replaced rather than truncated, and an interrupted write can't
 * leave a half-written {@code domain.xml} behind.
 */
public final class DomainXmlEditor {

    /** The listener the admin port belongs to. */
    public static final String ADMIN_LISTENER = "admin-listener";

    /** The listener the http port belongs to. */
    public static final String HTTP_LISTENER = "http-listener-1";

    /** The listener the https port belongs to. */
    public static final String HTTPS_LISTENER = "http-listener-2";

    private static final List<String> LISTENERS = List.of(ADMIN_LISTENER, HTTP_LISTENER, HTTPS_LISTENER);

    /** The config the DAS runs with when its {@code <server>} element doesn't say. */
    private static final String DEFAULT_SERVER_CONFIG = "server-config";

    private static final String DEBUG_OPTIONS = "debug-options";

    /** JDWP's options in a stock {@code domain.xml}, up to the address. */
    private static final String DEFAULT_DEBUG_OPTIONS = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=";

    /** The {@code address} option among the debug options, in group 2. */
    private static final Pattern DEBUG_ADDRESS = Pattern.compile("(^|[=,:])address=([^,\\s]*)");

    private static final Logger LOG = Logger.getLogger(DomainXmlEditor.class.getName());

    private DomainXmlEditor() {
    }

    /**
     * Set the admin/http/https ports of the server on the given
     * {@code domain.xml}. Only the config the {@code server} element's
     * {@code config-ref} names (normally {@code server-config}) is edited, so
     * templates such as {@code default-config} stay as they are. A
     * {@code ${...}} placeholder in the server's config is replaced too.
     *
     * @param domainXml path to {@code config/domain.xml}
     * @param admin admin-listener port
     * @param http http-listener-1 port
     * @param https http-listener-2 port
     * @throws IOException if the file can't be parsed, rewritten, or moved into place
     */
    public static void setPorts(Path domainXml, int admin, int http, int https) throws IOException {
        setPorts(domainXml, Map.of(
                ADMIN_LISTENER, String.valueOf(admin),
                HTTP_LISTENER, String.valueOf(http),
                HTTPS_LISTENER, String.valueOf(https)));
    }

    /**
     * Set the ports of the server's listeners, keyed by listener name, as
     * given, so a port read with {@link #ports} can be put back, placeholder
     * and all. Listeners {@code ports} doesn't name are left alone, and so are
     * all configs but the server's, as with {@link #setPorts(Path, int, int, int)}.
     */
    public static void setPorts(Path domainXml, Map<String, String> ports) throws IOException {
        Document doc = parse(domainXml);
        boolean changed = false;
        for (Element listener : serverListeners(doc, domainXml)) {
            String wanted = ports.get(listener.getAttribute("name"));
            if (wanted == null || wanted.equals(listener.getAttribute("port"))) {
                continue;
            }
            listener.setAttribute("port", wanted);
            changed = true;
        }
        if (!changed) {
            return;
        }
        atomicWrite(domainXml, doc);
    }

    /**
     * Set the ports of the server's three IIOP listeners, {@code orb-listener-1},
     * {@code SSL} and {@code SSL_MUTUALAUTH}, and of its JMS host,
     * {@code default_JMS_host}, which an application's remote EJBs and JMS
     * open. Only the server's config is edited, and what it doesn't have is
     * left out.
     */
    public static void setIiopAndJmsPorts(Path domainXml, int iiop, int iiopSsl, int iiopMutualAuth, int jms)
            throws IOException {
        Document doc = parse(domainXml);
        Element config = serverConfig(doc, domainXml);
        Map<String, Integer> iiopPorts = Map.of("orb-listener-1", iiop, "SSL", iiopSsl, "SSL_MUTUALAUTH",
                iiopMutualAuth);
        NodeList listeners = config.getElementsByTagName("iiop-listener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            Integer port = iiopPorts.get(listener.getAttribute("id"));
            if (port != null) {
                listener.setAttribute("port", String.valueOf(port));
            }
        }
        NodeList jmsHosts = config.getElementsByTagName("jms-host");
        for (int i = 0; i < jmsHosts.getLength(); i++) {
            Element jmsHost = (Element) jmsHosts.item(i);
            if ("default_JMS_host".equals(jmsHost.getAttribute("name"))) {
                jmsHost.setAttribute("port", String.valueOf(jms));
            }
        }
        atomicWrite(domainXml, doc);
    }

    /**
     * The ports of the server's admin, http and https listeners, keyed by
     * listener name, as written: a {@code ${...}} placeholder stays one.
     */
    public static Map<String, String> ports(Path domainXml) throws IOException {
        Map<String, String> ports = new LinkedHashMap<>();
        for (Element listener : serverListeners(parse(domainXml), domainXml)) {
            String name = listener.getAttribute("name");
            if (LISTENERS.contains(name)) {
                ports.put(name, listener.getAttribute("port"));
            }
        }

        return ports;
    }

    /**
     * {@code value}, or the value of the system property its {@code ${name}}
     * stands for, as the DAS resolves it: the server's own
     * {@code <system-property>} first, then its config's, then the domain's.
     * A value that isn't one placeholder, or one no system property defines,
     * comes back as it is.
     */
    public static String resolve(Path domainXml, String value) throws IOException {
        if (value == null || !value.matches("\\$\\{[^}]+}")) {
            return value;
        }

        return resolve(parse(domainXml), value);
    }

    /**
     * Where the server's debugger listens when it starts with
     * {@code --debug}: the {@code address} in the {@code debug-options} of
     * its config's {@code <java-config>}, such as {@code *:9009}, with a
     * {@code ${...}} placeholder for the port resolved as {@link #resolve}
     * does. {@code null} when the options have no address.
     */
    public static String debugAddress(Path domainXml) throws IOException {
        Document doc = parse(domainXml);
        Element javaConfig = javaConfig(doc, domainXml);
        Matcher address = DEBUG_ADDRESS.matcher(javaConfig == null ? "" : javaConfig.getAttribute(DEBUG_OPTIONS));
        if (!address.find()) {
            return null;
        }

        String value = address.group(2);
        int colon = value.lastIndexOf(':');
        return value.substring(0, colon + 1) + resolve(doc, value.substring(colon + 1));
    }

    /**
     * Set the {@code address} in the {@code debug-options} of the server's
     * config, so its debugger listens there when it starts with
     * {@code --debug}: in place of the address there, after the options
     * there, or with JDWP's usual options when there are none. Other configs
     * stay as they are, as with {@link #setPorts(Path, int, int, int)}.
     *
     * @throws IOException also when the server's config has no {@code <java-config>}
     */
    public static void setDebugAddress(Path domainXml, String address) throws IOException {
        Document doc = parse(domainXml);
        Element javaConfig = javaConfig(doc, domainXml);
        if (javaConfig == null) {
            throw new IOException(domainXml + " has no <java-config> for the server");
        }

        String options = javaConfig.getAttribute(DEBUG_OPTIONS);
        Matcher current = DEBUG_ADDRESS.matcher(options);
        String wanted;
        if (current.find()) {
            wanted = options.substring(0, current.start(2)) + address + options.substring(current.end(2));
        } else if (options.isBlank()) {
            wanted = DEFAULT_DEBUG_OPTIONS + address;
        } else {
            wanted = options + ",address=" + address;
        }
        if (wanted.equals(options)) {
            return;
        }

        javaConfig.setAttribute(DEBUG_OPTIONS, wanted);
        atomicWrite(domainXml, doc);
    }

    /** {@code value}'s system property, as {@link #resolve(Path, String)} looks it up, or {@code value}. */
    private static String resolve(Document doc, String value) {
        if (!value.matches("\\$\\{[^}]+}")) {
            return value;
        }

        String name = value.substring(2, value.length() - 1);
        for (Element scope : dasScopes(doc)) {
            for (Element property : directChildren(scope, "system-property")) {
                if (name.equals(property.getAttribute("name"))) {
                    return property.getAttribute("value");
                }
            }
        }

        return value;
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
     * {@link #setPorts}.
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
     * The {@code <jvm-options>} for a maximum heap size and enabled assertions,
     * keyed as {@link #setJvmOptions} takes them: {@code -Xmx<maxHeapSize>}, and
     * {@code -ea<enableAssertions>}, where {@code enableAssertions} is what
     * follows {@code -ea}, e.g. {@code :org.jboss.cdi.tck...}. A {@code null}
     * value leaves its option out.
     */
    public static Map<String, String> heapAndAssertionOptions(String maxHeapSize, String enableAssertions) {
        Map<String, String> options = new LinkedHashMap<>();
        if (maxHeapSize != null) {
            options.put("-Xmx", "-Xmx" + maxHeapSize);
        }
        if (enableAssertions != null) {
            options.put("-ea", "-ea" + enableAssertions);
        }

        return options;
    }

    /** Every {@code <jvm-options>} value in {@code domainXml}, from all of its configs. */
    public static List<String> jvmOptions(Path domainXml) throws IOException {
        NodeList options = parse(domainXml).getElementsByTagName("jvm-options");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < options.getLength(); i++) {
            values.add(options.item(i).getTextContent());
        }

        return values;
    }

    /**
     * The {@code <network-listener>}s of the config the DAS runs with: the one
     * its {@code <server name="server">} element's {@code config-ref} names,
     * else {@code server-config}.
     */
    /** The DAS's {@code <server>}, its {@code <config>} and the {@code <domain>}, in that order, where present. */
    private static List<Element> dasScopes(Document doc) {
        List<Element> scopes = new ArrayList<>();
        String configRef = DEFAULT_SERVER_CONFIG;
        NodeList servers = doc.getElementsByTagName("server");
        for (int i = 0; i < servers.getLength(); i++) {
            Element server = (Element) servers.item(i);
            if ("server".equals(server.getAttribute("name"))) {
                scopes.add(server);
                if (server.hasAttribute("config-ref")) {
                    configRef = server.getAttribute("config-ref");
                }
            }
        }
        NodeList configs = doc.getElementsByTagName("config");
        for (int i = 0; i < configs.getLength(); i++) {
            Element config = (Element) configs.item(i);
            if (configRef.equals(config.getAttribute("name"))) {
                scopes.add(config);
            }
        }
        scopes.add(doc.getDocumentElement());

        return scopes;
    }

    private static List<Element> serverListeners(Document doc, Path domainXml) throws IOException {
        List<Element> listeners = new ArrayList<>();
        NodeList found = serverConfig(doc, domainXml).getElementsByTagName("network-listener");
        for (int i = 0; i < found.getLength(); i++) {
            listeners.add((Element) found.item(i));
        }

        return listeners;
    }

    /** The {@code <java-config>} of the config the DAS runs with, or null. */
    private static Element javaConfig(Document doc, Path domainXml) throws IOException {
        List<Element> javaConfigs = directChildren(serverConfig(doc, domainXml), "java-config");
        return javaConfigs.isEmpty() ? null : javaConfigs.get(0);
    }

    /** The config the DAS runs with: the one its {@code config-ref} names, else {@code server-config}. */
    private static Element serverConfig(Document doc, Path domainXml) throws IOException {
        String configRef = DEFAULT_SERVER_CONFIG;
        NodeList servers = doc.getElementsByTagName("server");
        for (int i = 0; i < servers.getLength(); i++) {
            Element server = (Element) servers.item(i);
            if ("server".equals(server.getAttribute("name")) && server.hasAttribute("config-ref")) {
                configRef = server.getAttribute("config-ref");
            }
        }

        NodeList configs = doc.getElementsByTagName("config");
        for (int i = 0; i < configs.getLength(); i++) {
            Element config = (Element) configs.item(i);
            if (configRef.equals(config.getAttribute("name"))) {
                return config;
            }
        }
        throw new IOException(domainXml + " has no <config name=\"" + configRef + "\">");
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
