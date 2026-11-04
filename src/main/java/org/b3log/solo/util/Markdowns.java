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
package org.b3log.solo.util;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.util.Callstacks;
import org.b3log.latke.util.Stopwatchs;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeVisitor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <a href="http://en.wikipedia.org/wiki/Markdown">Markdown</a> utilities.
 * <p>
 * Uses the <a href="https://github.com/b3log/markdown-http">markdown-http</a> as the processor, if not found this command, try
 * built-in <a href="https://github.com/vsch/flexmark-java">flexmark</a> instead.
 * </p>
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.5
 */
public final class Markdowns {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Markdowns.class);

    private static final int MAX_MD_CACHE_ENTRIES = 512;
    private static final int MAX_CACHEABLE_MD_LENGTH = 65536;

    /**
     * Bounded LRU Markdown cache.
     */
    private static final Map<String, String> MD_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_MD_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
                    return size() > MAX_MD_CACHE_ENTRIES;
                }
            });

    /**
     * Shared bounded daemon pool for Markdown rendering.
     */
    private static final AtomicInteger RENDER_THREAD_SEQ = new AtomicInteger(1);
    private static final ExecutorService RENDER_POOL = createRenderPool();

    private static ExecutorService createRenderPool() {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,
                Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors())),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                runnable -> {
                    final Thread thread = new Thread(runnable, "volo-md-render-" + RENDER_THREAD_SEQ.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static final Safelist SAFE_HTML_WHITELIST = Safelist.relaxed()
            .preserveRelativeLinks(true)
            .addAttributes("a", "target", "rel")
            .addAttributes("pre", "class")
            .addAttributes("div", "class", "data-code")
            .addAttributes("span", "class")
            .addAttributes("code", "class")
            .addAttributes("img", "class", "alt", "title")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            .addTags("input")
            .addAttributes("input", "type", "checked", "disabled");

    /**
     * Markdown to HTML timeout.
     */
    private static final int MD_TIMEOUT = 10000;

    /**
     * Built-in MD engine options.
     */
    private static final DataHolder OPTIONS = new MutableDataSet().
            set(com.vladsch.flexmark.parser.Parser.EXTENSIONS, Arrays.asList(
                    TablesExtension.create(),
                    TaskListExtension.create(),
                    StrikethroughExtension.create(),
                    AutolinkExtension.create())).
            set(HtmlRenderer.SOFT_BREAK, "<br />\n");

    /**
     * Built-in MD engine parser.
     */
    private static final com.vladsch.flexmark.parser.Parser PARSER =
            com.vladsch.flexmark.parser.Parser.builder(OPTIONS).build();

    /**
     * Built-in MD engine HTML renderer.
     */
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder(OPTIONS).build();

    /**
     * Lute engine serve path. https://github.com/b3log/lute-http
     */
    public static String LUTE_ENGINE_URL = "http://localhost:8249";

    /**
     * Whether Lute is available.
     */
    public static boolean LUTE_AVAILABLE = false;

    public static boolean SHOW_CODE_BLOCK_LN = false;
    public static boolean FOOTNOTES = true;
    public static boolean SHOW_TOC = true;
    public static boolean AUTO_SPACE = false;
    public static boolean FIX_TERM_TYPO = false;
    public static boolean CHINESE_PUNCT = false;
    public static boolean IMADAOM = false;
    public static boolean PARAGRAPH_BEGINNING_SPACE = false;

    /**
     * Lute status
     */
    private static boolean LUTE_OK = false;

    /**
     * Private constructor.
     */
    private Markdowns() {
    }

    /**
     * Clears cache.
     */
    public static void clearCache() {
        MD_CACHE.clear();
    }

    /**
     * Cleans the specified HTML.
     *
     * @param html the specified HTML
     * @return html
     */
    public static String clean(final String html) {
        if (StringUtils.isEmpty(html)) {
            return "";
        }
        final Document.OutputSettings outputSettings = new Document.OutputSettings();
        outputSettings.prettyPrint(false);
        final String baseUri = StringUtils.defaultIfBlank(Latkes.getServePath(), "https://localhost");
        return Jsoup.clean(html, baseUri, SAFE_HTML_WHITELIST, outputSettings);
    }

    private static boolean isSafeLinkHref(final String rawHref) {
        if (rawHref == null) return false;
        final String href = rawHref.trim();
        if (href.isEmpty()) return true;
        for (int i = 0; i < href.length(); i++) {
            if (Character.isISOControl(href.charAt(i))) return false;
        }
        if (href.startsWith("#")) return true;
        if (href.startsWith("//")) return false;
        final int colon = href.indexOf(':');
        if (colon < 0) return true;
        final int slash = href.indexOf('/');
        final int q = href.indexOf('?');
        final int hash = href.indexOf('#');
        if ((slash >= 0 && slash < colon) || (q >= 0 && q < colon) || (hash >= 0 && hash < colon)) {
            return true;
        }
        final String scheme = href.substring(0, colon).toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme);
    }

    private static boolean isSafeImageSrc(final String rawSrc) {
        if (rawSrc == null) return false;
        final String src = rawSrc.trim();
        if (src.isEmpty() || src.startsWith("//")) return false;
        for (int i = 0; i < src.length(); i++) {
            if (Character.isISOControl(src.charAt(i))) return false;
        }
        final int colon = src.indexOf(':');
        if (colon < 0) return true;
        final int slash = src.indexOf('/');
        final int q = src.indexOf('?');
        final int hash = src.indexOf('#');
        if ((slash >= 0 && slash < colon) || (q >= 0 && q < colon) || (hash >= 0 && hash < colon)) {
            return true;
        }
        final String scheme = src.substring(0, colon).toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    /**
     * Converts the specified markdown text to HTML.
     *
     * @param markdownText the specified markdown text
     * @return converted HTML, returns an empty string "" if the specified markdown text is "" or {@code null}, returns
     * 'markdownErrorLabel' if exception
     */
    public static String toHTML(final String markdownText) {
        if (StringUtils.isBlank(markdownText)) {
            return "";
        }

        final String cacheKey = markdownText.length() <= MAX_CACHEABLE_MD_LENGTH
                ? DigestUtils.sha256Hex(markdownText) : null;
        if (null != cacheKey) {
            final String cachedHTML = MD_CACHE.get(cacheKey);
            if (null != cachedHTML) {
                return cachedHTML;
            }
        }

        final Callable<String> call = () -> {
            String html = null;
            if (LUTE_AVAILABLE) {
                try {
                    html = toHtmlByLute(markdownText);
                    if (!LUTE_OK) {
                        LOGGER.log(Level.INFO, "Lute-HTTP To HTML successful.");
                        LUTE_OK = true;
                    } else {
                        LOGGER.log(Level.DEBUG, "Lute-HTTP To HTML successful.");
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.WARN, "Failed to use [Lute] for markdown, Using FlexMark Instead [md=" + StringUtils.substring(markdownText, 0, 256) + "]: " + e.getMessage());
                }
            }

            if (StringUtils.isBlank(html)) {
                html = toHtmlByFlexmark(markdownText);
            }

            if (!StringUtils.startsWith(html, "<p>")) {
                html = "<p>" + html + "</p>";
            }

            final Document doc = Jsoup.parseBodyFragment(html);
            doc.select("a").forEach(a -> {
                final String src = a.attr("href");
                if (a.hasAttr("href") && !isSafeLinkHref(src)) {
                    a.removeAttr("href");
                } else if (a.hasAttr("href") && !StringUtils.startsWithIgnoreCase(src, Latkes.getServePath()) && !StringUtils.startsWithIgnoreCase(src, "#")) {
                    a.attr("target", "_blank");
                    a.attr("rel", "noopener noreferrer");
                }
                a.removeAttr("id");
            });
            doc.select("img[src]").forEach(img -> {
                if (!isSafeImageSrc(img.attr("src"))) {
                    img.removeAttr("src");
                }
            });

            final List<Node> toRemove = new ArrayList<>();
            doc.traverse(new NodeVisitor() {
                @Override
                public void head(final org.jsoup.nodes.Node node, final int depth) {
                    if (node instanceof org.jsoup.nodes.TextNode) {
                        final org.jsoup.nodes.TextNode textNode = (org.jsoup.nodes.TextNode) node;
                        final org.jsoup.nodes.Node parent = textNode.parent();

                        if (parent instanceof Element) {
                            final Element parentElem = (Element) parent;
                            if (parentElem.tagName().equals("code") || parentElem.tagName().equals("pre")) {
                                return;
                            }

                            if (parentElem.tagName().equals("span") && StringUtils.startsWithIgnoreCase(parentElem.attr("class"), "hljs")) {
                                return;
                            }

                            String text = textNode.getWholeText();
                            text = Emotions.convert(text);
                            if (text.contains("@<a href=") || text.contains("<img")) {
                                final List<org.jsoup.nodes.Node> nodes = Parser.parseFragment(text, parentElem, "");
                                final int index = textNode.siblingIndex();
                                parentElem.insertChildren(index, nodes);
                                toRemove.add(node);
                            } else {
                                textNode.text(text);
                            }
                        }
                    }
                }

                @Override
                public void tail(final org.jsoup.nodes.Node node, final int depth) {
                }
            });

            toRemove.forEach(Node::remove);

            doc.outputSettings().prettyPrint(false);

            String ret = doc.body().html();
            ret = StringUtils.trim(ret);
            ret = Images.qiniuImgProcessing(ret);

            if (null != cacheKey && ret.length() <= MAX_CACHEABLE_MD_LENGTH * 4) {
                MD_CACHE.put(cacheKey, ret);
            }

            return ret;
        };

        Stopwatchs.start("Md to HTML");
        Future<String> future = null;
        try {
            future = RENDER_POOL.submit(call);

            return future.get(MD_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            if (null != future) {
                future.cancel(true);
            }
            LOGGER.log(Level.ERROR, "Markdown timeout [length=" + markdownText.length() + "]");
            Callstacks.printCallstack(Level.ERROR, new String[]{"org.b3log"}, null);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Markdown failed [length=" + markdownText.length() + "]", e);
        } finally {
            Stopwatchs.end();
        }

        try {
            return BeanManager.getInstance().getReference(LangPropsService.class).get("contentRenderFailedLabel");
        } catch (final Exception ignored) {
            return "Content render failed";
        }
    }

    public static String toHtmlByLute(final String markdownText) throws Exception {
        if (!OutboundUrls.isHttpOrHttps(LUTE_ENGINE_URL)) {
            throw new IllegalArgumentException("Invalid Lute service URL");
        }
        final URL url = new URL(LUTE_ENGINE_URL);
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("X-CodeSyntaxHighlightLineNum", String.valueOf(Markdowns.SHOW_CODE_BLOCK_LN));
        conn.setRequestProperty("X-Footnotes", String.valueOf(Markdowns.FOOTNOTES));
        conn.setRequestProperty("X-ToC", String.valueOf(Markdowns.SHOW_TOC));
        conn.setRequestProperty("X-AutoSpace", String.valueOf(Markdowns.AUTO_SPACE));
        conn.setRequestProperty("X-FixTermTypo", String.valueOf(Markdowns.FIX_TERM_TYPO));
        conn.setRequestProperty("X-ChinesePunct", String.valueOf(Markdowns.CHINESE_PUNCT));
        conn.setRequestProperty("X-IMADAOM", String.valueOf(Markdowns.IMADAOM));
        conn.setRequestProperty("X-ParagraphBeginningSpace", String.valueOf(Markdowns.PARAGRAPH_BEGINNING_SPACE));
        conn.setRequestProperty("X-HeadingID", "true");
        conn.setConnectTimeout(100);
        conn.setReadTimeout(3000);
        conn.setDoOutput(true);

        try {
            try (final OutputStream outputStream = conn.getOutputStream()) {
                IOUtils.write(markdownText, outputStream, StandardCharsets.UTF_8);
            }

            try (final InputStream inputStream = conn.getInputStream()) {
                final byte[] bytes = inputStream.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) {
                    throw new IllegalStateException("Lute HTML response exceeds 4 MiB");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Render locally for security checks without calling a configured Lute service or populating its cache. */
    public static String toHTMLLocally(final String markdownText) {
        return StringUtils.isBlank(markdownText) ? "" : toHtmlByFlexmark(markdownText);
    }

    private static String toHtmlByFlexmark(final String markdownText) {
        final com.vladsch.flexmark.util.ast.Node document = PARSER.parse(markdownText);

        return RENDERER.render(document);
    }
}
