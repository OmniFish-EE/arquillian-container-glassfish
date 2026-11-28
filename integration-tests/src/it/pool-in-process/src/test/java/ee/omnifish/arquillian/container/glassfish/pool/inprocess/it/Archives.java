/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool.inprocess.it;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

final class Archives {

    private Archives() {
    }

    /** {@code shop.war}, which says {@code marker}: archives of the same name, on slots of their own. */
    static WebArchive shop(String marker) {
        return marked("shop.war", marker);
    }

    /** {@code name}, which says {@code marker}. */
    static WebArchive marked(String name, String marker) {
        return ShrinkWrap.create(WebArchive.class, name)
                .addClass(MarkerServlet.class)
                .addAsWebResource(new StringAsset(marker), "marker.txt");
    }
}
