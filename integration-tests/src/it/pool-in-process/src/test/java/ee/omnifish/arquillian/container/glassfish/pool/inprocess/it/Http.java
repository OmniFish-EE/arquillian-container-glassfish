/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

final class Http {

    private Http() {
    }

    /** The page at {@code path} under {@code base}. */
    static String get(URL base, String path) throws IOException {
        return read(new URL(base, path).openConnection());
    }

    static String read(URLConnection connection) throws IOException {
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
