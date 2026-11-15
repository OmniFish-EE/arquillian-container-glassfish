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
package ee.omnifish.glassfish.maven.staging;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Certificates for {@link GlassFishStaging} to import into the staged
 * install's truststore, so GlassFish trusts them from its first start: an
 * external identity provider's server certificate for OpenID, or a client
 * certificate for {@code CLIENT-CERT}. GlassFish reads its truststore once,
 * when it first opens a TLS connection, so they can't be added to a running
 * server. Pool slots, cloned from the staged install, inherit them.
 */
public final class CertImport {

    /** The password of GlassFish's default truststore. */
    public static final String DEFAULT_PASSWORD = "changeit";

    /** No certificates. */
    public static final CertImport NONE = new CertImport(List.of(), null, DEFAULT_PASSWORD, false);

    private final List<TrustedCert> certs;
    private final Path trustStore;
    private final String password;
    private final boolean skip;

    /**
     * @param certs the certificates to import
     * @param trustStore the truststore they go into, or {@code null} for the
     *        staged domain's own (see {@link GlassFishStaging#stage})
     * @param password the truststore's password; {@code null} for {@link #DEFAULT_PASSWORD}
     * @param skip whether to import nothing
     */
    public CertImport(List<TrustedCert> certs, Path trustStore, String password, boolean skip) {
        this.certs = (certs == null) ? List.of() : Collections.unmodifiableList(certs);
        this.trustStore = trustStore;
        this.password = (password == null) ? DEFAULT_PASSWORD : password;
        this.skip = skip;
    }

    public List<TrustedCert> certs() {
        return certs;
    }

    public Path trustStore() {
        return trustStore;
    }

    public String password() {
        return password;
    }

    public boolean skip() {
        return skip;
    }

    /** True iff there is a certificate to import. */
    public boolean hasWork() {
        return !skip && !certs.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CertImport)) {
            return false;
        }
        CertImport other = (CertImport) o;
        return skip == other.skip
                && Objects.equals(certs, other.certs)
                && Objects.equals(trustStore, other.trustStore)
                && Objects.equals(password, other.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(certs, trustStore, password, skip);
    }
}
