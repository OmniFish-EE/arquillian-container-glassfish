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

import java.util.Scanner;

/**
 * A utility class to store the GlassFish Version and check if it's more recent than another version.
 */
public class GlassFishVersion {

    private String versionString;

    public GlassFishVersion(String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            throw new IllegalArgumentException("Invalid version string.");
        }
        this.versionString = versionString;
    }

    /**
     * This Method takes in properties from a glassfish-version.properties file and constructs a new GlassFishVersion object
     * from this data. It is assumed that there will always be a major and minor version but update, payaraMajor and
     * payaraMinor may be null.
     *
     * @param major - Major version of the server
     * @param minor - Minor version of the server
     * @param update - Stability/Feature patch version of the server
     * @param glassFishMinor - Minor version of GlassFish versions
     * @param glassFishUpdate - Stability/Feat patch version of GlassFish versions
     * @return
     */
    public static GlassFishVersion buildVersionFromBrandingProperties(String major, String minor, String update, String glassFishMinor,
            String glassFishUpdate) throws IllegalArgumentException {
        StringBuilder versionBuilder = new StringBuilder();
        String[] subVersionValues = { minor, update, glassFishMinor, glassFishUpdate };

        if (major == null || major.isEmpty()) {
            throw new IllegalArgumentException("Invalid properties - Major version value cannot be null or empty.");
        }

        versionBuilder.append(major); // should always be a major version with value

        for (int i = 0; i < subVersionValues.length; i++) {

            String curVersionValue = subVersionValues[i];

            if (curVersionValue != null && !curVersionValue.isEmpty()) {
                versionBuilder.append(".");
                versionBuilder.append(curVersionValue);
            } else {
                break;
            }
        }

        return new GlassFishVersion(versionBuilder.toString());
    }

    public boolean isMoreRecentThan(String versionString) {
        return isMoreRecentThan(new GlassFishVersion(versionString));
    }

    /**
     * A Utility method used for comparing GlassFishVersion objects
     *
     * When the parsed GlassFishVersion is equal to the version it's being compared to, the method will return true
     *
     * @param minimum - the version you wish to compare to
     * @return true if minimum is equal to or greater than the version to compare
     */
    public boolean isMoreRecentThan(GlassFishVersion minimum) {
        String minString = minimum.versionString;
        try (Scanner minScanner = new Scanner(minString); Scanner vScanner = new Scanner(versionString)) {
            minScanner.useDelimiter("\\.");
            vScanner.useDelimiter("\\.");

            while (minScanner.hasNext() && vScanner.hasNext()) {
                String minPartString = minScanner.next();
                String vPartString = vScanner.next();

                int minPart = 0;
                try {
                    minPart = Integer.parseInt(minPartString.replaceAll("[^0-9]", ""));
                } catch (Exception ex) {
                    // Leave the number at 0
                }
                int vPart = 0;
                try {
                    vPart = Integer.parseInt(vPartString.replaceAll("[^0-9]", ""));
                } catch (Exception ex) {
                    // Leave the number at 0
                }

                if (minPartString.toUpperCase().contains("-SNAPSHOT")) {
                    minPart--;
                }
                if (vPartString.toUpperCase().contains("-SNAPSHOT")) {
                    vPart--;
                }

                if (vPart > minPart) {
                    return true;
                }
                if (minPart > vPart) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return versionString;
    }
}