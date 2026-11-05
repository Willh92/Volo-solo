/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.service.CommentMgmtService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;

public class CommentPermissionAndModerationTest {

    @BeforeClass
    public void setup() throws Exception {
        LatkeTestSupport.configure();
        final var local = new java.util.Properties();
        local.setProperty("runtimeDatabase", "NONE");
        local.setProperty("cookieHttpOnly", "true");
        local.setProperty("loginTrustedProxies", "127.0.0.1,::1");
        org.b3log.latke.Latkes.setLocalProps(local);
    }

    @org.testng.annotations.AfterClass
    public void restore() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void effectiveCommentAccessModeHonorsGlobalSwitchesAndGranularModes() {
        Assert.assertEquals(Option.effectiveCommentAccessMode(null), "all");
        Assert.assertEquals(Option.effectiveCommentAccessMode(new JSONObject()
                .put(Option.ID_C_INTERACTIVE, "true")
                .put(Option.ID_C_COMMENTABLE, "true")
                .put(Option.ID_C_COMMENT_ACCESS_MODE, "registered")), "registered");
        Assert.assertEquals(Option.effectiveCommentAccessMode(new JSONObject()
                .put(Option.ID_C_INTERACTIVE, "true")
                .put(Option.ID_C_COMMENTABLE, "true")
                .put(Option.ID_C_COMMENT_ACCESS_MODE, "off")), "off");
        Assert.assertEquals(Option.effectiveCommentAccessMode(new JSONObject()
                .put(Option.ID_C_INTERACTIVE, "false")
                .put(Option.ID_C_COMMENT_ACCESS_MODE, "all")), "off");
        Assert.assertEquals(Option.effectiveCommentAccessMode(new JSONObject()
                .put(Option.ID_C_COMMENTABLE, "false")
                .put(Option.ID_C_COMMENT_ACCESS_MODE, "registered")), "off");
    }

    @Test
    public void moderationQueueModesClassifyGuestLinksNewUsersAndAdminsProperly() throws Exception {
        final CommentMgmtService svc = new CommentMgmtService();
        final Field repoField = CommentMgmtService.class.getDeclaredField("commentRepository");
        repoField.setAccessible(true);
        repoField.set(svc, new CommentRepository() {
            @Override
            public boolean hasApprovedCommentByUser(final String userId) {
                return "trusted-user".equals(userId);
            }
        });

        final JSONObject allMod = new JSONObject().put(Option.ID_C_COMMENT_MODERATION_MODE, "all");
        Assert.assertEquals(svc.resolveInitialStatus(allMod, true, "admin", "https://example.com"),
                Comment.COMMENT_STATUS_C_APPROVED, "Admin comments always bypass moderation");
        Assert.assertEquals(svc.resolveInitialStatus(allMod, false, "trusted-user", "Hello world"),
                Comment.COMMENT_STATUS_C_PENDING, "'all' mode holds all non-admin comments for review");

        final JSONObject smartMod = new JSONObject().put(Option.ID_C_COMMENT_MODERATION_MODE, "smart");
        Assert.assertEquals(svc.resolveInitialStatus(smartMod, false, "trusted-user",
                        "Links https://spam1.example/a and https://spam2.example/b"),
                Comment.COMMENT_STATUS_C_PENDING, "Smart mode holds multi-link comments even from returning readers");
        Assert.assertEquals(svc.resolveInitialStatus(smartMod, false, "", "感谢博主的深入分享！"),
                Comment.COMMENT_STATUS_C_PENDING, "Smart mode holds unapproved anonymous comments");
        Assert.assertEquals(svc.resolveInitialStatus(smartMod, false, "new-user", "第一次留言"),
                Comment.COMMENT_STATUS_C_PENDING, "Smart mode holds first-time registered readers");
        Assert.assertEquals(svc.resolveInitialStatus(smartMod, false, "trusted-user", "这篇写得很透彻！"),
                Comment.COMMENT_STATUS_C_APPROVED, "Smart mode auto-approves returning readers without link spam");

        final JSONObject offMod = new JSONObject().put(Option.ID_C_COMMENT_MODERATION_MODE, "off");
        Assert.assertEquals(svc.resolveInitialStatus(offMod, false, "", "普通游客留言"),
                Comment.COMMENT_STATUS_C_APPROVED);
    }

