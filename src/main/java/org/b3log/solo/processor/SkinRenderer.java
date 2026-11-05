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
package org.b3log.solo.processor;

import freemarker.template.Template;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.renderer.AbstractFreeMarkerRenderer;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.service.ArticleMgmtService;
import org.b3log.solo.util.Skins;
import org.b3log.solo.web.HttpCompression;
import org.b3log.solo.web.PublicResourceHandler;

import java.io.StringWriter;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Skin renderer.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.9.1
 */
public final class SkinRenderer extends AbstractFreeMarkerRenderer {

    /**
     * Initial character buffer capacity for rendered HTML pages (avoids 12 char[] doublings from StringWriter(16)).
     */
    private static final int INITIAL_HTML_BUFFER_CHARS = 32768;

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleMgmtService.class);

    /**
     * HTTP servlet request context.
     */
    private final RequestContext context;

    /**
     * Active request context passed to {@link #render(RequestContext)}.
     */
    private RequestContext activeContext;

    /**
     * Optional package-private callback invoked once when {@link #genHTML} completes (used by {@link ErrorProcessor}).
     */
    private Consumer<String> htmlCapture;

    /**
     * Constructs a skin renderer with the specified request context and template name.
     *
     * @param context      the specified request context
     * @param templateName the specified template name
     */
    public SkinRenderer(final RequestContext context, final String templateName) {
        this.context = context;
        this.context.setRenderer(this);
        setTemplateName(templateName);
    }

    void setHtmlCapture(final Consumer<String> htmlCapture) {
        this.htmlCapture = htmlCapture;
    }

    @Override
    protected Template getTemplate() {
        final String templateName = getTemplateName();
        Template ret = Skins.getSkinTemplate(context, templateName);
        if (null == ret) {
            // 优先使用皮肤内的登录、报错等模板 https://github.com/b3log/solo/issues/12566
            ret = Skins.getTemplate(templateName);
        }
        if (null == ret) {
            // Rendering a missing page must never change the site's selected skin.
            LOGGER.log(Level.WARN, "Template not found [name={0}]", templateName);
        }

        return ret;
    }

    @Override
    public void render(final RequestContext renderContext) {
        this.activeContext = renderContext != null ? renderContext : this.context;
        try {
            super.render(this.activeContext);
        } finally {
            this.activeContext = null;
        }
    }

    /**
     * Processes the specified FreeMarker template with the specified request, data model, pjax hacking.
     *
     * @param request   the specified request
     * @param dataModel the specified data model
     * @param template  the specified FreeMarker template
     * @return generated HTML
     * @throws Exception exception
     */
    @Override
    protected String genHTML(final Request request, final Map<String, Object> dataModel, final Template template)
            throws Exception {
        final RequestContext ctx = activeContext != null ? activeContext : context;
        final boolean isPJAX = isPJAX(ctx);
        dataModel.put("pjax", isPJAX);

        final StringWriter stringWriter = new StringWriter(INITIAL_HTML_BUFFER_CHARS);
        template.setOutputEncoding("UTF-8");
        template.process(dataModel, stringWriter);

        final long endTimeMillis = System.currentTimeMillis();
        final String dateString = DateFormatUtils.format(endTimeMillis, "yyyy/MM/dd HH:mm:ss");
        final Object startObj = ctx != null ? ctx.attr(Keys.HttpRequest.START_TIME_MILLIS)
                : (request != null ? request.getAttribute(Keys.HttpRequest.START_TIME_MILLIS) : null);
        final long startTimeMillis = startObj instanceof Number num ? num.longValue() : endTimeMillis;
        final long elapsedMillis = endTimeMillis - startTimeMillis;

        final String rendered;
        if (!isPJAX) {
            stringWriter.write("\n<!-- Generated by Latke (https://github.com/88250/latke) in ");
            stringWriter.write(Long.toString(elapsedMillis));
            stringWriter.write("ms, ");
            stringWriter.write(dateString);
            stringWriter.write(" -->");
            rendered = stringWriter.toString();
        } else {
            final String latke = "\n<!-- Generated by Latke (https://github.com/b3log/latke) in "
                    + elapsedMillis + "ms, " + dateString + " -->";
            final String pjaxContainer = ctx.header("X-PJAX-Container");
            final String html = stringWriter.toString();
            final String[] containers = StringUtils.substringsBetween(html,
                    "<!---- pjax {" + pjaxContainer + "} start ---->",
                    "<!---- pjax {" + pjaxContainer + "} end ---->");
            rendered = (null == containers) ? (html + latke) : (String.join("", containers) + latke);
        }

        final Consumer<String> capture = this.htmlCapture;
        if (capture != null) {
            capture.accept(rendered);
        }
        return rendered;
    }

    @Override
    protected void doRender(final String html, final Request request, final Response response) {
        if (response == null || response.isCommitted()) {
            return;
        }
        final RequestContext ctx = activeContext != null ? activeContext : context;
        if (ctx != null && PublicResourceHandler.acceptsGzip(ctx.header("Accept-Encoding"))) {
            HttpCompression.sendText(ctx, html, "text/html; charset=UTF-8");
            return;
        }
        if (html != null && html.length() >= HttpCompression.MIN_GZIP_BYTES) {
            HttpCompression.ensureVaryAcceptEncoding(response);
        }
        super.doRender(html, request, response);
    }

    @Override
    protected void beforeRender(final RequestContext context) {
    }

    @Override
    protected void afterRender(final RequestContext context) {
    }

    /**
     * Determines whether the specified request is sending with pjax.
     *
     * @param context the specified request context
     * @return {@code true} if it is sending with pjax, otherwise returns {@code false}
     */
    private static boolean isPJAX(final RequestContext context) {
        if (context == null) {
            return false;
        }
        final boolean pjax = Boolean.parseBoolean(context.header("X-PJAX"));
        final String pjaxContainer = context.header("X-PJAX-Container");

        return pjax && StringUtils.isNotBlank(pjaxContainer);
    }
}
