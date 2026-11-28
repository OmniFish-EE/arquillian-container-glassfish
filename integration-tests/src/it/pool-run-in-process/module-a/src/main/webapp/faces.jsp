<%-- Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved. --%>
<%-- Where the Faces API comes from, and the version its JAR's first pom.properties gives. --%><%@ page import="java.io.File, java.util.Enumeration, java.util.Properties, java.util.jar.JarEntry, java.util.jar.JarFile" %><%
    java.net.URL location = jakarta.faces.context.FacesContext.class.getProtectionDomain().getCodeSource().getLocation();
    String version = "?";
    try (JarFile jar = new JarFile(new File(location.toURI()))) {
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                Properties pom = new Properties();
                pom.load(jar.getInputStream(entry));
                version = pom.getProperty("version");
                break;
            }
        }
    }
%><%= location %> <%= version %>
