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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo.security;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArchiveDateArticleRepository;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.service.ArticleQueryService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ArticleSelectionTest {
    @Test(timeOut = 10000)
    public void randomSelectionWrapsWithoutLosingPublishedArticles() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int i = 0; i < 20; i++) {
                final int before = fixture.reads.get();
                final List<JSONObject> articles = fixture.articles.getRandomly(10);
                Assert.assertEquals(ids(articles), Set.of("001", "002", "003"));
                Assert.assertEquals(articles.size(), 3, "The two random ranges must not overlap");
                Assert.assertTrue(fixture.reads.get() - before <= 2);
            }
            Assert.assertTrue(fixture.articles.getRandomly(0).isEmpty());
            Assert.assertTrue(fixture.articles.getRandomly(-1).isEmpty());
            Assert.assertEquals(fixture.articles.getRandomly(1).size(), 1);
        }
    }

    @Test
    public void tagPaginationFiltersDraftsAndDuplicateRelationsBeforeLimiting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Assert.assertEquals(fixture.tags.getPublishedArticleCount("shared"), 3);
            final JSONObject first = fixture.service.getArticlesByTag("shared", 1, 2);
            Assert.assertEquals(ids(rows(first)), Set.of("003", "002"));
            Assert.assertEquals(first.getJSONObject(Pagination.PAGINATION).getInt(Pagination.PAGINATION_PAGE_COUNT), 2);
            Assert.assertEquals(ids(rows(fixture.service.getArticlesByTag("shared", 2, 2))), Set.of("001"));
            Assert.assertEquals(fixture.tags.getPublishedArticleCount("missing' OR 1=1 --"), 0);
        }
    }

    @Test
    public void archivesUseTheConfiguredTablesAndKeepArticleIds() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Assert.assertEquals(fixture.archives.getPublishedArticleCount("month'x"), 3);
            final List<JSONObject> articles = fixture.service.getArticlesByArchiveDate("month'x", 1, 10);
            Assert.assertEquals(ids(articles), Set.of("001", "002", "003"));
            Assert.assertEquals(articles.size(), 3);
            Assert.assertEquals(fixture.archives.getPublishedArticleCount("missing' OR 1=1 --"), 0);
        }
    }

    @Test
    public void publicMonthsCombineDuplicateArchiveRecordsAndHideDraftOnlyMonths() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final JSONObject october = fixture.months.getByArchiveDate("2026/10");
            Assert.assertNotNull(october);
            Assert.assertEquals(new HashSet<>(fixture.months.getMonthArchiveIds(october.getString(Keys.OBJECT_ID))), Set.of("month'x", "duplicate"));
            Assert.assertEquals(fixture.months.getPublishedArchiveDates().size(), 1);
            Assert.assertEquals(fixture.months.getPublishedArchiveDates().get(0).getInt("archiveDatePublishedArticleCount"), 3);
            Assert.assertEquals(ids(fixture.service.getArticlesByArchiveDate("duplicate", 1, 10)), Set.of("001", "002", "003"));
            Assert.assertNull(fixture.months.getByArchiveDate("2026/13"));
            Assert.assertNull(fixture.months.getByArchiveDate("2026/10/trailing"));
        }
    }

    @Test
    public void popularTagsIgnoreDraftsMissingRecordsAndDuplicateLinks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final List<JSONObject> tags = fixture.tags.getMostUsedTags(5);
            Assert.assertEquals(tags.size(), 1);
            Assert.assertEquals(tags.get(0).getString("tagTitle"), "shared");
            Assert.assertEquals(tags.get(0).getInt("tagPublishedRefCount"), 3);
            Assert.assertTrue(fixture.tags.getMostUsedTags(0).isEmpty());
        }
    }

    @Test
    public void publicPaginationOffersTheLastPageButNeverAnExtraPage() throws Exception {
        for (final Class<?> type : List.of(org.b3log.solo.processor.TagProcessor.class,
                org.b3log.solo.processor.CategoryProcessor.class)) {
            final Object processor = type.getDeclaredConstructor().newInstance();
            final boolean category = type == org.b3log.solo.processor.CategoryProcessor.class;
            final var fill = category
                    ? type.getDeclaredMethod("fillPagination", java.util.Map.class, int.class, int.class, List.class, List.class, String.class)
                    : type.getDeclaredMethod("fillPagination", java.util.Map.class, int.class, int.class, List.class, List.class);
            fill.setAccessible(true);
            final java.util.Map<String,Object> data = new java.util.HashMap<>();
            fill.invoke(processor, category ? new Object[]{data, 2, 1, List.of(), List.of(1,2), "test"} : new Object[]{data, 2, 1, List.of(), List.of(1,2)});
            Assert.assertEquals(data.get(Pagination.PAGINATION_NEXT_PAGE_NUM), 2);
            fill.invoke(processor, category ? new Object[]{data, 2, 2, List.of(), List.of(1,2), "test"} : new Object[]{data, 2, 2, List.of(), List.of(1,2)});
            Assert.assertEquals(data.get(Pagination.PAGINATION_NEXT_PAGE_NUM), "");
            fill.invoke(processor, category ? new Object[]{data, 0, 1, List.of(), List.of(), "test"} : new Object[]{data, 0, 1, List.of(), List.of()});
            Assert.assertEquals(data.get(Pagination.PAGINATION_NEXT_PAGE_NUM), "");
            Assert.assertEquals(data.get(Pagination.PAGINATION_FIRST_PAGE_NUM), 1);
        }
    }

    @Test(timeOut = 10000)
    public void concurrentArchiveRequestsDoNotShareDeduplicationState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final var pool = Executors.newFixedThreadPool(4);
            try {
                final List<java.util.concurrent.Future<Set<String>>> requests = new ArrayList<>();
                for (int i = 0; i < 16; i++) requests.add(pool.submit(() ->
                        ids(fixture.service.getArticlesByArchiveDate("month'x", 1, 10))));
                for (final var request : requests) Assert.assertEquals(request.get(5, TimeUnit.SECONDS), Set.of("001", "002", "003"));
            } finally { pool.shutdownNow(); }
        }
    }

    @Test
    public void relatedSelectionUsesOneQueryAndOnlyReturnsPublicLinks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final JSONObject article = new JSONObject().put(Keys.OBJECT_ID, "001")
                    .put(Article.ARTICLE_TAGS_REF, "shared, missing' OR 1=1 --");
            final JSONObject preference = new JSONObject().put(Option.ID_C_RELEVANT_ARTICLES_DISPLAY_CNT, 2);
            final int before = fixture.reads.get();
            final List<JSONObject> related = fixture.service.getRelevantArticles(article, preference);
            Assert.assertEquals(related.size(), 2);
            Assert.assertEquals(fixture.reads.get() - before, 1, "Recommendations should not query every candidate separately");
            final Set<String> urls = new HashSet<>();
            for (final JSONObject item : related) {
                urls.add(item.getString(Article.ARTICLE_PERMALINK));
                Assert.assertEquals(item.keySet(), Set.of(Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK));
            }
            Assert.assertEquals(urls, Set.of("/2", "/3"));
            preference.put(Option.ID_C_RELEVANT_ARTICLES_DISPLAY_CNT, 0);
            Assert.assertTrue(fixture.service.getRelevantArticles(article, preference).isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<JSONObject> rows(final JSONObject result) { return (List<JSONObject>) result.opt(Keys.RESULTS); }
    private static Set<String> ids(final List<JSONObject> rows) {
        final Set<String> ids = new HashSet<>();
        for (final JSONObject row : rows) ids.add(row.getString(Keys.OBJECT_ID));
        return ids;
    }
    private static void inject(final Object bean, final String name, final Object value) throws Exception {
        Class<?> type = bean.getClass();
        while (type != null) {
            try { final Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(bean, value); return; }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    /** Executes the production SQL against isolated H2 tables with a non-default prefix. */
    private static final class Fixture implements AutoCloseable {
        final String url = "jdbc:h2:mem:selection_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false";
        final Connection keepAlive;
        final AtomicInteger reads = new AtomicInteger();
        final ArticleRepository articles;
        final TagArticleRepository tags;
        final ArchiveDateArticleRepository archives;
        final ArchiveDateRepository months;
        final ArticleQueryService service = new ArticleQueryService();
        Fixture() throws Exception {
            LatkeTestSupport.configure();
            final Properties local = new Properties(); local.setProperty("runtimeDatabase", "NONE"); Latkes.setLocalProps(local);
            keepAlive = DriverManager.getConnection(url);
            try (var statement = keepAlive.createStatement()) {
                statement.execute("CREATE TABLE check_article (oId VARCHAR PRIMARY KEY, articleStatus INT, articleRandomDouble DOUBLE, articleTitle VARCHAR, articlePermalink VARCHAR, articleCreated BIGINT, articleUpdated BIGINT)");
                statement.execute("CREATE TABLE check_tag (oId VARCHAR PRIMARY KEY, tagTitle VARCHAR)");
                statement.execute("CREATE TABLE check_tag_article (oId VARCHAR PRIMARY KEY, tag_oId VARCHAR, article_oId VARCHAR)");
                statement.execute("CREATE TABLE check_archivedate_article (oId VARCHAR PRIMARY KEY, archiveDate_oId VARCHAR, article_oId VARCHAR)");
                statement.execute("CREATE TABLE check_archivedate (oId VARCHAR PRIMARY KEY, archiveTime BIGINT)");
                statement.execute("INSERT INTO check_article VALUES ('001',0,0.99,'One','/1',1,1),('002',0,0.995,'Two','/2',2,2),('003',0,NULL,'Three','/3',3,3),('020',1,0.5,'Draft','/draft',20,20)");
                statement.execute("INSERT INTO check_tag VALUES ('shared','shared'),('draft','Draft only')");
                statement.execute("INSERT INTO check_tag_article VALUES ('r1','shared','001'),('r2','shared','002'),('r3','shared','003'),('r4','shared','002'),('r8','shared','absent'),('r9','shared','020'),('r10','draft','020'),('r11','absent','001')");
            }
            try (var statement = keepAlive.prepareStatement("INSERT INTO check_archivedate VALUES (?, ?)")) {
                for (final String[] row : new String[][]{{"month'x","2026-10-01"},{"duplicate","2026-10-02"},{"draft-only","2026-09-01"}}) {
                    statement.setString(1,row[0]); statement.setLong(2,java.time.LocalDate.parse(row[1]).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()); statement.executeUpdate();
                }
            }
            try (var statement = keepAlive.prepareStatement("INSERT INTO check_archivedate_article VALUES (?, ?, ?)")) {
                for (final String[] row : new String[][]{{"r1","001"},{"r2","002"},{"r3","003"},{"r4","002"},{"r8","absent"},{"r9","020"}}) {
                    statement.setString(1,row[0]); statement.setString(2,"month'x"); statement.setString(3,row[1]); statement.executeUpdate();
                }
            }
            try (var statement = keepAlive.createStatement()) {
                statement.execute("INSERT INTO check_archivedate_article VALUES ('r10','duplicate','002'),('r11','draft-only','020')");
            }
            articles = new ArticleRepository() {
                @Override public String getName() { return "check_article"; }
                @Override public List<JSONObject> select(String sql, Object... params) throws RepositoryException { return query(sql,params); }
            };
            final TagRepository tagRepository = new TagRepository() {
                @Override public String getName() { return "check_tag"; }
                @Override public List<JSONObject> select(String sql, Object... params) throws RepositoryException { return query(sql,params); }
            };
            tags = new TagArticleRepository() {
                @Override public String getName() { return "check_tag_article"; }
                @Override protected TagRepository getTagRepository() { return tagRepository; }
                @Override public List<JSONObject> select(String sql, Object... params) throws RepositoryException { return query(sql,params); }
            };
            archives = new ArchiveDateArticleRepository() {
                @Override public String getName() { return "check_archivedate_article"; }
                @Override public List<JSONObject> select(String sql, Object... params) throws RepositoryException { return query(sql,params); }
            };
            months = new ArchiveDateRepository() {
                @Override public String getName() { return "check_archivedate"; }
                @Override public List<JSONObject> select(String sql, Object... params) throws RepositoryException { return query(sql,params); }
            };
            inject(months,"archiveDateArticleRepository",archives);
            inject(tags,"articleRepository",articles); inject(archives,"articleRepository",articles);
            inject(service,"articleRepository",articles); inject(service,"tagArticleRepository",tags); inject(service,"archiveDateArticleRepository",archives); inject(service,"archiveDateRepository",months);
            inject(service,"tagRepository",tagRepository);
        }
        List<JSONObject> query(String sql, Object... params) throws RepositoryException {
            reads.incrementAndGet();
            try (Connection connection = DriverManager.getConnection(url); PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i=0;i<params.length;i++) statement.setObject(i+1,params[i]);
                final List<JSONObject> rows = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final JSONObject row = new JSONObject();
                        for (int i=1;i<=result.getMetaData().getColumnCount();i++) row.put(result.getMetaData().getColumnLabel(i),result.getObject(i));
                        rows.add(row);
                    }
                }
                return rows;
            } catch (Exception error) { throw new RepositoryException(error); }
        }
        @Override public void close() throws Exception { keepAlive.close(); LatkeTestSupport.configure(); }
    }
}
