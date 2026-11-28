<%-- Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved. --%>
<%--
  Fetches the page at the url parameter with the JVM's default TLS context, which GlassFish makes trust
  the pool's truststore: its body, or the exception.
--%><%@ page import="java.io.InputStream, java.net.URL" %><%
    String reply;
    try (InputStream in = new URL(request.getParameter("url")).openStream()) {
        reply = new String(in.readAllBytes());
    } catch (Exception e) {
        reply = "failed: " + e;
    }
%><%= reply %>
