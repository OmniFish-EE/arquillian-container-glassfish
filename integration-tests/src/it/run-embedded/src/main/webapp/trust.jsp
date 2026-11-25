<%--
  Fetches https://localhost:<port>/ with the JVM's default trust: the reply, or
  the class of the exception, as an SSLHandshakeException for an untrusted server.
--%><%@ page import="java.io.InputStream, java.net.URL" %><%
    String reply;
    try (InputStream in = new URL("https://localhost:" + request.getParameter("port") + "/").openStream()) {
        reply = new String(in.readAllBytes());
    } catch (Exception e) {
        reply = e.getClass().getName();
    }
%><%= reply %>
