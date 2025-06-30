/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2021 Payara Foundation and/or its affiliates. All rights reserved.
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
package ee.omnifish.arquillian.environment.setup;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import org.jboss.arquillian.container.test.spi.RemoteLoadableExtension;
import org.jboss.arquillian.container.test.spi.client.deployment.AuxiliaryArchiveAppender;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.event.suite.AfterSuite;
import org.jboss.arquillian.test.spi.event.suite.BeforeSuite;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

/**
 *
 * @author lprimak
 */
public class EnviromentRemoteExtension implements RemoteLoadableExtension {
    static final String ENV_PROP_FILE = "environment-variables.properties";
    static final String SYS_PROP_FILE = "system-properties.properties";

    @Override
    public void register(ExtensionBuilder eb) {
        eb.observer(EnvironmentSetter.class);
    }

    public static class Appender implements AuxiliaryArchiveAppender {
        @Override
        public Archive<?> createAuxiliaryArchive() {
            return ShrinkWrap.create(JavaArchive.class, "arquillian-container-environment.jar").addClass(EnviromentRemoteExtension.class)
                    .addAsServiceProvider(RemoteLoadableExtension.class, EnviromentRemoteExtension.class);
        }
    }

    public static class EnvironmentSetter {
        private static final Lock lock = new ReentrantLock();

        public static void setEnvironment(@Observes BeforeSuite before) {
            loadAndPerform(SYS_PROP_FILE, (k, v) -> System.setProperty(k.toString(), v.toString()));
            lock.lock();
            try {
                loadAndPerform(ENV_PROP_FILE, (k, v) -> getEnvironmentMap().put(k.toString(), v.toString()));
            } finally {
                lock.unlock();
            }
        }

        // unsetting the environment will make the next deployment fail due to no environment
        // there is no way to set the environment before deploying the application, so the
        // first deployment cannot rely on environment being set for Injections
        // all other interation should work normally
        public static void unsetEnvironment(/* @Observes */ AfterSuite before) {
            loadAndPerform(SYS_PROP_FILE, (k, v) -> System.clearProperty(k.toString()));
            lock.lock();
            try {
                loadAndPerform(ENV_PROP_FILE, (k, v) -> getEnvironmentMap().remove(k.toString()));
            } finally {
                lock.unlock();
            }
        }

        private static void loadAndPerform(String resourceName, BiConsumer<Object, Object> action) {
            Properties props = new Properties();
            try (InputStream istrm = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
                props.load(istrm);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            props.forEach(action);
        }

        @SuppressWarnings({ "unchecked" })
        static Map<String, String> getEnvironmentMap() {
            try {
                Map<String, String> env = System.getenv();
                Field field = env.getClass().getDeclaredField("m");
                field.setAccessible(true);
                return (Map<String, String>) field.get(env);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
