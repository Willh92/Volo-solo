/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import com.zaxxer.hikari.HikariDataSource;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.http.Dispatcher;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.function.Handler;
import org.b3log.latke.http.handler.RouteHandler;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Discoverer;
import org.b3log.latke.model.User;
import org.b3log.latke.plugin.PluginManager;
import org.b3log.latke.plugin.ViewLoadEventHandler;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Repositories;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.repository.h2.H2JdbcDatabaseSolution;
import org.b3log.latke.repository.jdbc.JdbcFactory;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.latke.repository.jdbc.util.JdbcRepositories;
import org.b3log.latke.service.LangPropsService;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Category;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Page;
import org.b3log.solo.model.Tag;
import org.b3log.solo.processor.ErrorProcessor;
import org.b3log.solo.processor.IndexNowKeyHandler;
import org.b3log.solo.processor.InitCheckHandler;
import org.b3log.solo.processor.PermalinkHandler;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.service.ArticleMgmtService;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.SkinMgmtService;
import org.b3log.latke.service.ServiceException;
import org.b3log.solo.web.PublicResourceHandler;
import org.b3log.solo.web.Routes;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * End-to-end full-stack HTTP routing & FreeMarker rendering regression suite exercising
 * {@link Dispatcher#handle(Request, Response)} against a real H2 database and all registered routes.
 */
public class PublicRoutingAndTemplatesTest {

    private static final String INDEXNOW_KEY = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Test
    public void fullDispatcherServesAllPublicListingCategoryArchiveTagPermalinkAndIndexNowRoutes() throws Exception {
        final List<Handler> savedHandlers = new ArrayList<>(Dispatcher.HANDLERS);
        final Handler savedStart = Dispatcher.startRequestHandler;
        final Handler savedEnd = Dispatcher.endRequestHandler;
        final Object savedErrorRouter = getStaticField(Dispatcher.class, "errorHandleRouter");
        final Map<Field, Map<Object, Object>> savedRouteMaps = snapshotRouteHandlerMaps();
        final boolean savedWaf = WAF.POWER;

        try {
            WAF.off();
            resetInitServiceFlags();
            clearRouteHandlerMaps();

            Latkes.setLocale(Locale.SIMPLIFIED_CHINESE);
            Latkes.setRuntimeMode(Latkes.RuntimeMode.PRODUCTION);
            final Properties latkeProps = new Properties();
            latkeProps.setProperty("serverScheme", "http");
            latkeProps.setProperty("serverHost", "localhost");
            latkeProps.setProperty("serverPort", "8080");
            latkeProps.setProperty("staticServerScheme", "http");
            latkeProps.setProperty("staticServerHost", "localhost");
            latkeProps.setProperty("staticServerPort", "8080");
            latkeProps.setProperty("contextPath", "");
            latkeProps.setProperty("staticPath", "");
            latkeProps.setProperty("runtimeMode", "PRODUCTION");
            latkeProps.setProperty("cookieName", "solo");
            latkeProps.setProperty("cookieSecret", "Beyond");
            Latkes.setLatkeProps(latkeProps);

            final String dbName = "volo_e2e_routing_" + System.nanoTime();
            final String jdbcUrl = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MYSQL";
            final Properties localProps = new Properties();
            localProps.setProperty("runtimeDatabase", "H2");
            localProps.setProperty("jdbc.driver", "org.h2.Driver");
            localProps.setProperty("jdbc.URL", jdbcUrl);
            localProps.setProperty("jdbc.username", "sa");
            localProps.setProperty("jdbc.password", "");
            localProps.setProperty("jdbc.minConnCnt", "2");
            localProps.setProperty("jdbc.maxConnCnt", "6");
            localProps.setProperty("jdbc.tablePrefix", "b3_solo");
            localProps.setProperty("cookieHttpOnly", "true");
            localProps.setProperty("loginTrustedProxies", "127.0.0.1,::1");
            localProps.setProperty("geoAccessEnabled", "false");
            Latkes.setLocalProps(localProps);
            Latkes.setContextPath("");
            Latkes.setScanPath("org.b3log.solo");
            ensureConnectionsPool(jdbcUrl);
            resetLatkeContainerAndRepositories(true);

            final Collection<Class<?>> classes = new ArrayList<>(Discoverer.discover("org.b3log.solo"));
            for (final Class<?> builtin : List.of(EventManager.class, LangPropsService.class, PluginManager.class)) {
                if (!classes.contains(builtin)) {
                    classes.add(builtin);
                }
            }

            BeanManager.start(classes);
            try {
                final BeanManager beanManager = BeanManager.getInstance();
                final SoloApplication app = new SoloApplication();
                setField(app, "beanManager", beanManager);

                Dispatcher.startRequestHandler = context -> {
                    context.getResponse().getCookies().clear();
                    app.requestInitialized(context.getRequest());
                };
                Dispatcher.endRequestHandler = context -> app.requestDestroyed(context.getRequest());

                Routes.HANDLERS.removeIf(handler -> handler instanceof org.b3log.latke.http.handler.StaticResourceHandler
                        || handler instanceof InitCheckHandler
                        || handler instanceof PublicResourceHandler
                        || handler instanceof PermalinkHandler
                        || handler instanceof IndexNowKeyHandler);
                Routes.HANDLERS.add(0, new IndexNowKeyHandler());
                Routes.HANDLERS.add(0, new PermalinkHandler());
                Routes.HANDLERS.add(0, new PublicResourceHandler());
                Routes.HANDLERS.add(0, new InitCheckHandler());

                SoloApplication.routeConsoleProcessors();
                Routes.mapping();
                Dispatcher.error("/error/{statusCode}",
                        beanManager.getReference(ErrorProcessor.class)::showErrorPage);

                beanManager.getReference(EventManager.class).registerListener(new ViewLoadEventHandler());

                final InitService initService = beanManager.getReference(InitService.class);
                initService.initTables();
                initService.init(new JSONObject().put(User.USER_NAME, "willh"));

                final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
                beanManager.getReference(SkinMgmtService.class).loadSkins(optionQueryService.getSkin());

                // Seed a category linked to the Hello World article, two custom pages, and enable IndexNow
                final ArticleRepository articleRepository = beanManager.getReference(ArticleRepository.class);
                final CategoryRepository categoryRepository = beanManager.getReference(CategoryRepository.class);
                final CategoryTagRepository categoryTagRepository = beanManager.getReference(CategoryTagRepository.class);
                final PageRepository pageRepository = beanManager.getReference(PageRepository.class);
                final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);

                final List<JSONObject> allArticles = articleRepository.getList(new Query());
                Assert.assertFalse(allArticles.isEmpty(), "InitService must seed Hello World article");
                final JSONObject seededArticle = allArticles.get(0);
                final String articleId = seededArticle.getString(Keys.OBJECT_ID);
                final String articlePermalink = seededArticle.getString(Article.ARTICLE_PERMALINK);
                final String archiveYearMonth = DateFormatUtils.format(
                        seededArticle.getLong(Article.ARTICLE_CREATED), "yyyy/MM");

                final Transaction tx = optionRepository.beginTransaction();
                try {
                    final String categoryId = "cat-tech-1";
                    categoryRepository.add(new JSONObject()
                            .put(Keys.OBJECT_ID, categoryId)
                            .put(Category.CATEGORY_TITLE, "技术专栏")
                            .put(Category.CATEGORY_URI, "tech")
                            .put(Category.CATEGORY_DESCRIPTION, "后端与架构笔记")
                            .put(Category.CATEGORY_ORDER, 1)
                            .put(Category.CATEGORY_TAG_CNT, 1));
                    categoryTagRepository.add(new JSONObject()
                            .put(Keys.OBJECT_ID, "ct-rel-1")
                            .put(Category.CATEGORY + "_" + Keys.OBJECT_ID, categoryId)
                            .put(Tag.TAG + "_" + Keys.OBJECT_ID, articleId));

                    pageRepository.add(new JSONObject()
                            .put(Keys.OBJECT_ID, "page-about-nav")
                            .put(Page.PAGE_TITLE, "关于本站")
                            .put(Page.PAGE_PERMALINK, "/about-custom.html")
                            .put(Page.PAGE_OPEN_TARGET, "_self")
                            .put(Page.PAGE_ORDER, 1)
                            .put(Page.PAGE_ICON, ""));
                    tx.commit();
                } catch (final Exception ex) {
                    if (tx.isActive()) {
                        tx.rollback();
                    }
                    throw ex;
                }
                JdbcRepository.dispose();

                // Publish articles through real ArticleMgmtService.addArticle across all custom permalink patterns:
                final ArticleMgmtService articleMgmtService = beanManager.getReference(ArticleMgmtService.class);
                final String authorId = seededArticle.optString(Article.ARTICLE_AUTHOR_ID);
                addPublishedArticle(articleMgmtService, authorId, "关于本站",
                        "/about-custom.html", "Custom About Content Body");
                addPublishedArticle(articleMgmtService, authorId, "Ads Verification",
                        "/ads.txt", "Custom Ads Content Body");
                addPublishedArticle(articleMgmtService, authorId, "我在 GitHub 上的开源项目",
                        "my-github-repos", "Custom GitHub Repos Body");
                addPublishedArticle(articleMgmtService, authorId, "Nginx 301跳转踩坑",
                        "  Nginx 301 跳转踩坑.html  ", "Custom Spaced Permalink Body");
                addPublishedArticle(articleMgmtService, authorId, "常用Smali语法",
                        "/常用Smali语法.html", "Custom Chinese Smali Body");
                addPublishedArticle(articleMgmtService, authorId, "C++20 协程笔记",
                        "/c++20-notes.html", "Custom Plus Permalink Body");
                addPublishedArticle(articleMgmtService, authorId, "Android 逆向记录",
                        "/[Android]-Smali.html", "Custom Bracket Permalink Body");

                // Duplicate article permalink must still be rejected
                Assert.expectThrows(ServiceException.class, () ->
                        addPublishedArticle(articleMgmtService, authorId, "Duplicate Slug",
                                "/my-github-repos", "Should Fail Duplicate"));

                final Transaction optTx = optionRepository.beginTransaction();
                try {
                    upsertOption(optionRepository, Option.ID_C_INDEXNOW_ENABLED, Option.CATEGORY_C_PREFERENCE, "true");
                    upsertOption(optionRepository, Option.ID_C_INDEXNOW_KEY, Option.CATEGORY_C_PREFERENCE, INDEXNOW_KEY);
                    optTx.commit();
                } catch (final Exception ex) {
                    if (optTx.isActive()) {
                        optTx.rollback();
                    }
                    throw ex;
                }
                JdbcRepository.dispose();

                // 1. Homepage
                final HttpResult home = performGet("/");
                Assert.assertEquals(home.status, 200, "Homepage must return 200");
                Assert.assertTrue(home.body.contains("willh 的个人博客"));

                // 2. Public UserTemplateProcessor (*.html) listing pages — the exact bug reported by user!
                final HttpResult archives = performGet("/archives.html");
                Assert.assertEquals(archives.status, 200, "/archives.html must return 200 (not hijacked by IndexNow)");
                Assert.assertFalse(archives.body.contains("这一页没有找到"));
                Assert.assertTrue(archives.body.contains("/archives/" + archiveYearMonth));

                final HttpResult categories = performGet("/categories.html");
                Assert.assertEquals(categories.status, 200, "/categories.html must return 200");
                Assert.assertFalse(categories.body.contains("这一页没有找到"));
                Assert.assertTrue(categories.body.contains("技术专栏"));

                // Cross-skin fallback between categories.ftl <-> category.ftl
                final HttpResult categorySingularOnStudio = performGet("/category.html");
                Assert.assertEquals(categorySingularOnStudio.status, 200,
                        "/category.html on volo-studio must fall back to categories.ftl");
                Assert.assertTrue(categorySingularOnStudio.body.contains("技术专栏"));

                final HttpResult categoriesPluralOnBubble = performGet("/categories.html?skin=bolo-bubble");
                Assert.assertEquals(categoriesPluralOnBubble.status, 200,
                        "/categories.html on bolo-bubble must fall back to category.ftl");
                Assert.assertTrue(categoriesPluralOnBubble.body.contains("技术专栏"));

                final HttpResult categoryOnFantastic = performGet("/category.html?skin=bolo-fantastic");
                Assert.assertEquals(categoryOnFantastic.status, 200,
                        "/category.html on bolo-fantastic must fall back to tags.ftl");
                Assert.assertTrue(categoryOnFantastic.body.contains("技术专栏"));

                for (final String nextUri : List.of(
                        "/categories.html?skin=bolo-next",
                        "/links.html?skin=bolo-next",
                        "/follows.html?skin=bolo-next")) {
                    final HttpResult res = performGet(nextUri);
                    Assert.assertEquals(res.status, 200, nextUri + " must return 200");
                    Assert.assertFalse(res.body.contains("这一页没有找到"), nextUri + " must not render 404 page");
                }

                for (final String pageUri : List.of("/tags.html", "/links.html", "/dynamic.html", "/follows.html")) {
                    final HttpResult res = performGet(pageUri);
                    Assert.assertEquals(res.status, 200, pageUri + " must return 200");
                    Assert.assertFalse(res.body.contains("这一页没有找到"), pageUri + " must not render 404 page");
                }

                // 3. Multi-segment Archive, Category, and Tag detail pages
                final HttpResult archiveMonth = performGet("/archives/" + archiveYearMonth);
                Assert.assertEquals(archiveMonth.status, 200, "/archives/" + archiveYearMonth + " must return 200");

                final HttpResult categoryDetail = performGet("/category/tech");
                Assert.assertEquals(categoryDetail.status, 200, "/category/tech must return 200");
                Assert.assertTrue(categoryDetail.body.contains("技术专栏"));

                final HttpResult tagDetail = performGet("/tags/Volo");
                Assert.assertEquals(tagDetail.status, 200, "/tags/Volo must return 200");
                Assert.assertTrue(tagDetail.body.contains("Volo"));

                // 4. Seeded default article permalink & all custom article permalink forms
                final HttpResult articleRes = performGet(articlePermalink);
                Assert.assertEquals(articleRes.status, 200, "Seeded article permalink must return 200");

                final HttpResult customHtmlPage = performGet("/about-custom.html");
                Assert.assertEquals(customHtmlPage.status, 200,
                        "Custom .html article permalink must win over UserTemplateProcessor");
                Assert.assertTrue(customHtmlPage.body.contains("Custom About Content Body"));

                final HttpResult customExtensionless = performGet("/my-github-repos");
                Assert.assertEquals(customExtensionless.status, 200,
                        "Extensionless custom article permalink must return 200");
                Assert.assertTrue(customExtensionless.body.contains("Custom GitHub Repos Body"));

                final HttpResult customSpaced = performGet("/Nginx-301-%E8%B7%B3%E8%BD%AC%E8%B8%A9%E5%9D%91.html");
                Assert.assertEquals(customSpaced.status, 200,
                        "Custom permalink with spaces normalized to hyphens must return 200");
                Assert.assertTrue(customSpaced.body.contains("Custom Spaced Permalink Body"));

                final HttpResult customChineseEncoded = performGet("/%E5%B8%B8%E7%94%A8Smali%E8%AF%AD%E6%B3%95.html");
                Assert.assertEquals(customChineseEncoded.status, 200,
                        "Percent-encoded UTF-8 Chinese custom article permalink must return 200");
                Assert.assertTrue(customChineseEncoded.body.contains("Custom Chinese Smali Body"));

                final HttpResult customChineseRaw = performGet("/常用Smali语法.html");
                Assert.assertEquals(customChineseRaw.status, 200,
                        "Proxy-decoded UTF-8 Chinese custom article permalink must return 200");
                Assert.assertTrue(customChineseRaw.body.contains("Custom Chinese Smali Body"));

                for (final String plusUri : List.of("/c++20-notes.html", "/c%2B%2B20-notes.html")) {
                    final HttpResult customPlus = performGet(plusUri);
                    Assert.assertEquals(customPlus.status, 200, plusUri + " must return 200");
                    Assert.assertTrue(customPlus.body.contains("Custom Plus Permalink Body"));
                }

                for (final String bracketUri : List.of("/%5BAndroid%5D-Smali.html", "/[Android]-Smali.html")) {
                    final HttpResult customBracket = performGet(bracketUri);
                    Assert.assertEquals(customBracket.status, 200, bracketUri + " must return 200");
                    Assert.assertTrue(customBracket.body.contains("Custom Bracket Permalink Body"));
                }

                final HttpResult customTxtPage = performGet("/ads.txt");
                Assert.assertEquals(customTxtPage.status, 200,
                        "Custom /ads.txt article must be served by PermalinkHandler before IndexNowKeyHandler");
                Assert.assertTrue(customTxtPage.body.contains("Custom Ads Content Body"));

                // 5. IndexNow key verification endpoint & negative cases
                final HttpResult validIndexNow = performGet("/" + INDEXNOW_KEY + ".txt");
                Assert.assertEquals(validIndexNow.status, 200, "Configured IndexNow key file must return 200");
                Assert.assertTrue(String.valueOf(validIndexNow.contentType).startsWith("text/plain"));
                Assert.assertEquals(validIndexNow.body, INDEXNOW_KEY);

                final HttpResult invalidIndexNow = performGet("/wrongkey12345678.txt");
                Assert.assertEquals(invalidIndexNow.status, 404, "Mismatched .txt key must return 404");

                // 6. Missing templates & missing literal suffixes must strictly return 404
                Assert.assertEquals(performGet("/nonexistent-template-xyz.html").status, 404);
                Assert.assertEquals(performGet("/archives").status, 404,
                        "Single-segment /archives without .html suffix must return 404");
                Assert.assertEquals(performGet("/github-stats/willh").status, 404,
                        "/github-stats/{username} without .svg suffix must return 404");
            } finally {
                JdbcRepository.dispose();
                BeanManager.close();
            }
        } finally {
            shutdownConnectionsPool();
            WAF.POWER = savedWaf;
            PublicResourceHandler.clearCache();
            Dispatcher.HANDLERS.clear();
            Dispatcher.HANDLERS.addAll(savedHandlers);
            Dispatcher.startRequestHandler = savedStart;
            Dispatcher.endRequestHandler = savedEnd;
            setStaticField(Dispatcher.class, "errorHandleRouter", savedErrorRouter);
            restoreRouteHandlerMaps(savedRouteMaps);
            resetInitServiceFlags();
            LatkeTestSupport.configure();
            final Properties noneProps = new Properties();
            noneProps.setProperty("runtimeDatabase", "NONE");
            noneProps.setProperty("cookieHttpOnly", "true");
            noneProps.setProperty("loginTrustedProxies", "127.0.0.1,::1");
            Latkes.setLocalProps(noneProps);
            resetLatkeContainerAndRepositories(false);
        }
    }

    private record HttpResult(int status, String contentType, String body) {
    }

    private static void addPublishedArticle(final ArticleMgmtService articleMgmtService,
                                            final String authorId,
                                            final String title,
                                            final String customPermalink,
                                            final String content) throws Exception {
        final JSONObject article = new JSONObject()
                .put(Article.ARTICLE_AUTHOR_ID, authorId)
                .put(Article.ARTICLE_TITLE, title)
                .put(Article.ARTICLE_ABSTRACT, content)
                .put(Article.ARTICLE_CONTENT, content)
                .put(Article.ARTICLE_TAGS_REF, "Volo")
                .put(Article.ARTICLE_PERMALINK, customPermalink)
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_COMMENTABLE, true)
                .put(Article.ARTICLE_VIEW_PWD, "");
        articleMgmtService.addArticle(new JSONObject().put(Article.ARTICLE, article));
        JdbcRepository.dispose();
    }

    private static HttpResult performGet(final String uriWithOptionalQuery) throws Exception {
        final DefaultFullHttpRequest httpReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, uriWithOptionalQuery);
        httpReq.headers().set(HttpHeaderNames.HOST, "localhost:8080");
        httpReq.headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 VoloIntegrationTest");
        httpReq.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final DefaultFullHttpResponse httpResp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        FullHttpResponse actualResp = httpResp;
        try {
            final Request request = new Request(null, httpReq) {
                @Override
                public String getRemoteAddr() {
                    return "127.0.0.1";
                }
            };
            final Response response = new Response(null, httpResp);
            Dispatcher.handle(request, response);

            final Field resField = Response.class.getDeclaredField("res");
            resField.setAccessible(true);
            final Object replaced = resField.get(response);
            if (replaced instanceof FullHttpResponse full) {
                actualResp = full;
            }

            final int status = actualResp.status().code();
            final String contentType = actualResp.headers().get(HttpHeaderNames.CONTENT_TYPE);
            byte[] rawBytes = response.getBytes();
            if (rawBytes == null) {
                final ByteBuf buf = actualResp.content();
                rawBytes = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), rawBytes);
            }

            final String body;
            if ("gzip".equalsIgnoreCase(actualResp.headers().get(HttpHeaderNames.CONTENT_ENCODING))
                    && rawBytes.length > 0) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(rawBytes))) {
                    body = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                body = new String(rawBytes, StandardCharsets.UTF_8);
            }
            return new HttpResult(status, contentType, body);
        } finally {
            httpReq.release();
            if (actualResp != httpResp && actualResp.refCnt() > 0) {
                actualResp.release();
            }
            if (httpResp.refCnt() > 0) {
                httpResp.release();
            }
        }
    }

    private static void ensureConnectionsPool(final String jdbcUrl) throws Exception {
        shutdownConnectionsPool();
        final HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setMinimumIdle(2);
        ds.setMaximumPoolSize(6);
        ds.setValidationTimeout(2000L);
        ds.setConnectionTimeout(2000L);

        setStaticField(Connections.class, "hikari", ds);
        setStaticField(Connections.class, "transactionIsolationInt", Connection.TRANSACTION_READ_COMMITTED);
        setStaticField(Connections.class, "url", jdbcUrl);
        setStaticField(Connections.class, "userName", "sa");
        setStaticField(Connections.class, "password", "");
    }

    @SuppressWarnings("unchecked")
    private static void resetLatkeContainerAndRepositories(final boolean prepareH2Solution) throws Exception {
        final BeanManager bm = BeanManager.getInstance();
        final Set<Object> builtInBeans = (Set<Object>) getStaticField(BeanManager.class, "builtInBeans");
        final Field beansField = BeanManager.class.getDeclaredField("beans");
        beansField.setAccessible(true);
        final Set<Object> beans = (Set<Object>) beansField.get(bm);
        beans.retainAll(builtInBeans);

        final Field contextField = BeanManager.class.getDeclaredField("context");
        contextField.setAccessible(true);
        final Object singletonContext = contextField.get(bm);
        final Field refsField = singletonContext.getClass().getDeclaredField("beanReferences");
        refsField.setAccessible(true);
        final Map<Object, Object> beanRefs = (Map<Object, Object>) refsField.get(singletonContext);
        beanRefs.keySet().retainAll(builtInBeans);

        final Map<String, Object> reposHolder = (Map<String, Object>) getStaticField(Repositories.class, "REPOS_HOLDER");
        reposHolder.clear();

        final Method loadRepoDesc = Repositories.class.getDeclaredMethod("loadRepositoryDescription");
        loadRepoDesc.setAccessible(true);
        loadRepoDesc.invoke(null);
        JdbcRepositories.setRepositoryDefinitions(null);

        setField(JdbcFactory.getInstance(), "databaseSolution",
                prepareH2Solution ? new H2JdbcDatabaseSolution() : null);

        for (final String cachedRefField : List.of(
                "cachedInitService",
                "cachedArticleRepository",
                "cachedShareTokenService",
                "cachedOptionQueryService",
                "cachedArticleQueryService")) {
            setStaticField(PermalinkHandler.class, cachedRefField, null);
        }
    }

    private static void shutdownConnectionsPool() throws Exception {
        Connections.shutdownConnectionPool();
        setStaticField(Connections.class, "hikari", null);
    }

    private static Object getStaticField(final Class<?> clazz, final String name) throws Exception {
        final Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setStaticField(final Class<?> clazz, final String name, final Object value) throws Exception {
        final Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void upsertOption(final OptionRepository repo,
                                     final String id,
                                     final String category,
                                     final String value) throws Exception {
        final JSONObject existing = repo.get(id);
        if (existing != null) {
            existing.put(Option.OPTION_VALUE, value);
            repo.update(id, existing);
        } else {
            repo.add(new JSONObject()
                    .put(Keys.OBJECT_ID, id)
                    .put(Option.OPTION_CATEGORY, category)
                    .put(Option.OPTION_VALUE, value));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Field, Map<Object, Object>> snapshotRouteHandlerMaps() throws Exception {
        final Map<Field, Map<Object, Object>> snapshot = new LinkedHashMap<>();
        for (final Field field : RouteHandler.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                final Map<Object, Object> map = (Map<Object, Object>) field.get(null);
                if (map != null) {
                    snapshot.put(field, new LinkedHashMap<>(map));
                }
            }
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static void clearRouteHandlerMaps() throws Exception {
        for (final Field field : RouteHandler.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                final Map<Object, Object> map = (Map<Object, Object>) field.get(null);
                if (map != null) {
                    map.clear();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreRouteHandlerMaps(final Map<Field, Map<Object, Object>> snapshot) throws Exception {
        for (final Map.Entry<Field, Map<Object, Object>> entry : snapshot.entrySet()) {
            final Map<Object, Object> map = (Map<Object, Object>) entry.getKey().get(null);
            if (map != null) {
                map.clear();
                map.putAll(entry.getValue());
            }
        }
    }

    private static void resetInitServiceFlags() throws Exception {
        for (final String fieldName : List.of("inited", "printedInitMsg")) {
            final Field f = InitService.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.setBoolean(null, false);
        }
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
