/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Says the marker of its deployment, so WARs of the same name tell each other apart. */
@WebServlet(urlPatterns = "/marker")
public class MarkerServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try (InputStream marker = getServletContext().getResourceAsStream("/marker.txt")) {
            resp.getWriter().print(new String(marker.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
