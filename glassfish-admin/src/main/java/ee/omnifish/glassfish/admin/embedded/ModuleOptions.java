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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which of the module options GlassFish Embedded needs a JVM lacks: the
 * {@code --add-opens} and {@code --add-exports} of
 * {@link EmbeddedGlassFish#moduleOptions}, which only the JVM's command line
 * can give, as a test JVM's {@code argLine} or Maven's
 * {@code .mvn/jvm.config}. Without them, GlassFish logs reflective-access
 * errors, and can't boot a second time in the same JVM.
 */
public final class ModuleOptions {

    private static final String ADD_OPENS = "--add-opens=";
    private static final String ADD_EXPORTS = "--add-exports=";
    private static final String ADD_MODULES = "--add-modules=";

    private ModuleOptions() {
    }

    /**
     * The options among {@code options} that aren't in effect for
     * {@code module}, such as the unnamed module of the class loader
     * GlassFish runs from. An option about a module the JVM doesn't have
     * counts as in effect, as there's nothing to open.
     */
    public static List<String> missing(List<String> options, Module module) {
        List<String> missing = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(ADD_OPENS) || option.startsWith(ADD_EXPORTS)) {
                boolean opens = option.startsWith(ADD_OPENS);
                String value = option.substring((opens ? ADD_OPENS : ADD_EXPORTS).length());
                int slash = value.indexOf('/');
                int equals = value.indexOf('=', slash + 1);
                if (slash < 0 || equals < 0) {
                    continue;
                }

                String pkg = value.substring(slash + 1, equals);
                Optional<Module> source = ModuleLayer.boot().findModule(value.substring(0, slash));
                if (source.isPresent() && !(opens ? source.get().isOpen(pkg, module)
                        : source.get().isExported(pkg, module))) {
                    missing.add(option);
                }
            } else if (option.startsWith(ADD_MODULES)) {
                for (String name : option.substring(ADD_MODULES.length()).split(",")) {
                    if (!name.isBlank() && ModuleLayer.boot().findModule(name.trim()).isEmpty()) {
                        missing.add(option);
                        break;
                    }
                }
            }
        }

        return missing;
    }
}
