/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.remote.it;

import jakarta.enterprise.context.ApplicationScoped;

/** The hello world application the tests deploy, and call inside the server. */
@ApplicationScoped
public class Greeter {

    public String greet(String name) {
        return "Hello, " + name;
    }
}
