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
package ee.omnifish.glassfish.admin.embedded;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Properties;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * What GlassFish Embedded changes in the JVM that hosts it, and leaves
 * changed after its shutdown: the system properties, of which it sets some
 * thirty, {@code javax.net.ssl.trustStore} among them, and the default TLS
 * context and socket factory, which it replaces with its own. Taken before
 * GlassFish boots, and restored after its shutdown, so the host finds the
 * JVM as it was: Maven, which may make HTTPS requests of its own after a
 * run, or a test JVM, which may boot GlassFish again.
 */
final class JvmSnapshot {

    private final Properties properties;
    private final SSLContext sslContext;
    private final SSLSocketFactory socketFactory;
    private final HostnameVerifier hostnameVerifier;

    private JvmSnapshot(Properties properties, SSLContext sslContext, SSLSocketFactory socketFactory,
            HostnameVerifier hostnameVerifier) {
        this.properties = properties;
        this.sslContext = sslContext;
        this.socketFactory = socketFactory;
        this.hostnameVerifier = hostnameVerifier;
    }

    static JvmSnapshot take() {
        Properties properties = new Properties();
        properties.putAll(System.getProperties());
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            // A JVM without TLS has no default to restore.
            sslContext = null;
        }

        return new JvmSnapshot(properties, sslContext, HttpsURLConnection.getDefaultSSLSocketFactory(),
                HttpsURLConnection.getDefaultHostnameVerifier());
    }

    /** Put the system properties and the TLS defaults back as they were when this was taken. */
    void restore() {
        Properties current = System.getProperties();
        for (Object key : new ArrayList<>(current.keySet())) {
            if (!properties.containsKey(key)) {
                current.remove(key);
            }
        }
        properties.forEach((key, value) -> {
            if (!value.equals(current.get(key))) {
                current.put(key, value);
            }
        });
        if (sslContext != null) {
            SSLContext.setDefault(sslContext);
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory);
        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier);
    }
}
