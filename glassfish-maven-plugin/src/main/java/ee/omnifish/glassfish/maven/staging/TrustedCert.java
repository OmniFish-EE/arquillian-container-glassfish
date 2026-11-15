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

import java.io.File;
import java.util.Objects;

/**
 * A certificate GlassFish should trust: a local PEM or DER file, and the
 * alias it is stored under in the staged install's truststore.
 *
 * <p>JavaBean for Plexus injection (nested {@code <trustedCert>} blocks).
 */
public class TrustedCert {

    private String alias;
    private File file;

    public TrustedCert() {
    }

    public TrustedCert(String alias, File file) {
        this.alias = alias;
        this.file = file;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TrustedCert)) {
            return false;
        }
        TrustedCert other = (TrustedCert) o;
        return Objects.equals(alias, other.alias) && Objects.equals(file, other.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, file);
    }

    @Override
    public String toString() {
        return alias + "=" + file;
    }
}
