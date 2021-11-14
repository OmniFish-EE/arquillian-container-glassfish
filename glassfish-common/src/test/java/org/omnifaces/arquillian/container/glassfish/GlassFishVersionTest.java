/*
 * Copyright (c) 2018 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
// Portions Copyright [2021] [OmniFaces and/or its affiliates]
package org.omnifaces.arquillian.container.glassfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnifaces.arquillian.container.glassfish.GlassFishVersion;

public class GlassFishVersionTest {

    private GlassFishVersion v1;
    private GlassFishVersion v2;

    @Test
    public void moreRecentVersionTest() {
        v1 = new GlassFishVersion("4.1.2.174");
        v2 = new GlassFishVersion("4.1.2.181");
        assertTrue("The version check failed.", v2.isMoreRecentThan(v1));

        v1 = new GlassFishVersion("4.1.2.181");
        v2 = new GlassFishVersion("4.1.3.181");
        assertTrue("The version check failed.", v2.isMoreRecentThan(v1));

        v1 = new GlassFishVersion("4.1.2.181");
        v2 = new GlassFishVersion("4.2.2.181");
        assertTrue("The version check failed.", v2.isMoreRecentThan(v1));

        v1 = new GlassFishVersion("4.1.2.174");
        v2 = new GlassFishVersion("4.1.2.181-SNAPSHOT");
        assertTrue("The version check failed.", v2.isMoreRecentThan(v1));

        v1 = new GlassFishVersion("4.1.2.181-SNAPSHOT");
        v2 = new GlassFishVersion("4.1.2.181");
        assertTrue("The version check failed.", v2.isMoreRecentThan(v1));

        v1 = new GlassFishVersion("5.Beta2");
        v2 = new GlassFishVersion("5.181-SNAPSHOT");
        assertTrue("The version check failed.", v2.isMoreRecentThan(v1));

        v1 = new GlassFishVersion("5.181-SNAPSHOT");
        v2 = new GlassFishVersion("5.181");
        assertTrue("The version check failed.", v2.isMoreRecentThan(v1));
    }
    
    @Test
    public void testVersionBuilderFromProperties() {
    
        GlassFishVersion testVersion = GlassFishVersion.buildVersionFromBrandingProperties("5", "192", "", "", "");
        assertEquals("5.192", testVersion.toString());
        
        testVersion = GlassFishVersion.buildVersionFromBrandingProperties("5", "192", "3", "", "");
        assertEquals("5.192.3", testVersion.toString());
        
        testVersion = GlassFishVersion.buildVersionFromBrandingProperties("4", "1", "2", null, null);
        assertEquals("4.1.2", testVersion.toString());
        
        testVersion = GlassFishVersion.buildVersionFromBrandingProperties("4", "1", "2", "191", "");
        assertEquals("4.1.2.191", testVersion.toString());
        
        testVersion = GlassFishVersion.buildVersionFromBrandingProperties("4", "1", "2", "191", "7");
        assertEquals("4.1.2.191.7", testVersion.toString());
        
        testVersion = GlassFishVersion.buildVersionFromBrandingProperties("4", "1", null, "191", "7");
        assertEquals("4.1", testVersion.toString());
    
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void whenMajorValueIsNull_thanExpectIllegalArgument() {
        GlassFishVersion testVersion = GlassFishVersion.buildVersionFromBrandingProperties(null, "192", "", "", "");
    }
}