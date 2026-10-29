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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.b3log.solo.log4j.RamAppender;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LoggingConfigurationTest {
    @Test
    public void loadsConvertedConfigurationAndKeepsFiveRollingBackups() throws Exception {
        final Path directory = Files.createTempDirectory("bolo-logging-test-");
        final LoggerContext context = new LoggerContext("converted-config-test");
        try {
            final String original = Files.readString(Path.of("src/main/resources/log4j2-file.xml.example"));
            // Reduce the threshold in this disposable test; production retains 50 MB.
            final String xml = original.replace("${sys:base.dir:-.}", directory.toString().replace('\\', '/'))
                    .replace("size=\"50 MB\"", "size=\"1 KB\"")
                    .replace("<AppenderRef ref=\"stdout\"/>", "");
            context.start(new XmlConfiguration(context, new ConfigurationSource(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))));
            Assert.assertTrue(context.getConfiguration().getAppender("ramAppender") instanceof RamAppender);
            Assert.assertFalse(context.getLogger("org.b3log.latke").isInfoEnabled());
            Assert.assertFalse(context.getLogger("io.netty").isInfoEnabled());
            final var logger = context.getLogger("org.b3log.solo.security.LoggingConfigurationTest");
            Assert.assertTrue(logger.isInfoEnabled());
            for (int index = 0; index < 12; index++) {
                logger.info("配置迁移-{} {}", index, "x".repeat(1500));
            }
            Assert.assertTrue(Files.readString(directory.resolve("logs/solo.log")).contains("配置迁移-11"));
            try (var files = Files.list(directory.resolve("logs"))) {
                Assert.assertEquals(files.filter(file -> file.getFileName().toString().startsWith("solo.log.")).count(), 5L);
            }
            Assert.assertTrue(Files.readString(directory.resolve("logs/solo.log.1")).contains("配置迁移-10"));
        } finally {
            context.stop();
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
}
