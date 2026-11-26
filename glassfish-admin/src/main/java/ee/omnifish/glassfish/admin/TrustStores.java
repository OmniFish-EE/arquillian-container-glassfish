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
package ee.omnifish.glassfish.admin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.UUID;

/**
 * Truststores, as GlassFish reads them: it opens its truststore with its
 * master password, whatever {@code javax.net.ssl.trustStorePassword} says, so
 * a copy for GlassFish has {@link #MASTER_PASSWORD}.
 */
public final class TrustStores {

    /** GlassFish's master password, which opens the truststores of its domains. */
    public static final String MASTER_PASSWORD = "changeit";

    private TrustStores() {
    }

    /**
     * Write the certificates of the truststore {@code source}, which
     * {@code sourcePassword} opens, to {@code target}, as PKCS12 with
     * {@code targetPassword}. The JDK probes the source's type from the file.
     * Keys, which trust doesn't use, are left out.
     */
    public static void copyCertificates(Path source, char[] sourcePassword, Path target, char[] targetPassword)
            throws IOException {
        KeyStore store = load(source, sourcePassword);
        try {
            KeyStore copy = KeyStore.getInstance("PKCS12");
            copy.load(null, null);
            for (String alias : Collections.list(store.aliases())) {
                if (store.isCertificateEntry(alias)) {
                    copy.setCertificateEntry(alias, store.getCertificate(alias));
                }
            }
            storeAtomically(copy, target, targetPassword);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not copy the certificates of " + source + ": " + e.getMessage(), e);
        }
    }

    /** The truststore {@code trustStore}, which {@code password} opens; the JDK probes its type from the file. */
    public static KeyStore load(Path trustStore, char[] password) throws IOException {
        if (!Files.isRegularFile(trustStore)) {
            throw new IOException("Truststore " + trustStore + " doesn't exist");
        }
        try {
            return KeyStore.getInstance(trustStore.toFile(), password);
        } catch (IOException | GeneralSecurityException e) {
            throw new IOException("Could not load truststore " + trustStore + ": " + e.getMessage(), e);
        }
    }

    /**
     * Write {@code store} to a sibling of {@code trustStore}, then move it
     * into place, so whoever reads it meanwhile gets the old truststore or
     * the new one, and an interrupted write can't leave half of it behind.
     */
    public static void storeAtomically(KeyStore store, Path trustStore, char[] password) throws IOException {
        // Of its own, so that writers don't share it.
        Path tmp = trustStore.resolveSibling(trustStore.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                store.store(out, password);
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not write truststore " + trustStore + ": " + e.getMessage(), e);
            }
            try {
                Files.move(tmp, trustStore, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Still replaces the file rather than rewriting it in place, just not crash-safe.
                Files.move(tmp, trustStore, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
