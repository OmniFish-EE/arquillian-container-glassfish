/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.it;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fetches the page at the {@code url} parameter from inside GlassFish, and
 * returns its body, or the exception. The connection uses GlassFish's default
 * TLS setup, so an HTTPS handshake succeeds only if the domain truststore
 * trusts the server's certificate.
 */
@WebServlet(urlPatterns = "/fetch")
public class HttpsClientServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        try (PrintWriter writer = resp.getWriter()) {
            try (InputStream in = new URL(req.getParameter("url")).openStream()) {
                writer.print(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                writer.print("failed: " + e);
            }
        }
    }
}
