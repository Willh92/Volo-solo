/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo.security;

import com.zaxxer.hikari.HikariConfig;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MariaDbDriverTest {
    @Test
    public void loadsMariaDbDriverWithTheExplicitMariaDbProtocol() throws Exception {
        final java.sql.Driver driver = (java.sql.Driver) Class.forName("org.mariadb.jdbc.Driver").getDeclaredConstructor().newInstance();
        Assert.assertTrue(driver.acceptsURL("jdbc:mariadb://localhost:3306/solo?allowLocalInfile=false"));
        final HikariConfig pool = new HikariConfig();
        pool.setDriverClassName("org.mariadb.jdbc.Driver");
        pool.setJdbcUrl("jdbc:mariadb://localhost:3306/solo?allowLocalInfile=false");
        Assert.assertEquals(pool.getDriverClassName(), "org.mariadb.jdbc.Driver");
    }
}
