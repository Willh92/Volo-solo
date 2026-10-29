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

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ContactTemplateTest {
    private String render(final Map<String, String> contacts) throws Exception {
        final String source = Files.readString(Path.of("src/main/webapp/common-template/macro-user_site.ftl"));
        final Template template = new Template("contacts", new StringReader(source + "<@userSite dir=''/>"),
                new Configuration(Configuration.VERSION_2_3_35));
        final StringWriter output = new StringWriter();
        template.process(Map.of("usite", contacts, "hacpaiUser", "", "hacpaiDomain", "ld246.com"), output);
        return output.toString();
    }

    @Test
    public void emptyAndPartialConfigurationRenderWithoutPlaceholders() throws Exception {
        Assert.assertEquals(render(Map.of()).trim(), "");
        final String html = render(Map.of("usiteGitHub", "example"));
        Assert.assertTrue(html.contains("https://github.com/example"));
        Assert.assertEquals(html.split("<a ", -1).length - 1, 1);
    }

    @Test
    public void allConfiguredPlatformsRenderAndAccountValuesCannotInjectMarkup() throws Exception {
        final String[] keys = {"GitHub", "StackOverflow", "Dribbble", "Behance", "Twitter", "Facebook",
                "Instagram", "Medium", "LinkedIn", "Telegram", "WeiBo", "ZhiHu", "QQ", "WeChat", "WYMusic",
                "QQMusic", "Steam", "CodePen"};
        final Map<String, String> contacts = new HashMap<>();
        for (final String key : keys) {
            contacts.put("usite" + key, "account\"><img src=x onerror=alert(1)>");
        }
        contacts.put("usiteResume", "https://example.test/?a=1&b=2");
        final String html = render(contacts);
        Assert.assertFalse(html.contains("<img"));
        Assert.assertFalse(html.contains("javascript:"));
        Assert.assertTrue(html.contains("user__site-account"));
        Assert.assertTrue(html.contains("https://example.test/?a=1&amp;b=2"));
        Assert.assertEquals(html.split("<a ", -1).length - 1, 18);
    }

    @Test
    public void resumeRejectsScriptAndProtocolRelativeUrls() throws Exception {
        Assert.assertEquals(render(Map.of("usiteResume", "javascript:alert(1)")).trim(), "");
        Assert.assertEquals(render(Map.of("usiteResume", "//example.test")).trim(), "");
        Assert.assertTrue(render(Map.of("usiteResume", "/about.html")).contains("href=\"/about.html\""));
    }
}
