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
package org.b3log.solo.web;

import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.AbstractResponseRenderer;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.model.Common;
import org.json.JSONObject;

/** Initializes a JSON response before using Latke's field setters, preserving Bolo's sc contract. */
public final class JsonResponses {
    private static final byte[] EMPTY_BYTES = new byte[0];

    private JsonResponses() { }

    public static RequestContext json(final RequestContext context) {
        if (context.getRenderer() instanceof CompressibleJsonRenderer wrapped) {
            context.setRenderer(wrapped.delegate);
        } else if (!(context.getRenderer() instanceof JsonRenderer)) {
            context.renderJSON(new JSONObject().put(Common.STATUS_CODE, false));
        }
        return context;
    }

    /**
     * Upgrades a populated {@link JsonRenderer} on {@code context} to a compression- and
     * {@code Vary: Accept-Encoding}-aware renderer (supporting both compact and pretty JSON,
     * plain and GZIP {@code GET}, and zero-body {@code HEAD}). Because Latke 3.5.1 declares
     * {@link JsonRenderer} {@code final} and checks {@code renderer instanceof JsonRenderer}
     * inside {@code renderCode/Data/Msg}, wrapping runs after controller handlers finish and
     * right before {@code Dispatcher.renderResponse}.
     */
    public static void applyCompression(final RequestContext context) {
        if (context == null || context.getResponse() == null || context.getResponse().isCommitted()) {
            return;
        }
        if (context.getRenderer() instanceof JsonRenderer jsonRenderer && !jsonRenderer.isJSONP()) {
            context.setRenderer(new CompressibleJsonRenderer(jsonRenderer));
        }
    }

    public static void render(final RequestContext context) {
        if (context == null || context.getRenderer() == null) {
            return;
        }
        applyCompression(context);
        context.getRenderer().render(context);
    }

    public static AbstractResponseRenderer compressible(final JsonRenderer delegate) {
        return new CompressibleJsonRenderer(delegate);
    }

    public static final class CompressibleJsonRenderer extends AbstractResponseRenderer {
        private final JsonRenderer delegate;

        public CompressibleJsonRenderer(final JsonRenderer delegate) {
            this.delegate = delegate;
        }

        public JsonRenderer unwrap() {
            return delegate;
        }

        public JSONObject getJSONObject() {
            return delegate.getJSONObject();
        }

        @Override
        public void render(final RequestContext context) {
            if (context == null || context.getResponse() == null || context.getResponse().isCommitted()) {
                return;
            }
            if (delegate.isJSONP()) {
                delegate.render(context);
                return;
            }
            final JSONObject obj = delegate.getJSONObject();
            if (obj == null) {
                if ("HEAD".equalsIgnoreCase(context.method())) {
                    context.getResponse().setContentType("application/json; charset=UTF-8");
                    context.sendBytes(EMPTY_BYTES);
                } else {
                    delegate.render(context);
                }
                return;
            }
            final String json = delegate.isPretty() ? obj.toString(4) : obj.toString();
            if ("HEAD".equalsIgnoreCase(context.method())) {
                HttpCompression.sendText(context, json, "application/json; charset=UTF-8");
                return;
            }
            if (PublicResourceHandler.acceptsGzip(context.header("Accept-Encoding"))) {
                HttpCompression.sendText(context, json, "application/json; charset=UTF-8");
                return;
            }
            if (json.length() >= HttpCompression.MIN_GZIP_BYTES
                    || (json.length() * 3 >= HttpCompression.MIN_GZIP_BYTES
                    && HttpCompression.utf8EncodedLength(json) >= HttpCompression.MIN_GZIP_BYTES)) {
                HttpCompression.ensureVaryAcceptEncoding(context.getResponse());
            }
            delegate.render(context);
        }
    }
}
