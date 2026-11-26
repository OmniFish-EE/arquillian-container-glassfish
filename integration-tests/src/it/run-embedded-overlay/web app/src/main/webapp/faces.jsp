<%-- The Faces JARs the application loads: the overlays, which replaced GlassFish's own. Mojarra 5's manifest has the
     wrong version, so each says which it is in its pom.properties. --%>
<%@ page import="java.io.InputStream, java.util.Properties" %><%
    for (String pom : new String[] { "org.glassfish/jakarta.faces", "org.glassfish.mojarra/mojarra",
            "jakarta.faces/jakarta.faces-api" }) {
        try (InputStream in = jakarta.faces.context.FacesContext.class.getResourceAsStream(
                "/META-INF/maven/" + pom + "/pom.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                out.println(pom + " " + properties.getProperty("version"));
            }
        }
    }
%>
