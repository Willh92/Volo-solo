/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Ids;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.ArticleRevision;
import org.b3log.solo.repository.ArticleRevisionRepository;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manages article revision snapshots (retaining up to {@link ArticleRevision#MAX_REVISIONS_PER_ARTICLE} versions per article)
 * and supports one-click rollback.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class ArticleRevisionService {

    private static final Logger LOGGER = Logger.getLogger(ArticleRevisionService.class);

    @Inject
    private ArticleRevisionRepository articleRevisionRepository;

    /**
     * Records a snapshot in its own transaction after an article add/update commits.
     */
    public void recordSnapshot(final JSONObject previousArticle, final JSONObject currentArticle,
                               final String authorName, final String note) {
        if (currentArticle == null || articleRevisionRepository == null) {
            return;
        }
        final String articleId = currentArticle.optString(Keys.OBJECT_ID);
        if (StringUtils.isBlank(articleId)) {
            return;
        }
        final Transaction tx = articleRevisionRepository.beginTransaction();
        try {
            final List<JSONObject> existing = articleRevisionRepository.listByArticleId(articleId, 20);
            if (existing.isEmpty() && previousArticle != null && hasContentDiff(previousArticle, currentArticle)) {
                insertRow(articleId,
                        previousArticle.optString(Article.ARTICLE_TITLE, ""),
                        previousArticle.optString(Article.ARTICLE_ABSTRACT, ""),
                        previousArticle.optString(Article.ARTICLE_CONTENT, ""),
                        authorName,
                        "更新前历史版本",
                        previousArticle.optLong(Article.ARTICLE_UPDATED, System.currentTimeMillis() - 1000L));
            }
            if (!existing.isEmpty() && !hasSnapshotDiff(existing.get(0), currentArticle)) {
                tx.commit();
                return;
            }
            insertRow(articleId,
                    currentArticle.optString(Article.ARTICLE_TITLE, ""),
                    currentArticle.optString(Article.ARTICLE_ABSTRACT, ""),
                    currentArticle.optString(Article.ARTICLE_CONTENT, ""),
                    authorName,
                    StringUtils.defaultIfBlank(note, previousArticle == null ? "创建文章" : "编辑保存"),
                    System.currentTimeMillis());
            articleRevisionRepository.pruneExcess(articleId, ArticleRevision.MAX_REVISIONS_PER_ARTICLE);
            tx.commit();
        } catch (final Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            LOGGER.log(Level.WARN, "Records article revision failed [articleId=" + articleId + "]", e);
        }
    }

    /**
     * Removes all revisions of a deleted article in its own transaction.
     */
    public void removeRevisions(final String articleId) {
        if (StringUtils.isBlank(articleId) || articleRevisionRepository == null) {
            return;
        }
        final Transaction tx = articleRevisionRepository.beginTransaction();
        try {
            articleRevisionRepository.removeByArticleId(articleId);
            tx.commit();
        } catch (final Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            LOGGER.log(Level.WARN, "Removes article revisions failed [articleId=" + articleId + "]", e);
        }
    }

    /**
     * Lists lightweight revision metadata for the editor history drawer.
     */
    public List<JSONObject> listRevisions(final String articleId) throws ServiceException {
        if (StringUtils.isBlank(articleId)) {
            return List.of();
        }
        try {
            final List<JSONObject> rows = articleRevisionRepository.listByArticleId(
                    articleId, ArticleRevision.MAX_REVISIONS_PER_ARTICLE);
            final List<JSONObject> summaries = new ArrayList<>(rows.size());
            for (final JSONObject row : rows) {
                final String content = row.optString(ArticleRevision.REVISION_CONTENT, "");
                summaries.add(new JSONObject()
                        .put(Keys.OBJECT_ID, row.optString(Keys.OBJECT_ID))
                        .put(ArticleRevision.ARTICLE_OID, row.optString(ArticleRevision.ARTICLE_OID))
                        .put(ArticleRevision.REVISION_TITLE, row.optString(ArticleRevision.REVISION_TITLE))
                        .put(ArticleRevision.REVISION_AUTHOR_NAME, row.optString(ArticleRevision.REVISION_AUTHOR_NAME))
                        .put(ArticleRevision.REVISION_NOTE, row.optString(ArticleRevision.REVISION_NOTE))
                        .put(ArticleRevision.REVISION_CREATED, row.optLong(ArticleRevision.REVISION_CREATED))
                        .put("contentLength", content.length()));
            }
            return summaries;
        } catch (final RepositoryException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Gets a single full revision ensuring it belongs to {@code articleId}.
     */
    public JSONObject getRevision(final String articleId, final String revisionId) throws ServiceException {
        if (StringUtils.isBlank(articleId) || StringUtils.isBlank(revisionId)) {
            return null;
        }
        try {
            final JSONObject rev = articleRevisionRepository.get(revisionId);
            if (rev == null || !articleId.equals(rev.optString(ArticleRevision.ARTICLE_OID))) {
                return null;
            }
            return rev;
        } catch (final RepositoryException e) {
            throw new ServiceException(e);
        }
    }

    private void insertRow(final String articleId, final String title, final String abs, final String content,
                           final String authorName, final String note, final long createdMillis) throws RepositoryException {
        final JSONObject rev = new JSONObject();
        rev.put(Keys.OBJECT_ID, Ids.genTimeMillisId());
        rev.put(ArticleRevision.ARTICLE_OID, articleId);
        rev.put(ArticleRevision.REVISION_TITLE, StringUtils.left(StringUtils.defaultString(title), 255));
        rev.put(ArticleRevision.REVISION_ABSTRACT, StringUtils.defaultString(abs));
        rev.put(ArticleRevision.REVISION_CONTENT, StringUtils.defaultString(content));
        rev.put(ArticleRevision.REVISION_AUTHOR_NAME, StringUtils.left(StringUtils.defaultString(authorName), 64));
        rev.put(ArticleRevision.REVISION_NOTE, StringUtils.left(StringUtils.defaultString(note), 255));
        rev.put(ArticleRevision.REVISION_CREATED, createdMillis);
        articleRevisionRepository.add(rev);
    }

    private static boolean hasContentDiff(final JSONObject a, final JSONObject b) {
        return !Objects.equals(a.optString(Article.ARTICLE_TITLE, ""), b.optString(Article.ARTICLE_TITLE, ""))
                || !Objects.equals(a.optString(Article.ARTICLE_ABSTRACT, ""), b.optString(Article.ARTICLE_ABSTRACT, ""))
                || !Objects.equals(a.optString(Article.ARTICLE_CONTENT, ""), b.optString(Article.ARTICLE_CONTENT, ""));
    }

    private static boolean hasSnapshotDiff(final JSONObject snapshot, final JSONObject article) {
        return !Objects.equals(snapshot.optString(ArticleRevision.REVISION_TITLE, ""), article.optString(Article.ARTICLE_TITLE, ""))
                || !Objects.equals(snapshot.optString(ArticleRevision.REVISION_ABSTRACT, ""), article.optString(Article.ARTICLE_ABSTRACT, ""))
                || !Objects.equals(snapshot.optString(ArticleRevision.REVISION_CONTENT, ""), article.optString(Article.ARTICLE_CONTENT, ""));
    }
}
