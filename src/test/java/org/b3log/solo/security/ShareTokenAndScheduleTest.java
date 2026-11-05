/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import org.b3log.latke.Keys;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.ShareTokenService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

public class ShareTokenAndScheduleTest {

    @BeforeClass
    public void setup() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void shareTokensBindArticleIdViewPwdExpiryAndRevokeOnSecretRotation() throws Exception {
        final AtomicReference<JSONObject> storedSecret = new AtomicReference<>();
        final ShareTokenService svc = new ShareTokenService();

        final Field queryField = ShareTokenService.class.getDeclaredField("optionQueryService");
        queryField.setAccessible(true);
        queryField.set(svc, new OptionQueryService() {
            @Override
            public JSONObject getOptionById(final String id) {
                return ShareTokenService.OPTION_ID_SECRET.equals(id) ? storedSecret.get() : null;
            }
        });

        final Field mgmtField = ShareTokenService.class.getDeclaredField("optionMgmtService");
        mgmtField.setAccessible(true);
        mgmtField.set(svc, new OptionMgmtService() {
            @Override
            public String addOrUpdateOption(final JSONObject option) {
                storedSecret.set(new JSONObject(option.toString()));
                return option.optString(Keys.OBJECT_ID);
            }
        });

        final JSONObject draftArticle = new JSONObject()
                .put(Keys.OBJECT_ID, "art-draft-1")
                .put(Article.ARTICLE_PERMALINK, "/articles/draft-1.html")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_DRAFT)
                .put(Article.ARTICLE_VIEW_PWD, "secret-123");

        final String token = svc.generateToken(draftArticle, svc.resolveTtlSeconds("7d"));
        Assert.assertTrue(svc.verifyToken(draftArticle, token));
        Assert.assertTrue(svc.buildShareUrl(draftArticle, token).contains("/articles/draft-1.html?preview="));

        // Token cannot be reused for another article
        final JSONObject otherArticle = new JSONObject(draftArticle.toString()).put(Keys.OBJECT_ID, "art-draft-2");
        Assert.assertFalse(svc.verifyToken(otherArticle, token));

        // Changing article view password invalidates old token
        final JSONObject changedPwdArticle = new JSONObject(draftArticle.toString()).put(Article.ARTICLE_VIEW_PWD, "new-pwd");
        Assert.assertFalse(svc.verifyToken(changedPwdArticle, token));

        // Expired timestamp is rejected
        final String expiredToken = "1000000." + token.substring(token.indexOf('.') + 1);
        Assert.assertFalse(svc.verifyToken(draftArticle, expiredToken));

        // Rotating HMAC secret revokes all previously issued tokens immediately
        svc.rotateSecret();
        Assert.assertFalse(svc.verifyToken(draftArticle, token), "Rotating secret must revoke existing tokens");
        final String freshToken = svc.generateToken(draftArticle, svc.resolveTtlSeconds("24h"));
        Assert.assertTrue(svc.verifyToken(draftArticle, freshToken));
    }
}
