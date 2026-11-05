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

    @Test
    public void usiteNormalizationAcceptsBlanksHandlesAndStripsPastedPlatformUrls() {
        final org.json.JSONObject incoming = new org.json.JSONObject()
                .put("usiteGitHub", "https://github.com/example-user/")
                .put("usiteMedium", "https://medium.com/@writer")
                .put("usiteTelegram", "@tg_handle")
                .put("usiteWeiBo", "")
                .put("usiteFacebook", "   ")
                .put("usiteResume", "");
        final org.json.JSONObject stored = new org.json.JSONObject()
                .put("usiteUserId", "u1001")
                .put("oId", "oid1001");
        final org.json.JSONObject normalized = org.b3log.solo.processor.UserTemplateProcessor
                .normalizeAndValidateUsite(incoming, stored);
        Assert.assertEquals(normalized.optString("usiteGitHub"), "example-user");
        Assert.assertEquals(normalized.optString("usiteMedium"), "writer");
        Assert.assertEquals(normalized.optString("usiteTelegram"), "tg_handle");
        Assert.assertEquals(normalized.optString("usiteWeiBo"), "");
        Assert.assertEquals(normalized.optString("usiteFacebook"), "");
        Assert.assertEquals(normalized.optString("usiteResume"), "");
        Assert.assertEquals(normalized.optString("usiteUserId"), "u1001");
        Assert.assertEquals(normalized.optString("oId"), "oid1001");

        Assert.assertThrows(IllegalArgumentException.class, () ->
                org.b3log.solo.processor.UserTemplateProcessor.normalizeAndValidateUsite(
                        new org.json.JSONObject().put("usiteGitHub", "bad\"><script>alert(1)</script>"), null));
        Assert.assertThrows(IllegalArgumentException.class, () ->
                org.b3log.solo.processor.UserTemplateProcessor.normalizeAndValidateUsite(
                        new org.json.JSONObject().put("usiteResume", "javascript:alert(1)"), null));
    }

    @Test
    public void unifiedBooleanSwitchMigrationAndMarkdownAllowlistRemainConsistentAndSafe() throws Exception {
        final org.json.JSONObject pref = new org.json.JSONObject()
                .put("trueStr", "true")
                .put("falseStr", "false")
                .put("boolTrue", true)
                .put("boolFalse", false)
                .put("blank", " ");
        Assert.assertTrue(org.b3log.solo.model.Option.optBooleanSwitch(pref, "trueStr", false));
        Assert.assertFalse(org.b3log.solo.model.Option.optBooleanSwitch(pref, "falseStr", true));
        Assert.assertTrue(org.b3log.solo.model.Option.optBooleanSwitch(pref, "boolTrue", false));
        Assert.assertFalse(org.b3log.solo.model.Option.optBooleanSwitch(pref, "boolFalse", true));
        Assert.assertTrue(org.b3log.solo.model.Option.optBooleanSwitch(pref, "blank", true));
        Assert.assertFalse(org.b3log.solo.model.Option.optBooleanSwitch(pref, "missing", false));

        Assert.assertEquals(org.b3log.solo.model.Option.normalizeBooleanSwitch("true", false, "err"), "true");
        Assert.assertEquals(org.b3log.solo.model.Option.normalizeBooleanSwitch(Boolean.FALSE, true, "err"), "false");
        Assert.assertThrows(IllegalArgumentException.class, () ->
                org.b3log.solo.model.Option.normalizeBooleanSwitch("on", false, "err"));
        Assert.assertThrows(IllegalArgumentException.class, () ->
                org.b3log.solo.model.Option.normalizeBooleanSwitch("off", true, "err"));

        // Verify startup migration converts legacy "on"/"off"/"1"/"0" option rows to canonical "true"/"false"
        final java.util.Map<String, org.json.JSONObject> dbRows = new java.util.HashMap<>();
        dbRows.put("smtpAuth", new org.json.JSONObject().put("oId", "smtpAuth").put("optionValue", "on"));
        dbRows.put(org.b3log.solo.model.Option.ID_C_SHOW_LOGIN_ENTRY,
                new org.json.JSONObject().put("oId", org.b3log.solo.model.Option.ID_C_SHOW_LOGIN_ENTRY).put("optionValue", "off"));
        dbRows.put(org.b3log.solo.model.Option.ID_C_INTERACTIVE,
                new org.json.JSONObject().put("oId", org.b3log.solo.model.Option.ID_C_INTERACTIVE).put("optionValue", "off"));
        dbRows.put(org.b3log.solo.model.Option.ID_C_ALLOW_REGISTER,
                new org.json.JSONObject().put("oId", org.b3log.solo.model.Option.ID_C_ALLOW_REGISTER).put("optionValue", "on"));
        org.b3log.solo.upgrade.V20261101_20261105.migrateBooleanSwitchOptions(
                new org.b3log.solo.service.OptionQueryService() {
                    @Override
                    public org.json.JSONObject getOptionById(final String optionId) {
                        return dbRows.get(optionId);
                    }
                },
                new org.b3log.solo.service.OptionMgmtService() {
                    @Override
                    public String addOrUpdateOption(final org.json.JSONObject option) {
                        dbRows.put(option.optString("oId"), option);
                        return option.optString("oId");
                    }
                }
        );
        Assert.assertEquals(dbRows.get("smtpAuth").getString("optionValue"), "true");
        Assert.assertEquals(dbRows.get(org.b3log.solo.model.Option.ID_C_SHOW_LOGIN_ENTRY).getString("optionValue"), "false");
        Assert.assertEquals(dbRows.get(org.b3log.solo.model.Option.ID_C_INTERACTIVE).getString("optionValue"), "false");
        Assert.assertEquals(dbRows.get(org.b3log.solo.model.Option.ID_C_ALLOW_REGISTER).getString("optionValue"), "true");

        // Verify Markdowns.clean preserves standard Markdown elements & ToC heading IDs while stripping XSS
        final String cleaned = org.b3log.solo.util.Markdowns.clean(
                "<h2 id=\"toc-h2-1\">章节</h2><p><del>删除线</del> <kbd>Ctrl+K</kbd> <mark>重点</mark></p>"
                        + "<details open><summary>展开</summary><p>内容</p></details><hr>"
                        + "<table><tr><th colspan=\"2\">表头</th></tr></table>"
                        + "<img src=\"/image/a.png\" loading=\"lazy\" onerror=\"alert(1)\">"
                        + "<a href=\"javascript:alert(2)\">bad</a><script>alert(3)</script>");
        Assert.assertTrue(cleaned.contains("id=\"toc-h2-1\""));
        Assert.assertTrue(cleaned.contains("<del>删除线</del>"));
        Assert.assertTrue(cleaned.contains("<kbd>Ctrl+K</kbd>"));
        Assert.assertTrue(cleaned.contains("<mark>重点</mark>"));
        Assert.assertTrue(cleaned.contains("<details"));
        Assert.assertTrue(cleaned.contains("<summary>展开</summary>"));
        Assert.assertTrue(cleaned.contains("colspan=\"2\""));
        Assert.assertFalse(cleaned.contains("onerror"));
        Assert.assertFalse(cleaned.contains("javascript:"));
        Assert.assertFalse(cleaned.contains("<script"));

        // Verify bookshelf shortcodes are stripped from excerpts and replaced cleanly in RSS/Atom feeds
        final String mdWithShelf = "导语段落\n\n:::bookshelf:::\n\n:::book id=\"go-guide\":::\n\n正文段落";
        Assert.assertFalse(org.b3log.solo.service.HiddenContentService.stripForExcerptOrImage(mdWithShelf).contains(":::bookshelf"));
        Assert.assertFalse(org.b3log.solo.service.SeriesAndBookshelfService.stripForFeed(mdWithShelf).contains(":::book"));
        Assert.assertTrue(org.b3log.solo.service.SeriesAndBookshelfService.stripForFeed(mdWithShelf).contains("在线书架阅读组件"));
    }
}
