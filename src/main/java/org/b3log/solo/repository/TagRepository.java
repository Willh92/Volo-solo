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
package org.b3log.solo.repository;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Tag;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tag repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class TagRepository extends AbstractRepository {

    /**
     * Public constructor.
     */
    public TagRepository() {
        super(Tag.TAG);
    }

    /**
     * Tag-Article relation repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    @Inject
    private ArticleRepository articleRepository;

    /**
     * Gets tags of an article specified by the article id.
     *
     * @param articleId the specified article id
     * @return a list of tags of the specified article, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getByArticleId(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isBlank()) {
            return new ArrayList<>();
        }
        final List<JSONObject> rows = select("SELECT t.oId, t.tagTitle FROM " + getName() + " AS t JOIN "
                + tagArticleRepository.getName() + " AS r ON r.tag_oId = t.oId WHERE r.article_oId = ?", articleId);
        return null == rows ? new ArrayList<>() : rows;
    }

    /**
     * Gets a tag by the specified tag title with its published article count in a single query.
     *
     * @param tagTitle the specified tag title
     * @return a tag, {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByTitle(final String tagTitle) throws RepositoryException {
        if (null == tagTitle || tagTitle.isBlank()) {
            return null;
        }
        if (null != articleRepository && null != tagArticleRepository) {
            final List<JSONObject> rows = select(
                    "SELECT t.oId, t.tagTitle, (SELECT count(*) FROM " + articleRepository.getName()
                            + " AS a WHERE a.articleStatus = ? AND EXISTS (SELECT 1 FROM "
                            + tagArticleRepository.getName()
                            + " AS r WHERE r.article_oId = a.oId AND r.tag_oId = t.oId)) AS C FROM "
                            + getName() + " AS t WHERE t.tagTitle = ? LIMIT 1",
                    Article.ARTICLE_STATUS_C_PUBLISHED, tagTitle);
            if (null == rows || rows.isEmpty()) {
                return null;
            }
            final JSONObject ret = rows.get(0);
            ret.put(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT, ret.optInt("C"));
            ret.remove("C");
            return ret;
        }

        final Query query = new Query().setFilter(new PropertyFilter(Tag.TAG_TITLE, FilterOperator.EQUAL, tagTitle)).setPageCount(1);
        final JSONObject ret = getFirst(query);
        if (null == ret) {
            return null;
        }

        final String tagId = ret.optString(Keys.OBJECT_ID);
        final int articleCount = null == tagArticleRepository ? 0 : tagArticleRepository.getPublishedArticleCount(tagId);
        ret.put(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT, articleCount);

        return ret;
    }
}
