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
package ee.omnifish.glassfish.admin.rest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options of a deployment, as those of {@code asadmin deploy}. What isn't set
 * isn't sent, so GlassFish's default applies: the name and context root then
 * come from the archive.
 */
public final class DeployOptions {

    private String name;
    private String contextRoot;
    private String libraries;
    private String properties;
    private String type;
    private boolean availabilityEnabled;
    private boolean force;

    /** The application's name. Set, it overrides the name in an EAR's {@code application.xml}. */
    public DeployOptions name(String name) {
        this.name = name;
        return this;
    }

    public String name() {
        return name;
    }

    /** The context root of a WAR. */
    public DeployOptions contextRoot(String contextRoot) {
        this.contextRoot = contextRoot;
        return this;
    }

    /** Library jars, comma separated, as {@code asadmin deploy --libraries}. */
    public DeployOptions libraries(String libraries) {
        this.libraries = libraries;
        return this;
    }

    /** Properties, colon separated, as {@code asadmin deploy --properties}. */
    public DeployOptions properties(String properties) {
        this.properties = properties;
        return this;
    }

    /** The archive's type, such as {@code osgi}, when GlassFish can't tell it. */
    public DeployOptions type(String type) {
        this.type = type;
        return this;
    }

    /** Whether high availability is enabled for it, as {@code asadmin deploy --availabilityenabled}. */
    public DeployOptions availabilityEnabled(boolean availabilityEnabled) {
        this.availabilityEnabled = availabilityEnabled;
        return this;
    }

    /** Whether it replaces an application deployed with the same name, as {@code asadmin deploy --force}. */
    public DeployOptions force(boolean force) {
        this.force = force;
        return this;
    }

    /** The form fields of the options that are set, by the names GlassFish's deploy command takes. */
    Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfSet(fields, "name", name);
        putIfSet(fields, "contextroot", contextRoot);
        putIfSet(fields, "libraries", libraries);
        putIfSet(fields, "properties", properties);
        putIfSet(fields, "type", type);
        if (availabilityEnabled) {
            fields.put("availabilityenabled", "true");
        }
        if (force) {
            fields.put("force", "true");
        }

        return fields;
    }

    private static void putIfSet(Map<String, String> fields, String field, String value) {
        if (value != null) {
            fields.put(field, value);
        }
    }
}
