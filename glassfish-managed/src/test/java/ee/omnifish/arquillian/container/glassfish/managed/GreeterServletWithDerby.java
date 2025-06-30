/*
 * Copyright (c) 2017-2021 Payara Foundation and/or its affiliates. All rights reserved.
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
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Portions Copyright [2021] [OmniFaces and/or its affiliates]
package ee.omnifish.arquillian.container.glassfish.managed;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import jakarta.annotation.Resource;
import jakarta.ejb.EJB;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Simple servlet for testing deployment with enabled Derby database.
 *
 * @author <a href="http://community.jboss.org/people/aslak">Aslak Knutsen</a>
 * @author <a href="http://community.jboss.org/people/LightGuard">Jason Porter</a>
 */
@WebServlet("/Greeter")
public class GreeterServletWithDerby extends HttpServlet {

    private static final String GET_LOG_ARCHIVE_MODE_QUERY =
        "VALUES SYSCS_UTIL.SYSCS_GET_DATABASE_PROPERTY('derby.storage.logArchiveMode')";

    private static final long serialVersionUID = 8249673615048070666L;

    private static final Logger logger = Logger.getLogger(GreeterServletWithDerby.class.getName());

    @EJB
    private Greeter greeter;

    @Resource(name = "jdbc/__default")
    private DataSource dataSource;

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // test the DataSource and thus the working DB connection with an internal H2 query
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            connection = dataSource.getConnection();
            statement = connection.createStatement();
            resultSet = statement.executeQuery(GET_LOG_ARCHIVE_MODE_QUERY);

            resultSet.next();
            final PrintWriter writer = resp.getWriter();
            if (!resultSet.getBoolean(1)) {
                writer.append(this.greeter.greet());
            } else {
                writer.append("Something terrible happened! No greetings! derby.storage.logArchiveMode is set to TRUE");
            }
        } catch (SQLException ex) {
            throw new ServletException(ex);
        } finally {
            closeResources(connection, statement, resultSet);
        }
    }

    private void closeResources(Connection conn, Statement stmt, ResultSet rs) {
        try {
            if (rs != null) {
                conn.close();
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed closing resource!", e);
        }

        try {
            if (rs != null) {
                stmt.close();
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed closing resource!", e);
        }

        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed closing resource!", e);
        }
    }
}
