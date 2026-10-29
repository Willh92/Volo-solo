/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 * Licensed under the GNU Affero General Public License, version 3 or later.
 */
package org.b3log.solo.security;

import java.lang.reflect.Field;
import java.util.Properties;
import org.b3log.latke.Latkes;
import org.b3log.latke.service.LangPropsService;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.CommentMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AnonymousCommentTest {
    @Test
    public void anUnknownNicknameUsesTheLocalAvatarWithoutCreatingAnAccount() throws Exception {
        configure();
        final CommentMgmtService service = new CommentMgmtService();
        inject(service, "userRepository", new UserRepository() {
            @Override public JSONObject getByUserName(final String name) { return null; }
            @Override public String add(final JSONObject user) { throw new AssertionError("Anonymous nicknames are not accounts"); }
        });
        final JSONObject comment = new JSONObject().put(Comment.COMMENT_NAME, "new-reader");
        service.setCommentThumbnailURL(comment);
        Assert.assertEquals(comment.getString(Comment.COMMENT_THUMBNAIL_URL), Latkes.getStaticServePath() + "/images/volo-mark.svg");
        inject(service, "userRepository", new UserRepository() {
            @Override public JSONObject getByUserName(final String name) { return new JSONObject().put(UserExt.USER_AVATAR, "/avatar.png"); }
        });
        service.setCommentThumbnailURL(comment);
        Assert.assertEquals(comment.getString(Comment.COMMENT_THUMBNAIL_URL), "/avatar.png");
        inject(service, "userRepository", new UserRepository() {
            @Override public JSONObject getByUserName(final String name) { return new JSONObject().put(UserExt.USER_AVATAR, ""); }
        });
        service.setCommentThumbnailURL(comment);
        Assert.assertEquals(comment.getString(Comment.COMMENT_THUMBNAIL_URL), Latkes.getStaticServePath() + "/images/volo-mark.svg");
    }

    @Test
    public void aKnownDraftIdCannotAcceptAnonymousComments() throws Exception {
        configure();
        final CommentMgmtService service = new CommentMgmtService();
        inject(service, "optionQueryService", new OptionQueryService() {
            @Override public JSONObject getPreference() { return new JSONObject().put(Option.ID_C_COMMENTABLE, true); }
        });
        inject(service, "articleRepository", new ArticleRepository() {
            @Override public JSONObject get(final String id) { return new JSONObject().put(Article.ARTICLE_COMMENTABLE, true).put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_DRAFT); }
        });
        inject(service, "langPropsService", new LangPropsService() {
            @Override public String get(final String key) { return key; }
        });
        final JSONObject request = new JSONObject().put("oId", "draft").put(Common.TYPE, Article.ARTICLE)
                .put(Comment.COMMENT_NAME, "guest").put(Comment.COMMENT_CONTENT, "comment");
        Assert.assertFalse(service.checkAddCommentRequest(request).getBoolean("sc"));
    }

    private static void inject(final Object target, final String name, final Object value) throws Exception {
        final Field field = CommentMgmtService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void configure() throws Exception {
        LatkeTestSupport.configure();
        final Properties properties = new Properties();
        properties.setProperty("runtimeDatabase", "NONE");
        Latkes.setLocalProps(properties);
    }
}
