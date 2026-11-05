/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.model;

/**
 * Defines keys for lightweight article revision history snapshots.
 */
public final class ArticleRevision {

    public static final String ARTICLE_REVISION = "article_revision";
    public static final String REVISIONS = "revisions";

    public static final String ARTICLE_OID = "articleOId";
    public static final String REVISION_TITLE = "revisionTitle";
    public static final String REVISION_ABSTRACT = "revisionAbstract";
    public static final String REVISION_CONTENT = "revisionContent";
    public static final String REVISION_CREATED = "revisionCreated";
    public static final String REVISION_AUTHOR_NAME = "revisionAuthorName";
    public static final String REVISION_NOTE = "revisionNote";

    public static final int MAX_REVISIONS_PER_ARTICLE = 10;

    private ArticleRevision() {
    }
}
