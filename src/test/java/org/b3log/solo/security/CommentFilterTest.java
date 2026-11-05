/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import org.b3log.solo.model.Option;
import org.b3log.solo.service.CommentFilterService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CommentFilterTest {
    @Test public void builtinDictionaryIsEnabledForExistingSitesWithoutANewOption() {
        final var filter = new CommentFilterService().prepare(new JSONObject());
        Assert.assertTrue(filter.blocks("你就是个傻逼"));
        Assert.assertFalse(filter.blocks("Java 17 / Nginx 配置：https://example.test/，邮箱 user@example.test，端口 8080，IP 192.0.2.1。"));
    }

    @Test public void existingSpamWordsUseTheSameMatcherWithBuiltinDisabled() {
        final var filter = new CommentFilterService().prepare(rules("禁词,forbidden,ass", ""));
        Assert.assertTrue(filter.blocks("包含禁词的留言"));
        Assert.assertTrue(filter.blocks("ＦＯＲＢＩＤＤＥＮ"));
        Assert.assertTrue(filter.blocks("FORBIDDEN"));
        Assert.assertTrue(filter.blocks("禁\u200b词"));
        Assert.assertTrue(filter.blocks("禁**词**"));
        Assert.assertTrue(filter.blocks("&#x7981;词"));
        Assert.assertFalse(filter.blocks("The Java class compiles successfully."), "English keywords must respect word boundaries");
        Assert.assertFalse(filter.blocks("测试一条正常的留言"));
    }

    @Test public void allowlistedPhrasesDoNotWhitelistOtherViolationsInTheSameComment() {
        final var filter = new CommentFilterService().prepare(rules("禁词,forbidden", "非禁词示例"));
        Assert.assertFalse(filter.blocks("这是非禁词示例。"));
        Assert.assertTrue(filter.blocks("非禁词示例，但是 forbidden 仍需拦截。"));
        Assert.assertTrue(filter.blocks("非禁词示例，另外出现禁词。"));
    }

    @Test public void immutableSnapshotsAreReusedAndReplacementDoesNotModifyReaders() {
        final CommentFilterService service = new CommentFilterService();
        final var original = service.prepare(rules("旧词", ""));
        service.activate(original);
        Assert.assertSame(service.prepare(rules("旧词", "")), original);
        final var replacement = service.prepare(rules("新词", ""));
        service.activate(replacement);
        Assert.assertTrue(original.blocks("旧词"));
        Assert.assertFalse(original.blocks("新词"));
        Assert.assertFalse(service.blocked("旧词"));
        Assert.assertTrue(service.blocked("新词"));
        Assert.assertFalse(service.prepare(rules("", "")).blocks("任意文本"));
    }

    @Test public void invalidSettingsAreRejectedBeforeCompilingOrPersisting() {
        Assert.expectThrows(IllegalArgumentException.class, () -> CommentFilterService.validate(rules("a".repeat(201), "")));
        Assert.expectThrows(IllegalArgumentException.class, () -> CommentFilterService.validate(rules("x,".repeat(10001), "")));
        Assert.expectThrows(IllegalArgumentException.class, () -> CommentFilterService.validate(rules("", "").put(Option.ID_C_COMMENT_FILTER_BUILTIN, "invalid")));
    }

    private static JSONObject rules(final String deny, final String allow) {
        return new JSONObject().put(Option.ID_C_COMMENT_FILTER_BUILTIN, "false").put(Option.ID_C_SPAM, deny).put(Option.ID_C_COMMENT_FILTER_ALLOW, allow);
    }
}