    @Test
    public void replyRejectsUnapprovedOrDeletedParentAndEditedSpamTransitionsBackToPending() throws Exception {
        final java.util.Map<String, JSONObject> storedComments = new java.util.HashMap<>();
        storedComments.put("pending-c1", new JSONObject()
                .put(org.b3log.latke.Keys.OBJECT_ID, "pending-c1")
                .put(Comment.COMMENT_ON_ID, "art-1")
                .put(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_PENDING)
                .put(Comment.COMMENT_CONTENT, "待审核评论"));
        storedComments.put("approved-c2", new JSONObject()
                .put(org.b3log.latke.Keys.OBJECT_ID, "approved-c2")
                .put(Comment.COMMENT_ON_ID, "art-1")
                .put(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED)
                .put(Comment.COMMENT_NAME, "ReaderA")
                .put(Comment.COMMENT_CONTENT, "原始合规评论"));
        final java.util.concurrent.atomic.AtomicInteger articleApprovedCount = new java.util.concurrent.atomic.AtomicInteger(1);

        final CommentMgmtService svc = new CommentMgmtService();
        final Field commentRepoField = CommentMgmtService.class.getDeclaredField("commentRepository");
        commentRepoField.setAccessible(true);
        commentRepoField.set(svc, new CommentRepository() {
            @Override
            public org.b3log.latke.repository.Transaction beginTransaction() {
                return new org.b3log.latke.repository.Transaction() {
                    private boolean active = true;
                    @Override public String getId() { return "tx"; }
                    @Override public void commit() { active = false; }
                    @Override public void rollback() { active = false; }
                    @Override public boolean isActive() { return active; }
                };
            }

            @Override
            public JSONObject get(final String id) {
                return storedComments.get(id);
            }

            @Override
            public void update(final String id, final JSONObject comment, final String... propertyNames) {
                storedComments.put(id, comment);
            }

            @Override
            public java.util.List<JSONObject> getComments(final String articleId, final int currentPageNum, final int pageSize) {
                return new java.util.ArrayList<>(storedComments.values());
            }
        });
        final Field optionQueryField = CommentMgmtService.class.getDeclaredField("optionQueryService");
        optionQueryField.setAccessible(true);
        optionQueryField.set(svc, new org.b3log.solo.service.OptionQueryService() {
            @Override
            public JSONObject getPreference() {
                return new JSONObject()
                        .put(Option.ID_C_INTERACTIVE, "true")
                        .put(Option.ID_C_COMMENTABLE, true)
                        .put(Option.ID_C_COMMENT_ACCESS_MODE, "all");
            }
        });
        final Field articleRepoField = CommentMgmtService.class.getDeclaredField("articleRepository");
        articleRepoField.setAccessible(true);
        articleRepoField.set(svc, new org.b3log.solo.repository.ArticleRepository() {
            @Override
            public JSONObject get(final String id) {
                return "art-1".equals(id) ? new JSONObject()
                        .put(org.b3log.latke.Keys.OBJECT_ID, "art-1")
                        .put(org.b3log.solo.model.Article.ARTICLE_STATUS, org.b3log.solo.model.Article.ARTICLE_STATUS_C_PUBLISHED)
                        .put(org.b3log.solo.model.Article.ARTICLE_COMMENTABLE, true)
                        .put(org.b3log.solo.model.Article.ARTICLE_COMMENT_COUNT, articleApprovedCount.get()) : null;
            }

            @Override
            public void update(final String id, final JSONObject article, final String... propertyNames) {
                articleApprovedCount.set(article.optInt(org.b3log.solo.model.Article.ARTICLE_COMMENT_COUNT, 0));
            }
        });

        // 1. Replying to a pending comment must be rejected
        final JSONObject badReply = svc.checkAddCommentRequest(new JSONObject()
                .put(org.b3log.latke.Keys.OBJECT_ID, "art-1")
                .put(org.b3log.solo.model.Common.TYPE, org.b3log.solo.model.Article.ARTICLE)
                .put(Comment.COMMENT_NAME, "Guest")
                .put(Comment.COMMENT_CONTENT, "回复待审评论")
                .put(Comment.COMMENT_ORIGINAL_COMMENT_ID, "pending-c1"));
        Assert.assertFalse(badReply.optBoolean(org.b3log.solo.model.Common.STATUS_CODE));
        Assert.assertEquals(badReply.optString(org.b3log.latke.Keys.MSG), "回复的评论不存在，或不属于当前文章。");

        // 2. Editing an approved comment into spam transitions it to PENDING and decrements approved count to 0
        svc.updateCommentContentAndStatus("approved-c2", "https://spam1.test and https://spam2.test",
                Comment.COMMENT_STATUS_C_PENDING);
        Assert.assertEquals(storedComments.get("approved-c2").getInt(Comment.COMMENT_STATUS),
                Comment.COMMENT_STATUS_C_PENDING);
        Assert.assertEquals(articleApprovedCount.get(), 0);
    }
}
