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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.Test;

/** What GlassFish leaves changed in the JVM that hosted it, put back. */
class JvmSnapshotTest {

    @Test
    void theSystemPropertiesAreRestored() {
        System.setProperty("jvm.snapshot.changed", "before");
        System.setProperty("jvm.snapshot.removed", "before");
        try {
            JvmSnapshot snapshot = JvmSnapshot.take();
            System.setProperty("jvm.snapshot.changed", "by GlassFish");
            System.clearProperty("jvm.snapshot.removed");
            System.setProperty("jvm.snapshot.added", "by GlassFish");

            snapshot.restore();

            assertThat(System.getProperty("jvm.snapshot.changed"), equalTo("before"));
            assertThat(System.getProperty("jvm.snapshot.removed"), equalTo("before"));
            assertThat(System.getProperty("jvm.snapshot.added"), nullValue());
        } finally {
            System.clearProperty("jvm.snapshot.changed");
            System.clearProperty("jvm.snapshot.removed");
            System.clearProperty("jvm.snapshot.added");
        }
    }

    /** GlassFish replaces them with its own, which may trust nothing, and Maven's HTTPS uses them after a run. */
    @Test
    void theTlsDefaultsAreRestored() throws Exception {
        SSLContext context = SSLContext.getDefault();
        SSLSocketFactory factory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        JvmSnapshot snapshot = JvmSnapshot.take();
        SSLContext glassfishs = SSLContext.getInstance("TLS");
        glassfishs.init(null, null, null);
        SSLContext.setDefault(glassfishs);
        HttpsURLConnection.setDefaultSSLSocketFactory(glassfishs.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);

        snapshot.restore();

        assertThat(SSLContext.getDefault(), sameInstance(context));
        assertThat(HttpsURLConnection.getDefaultSSLSocketFactory(), sameInstance(factory));
        assertThat(HttpsURLConnection.getDefaultHostnameVerifier(), sameInstance(verifier));
    }
}
