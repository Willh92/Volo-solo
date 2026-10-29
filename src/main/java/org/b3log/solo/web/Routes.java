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

import org.b3log.latke.http.*;
import org.b3log.latke.http.function.Handler;
import org.b3log.latke.http.handler.ContextHandlerMeta;
import org.b3log.latke.http.handler.RouteHandler;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.solo.web.annotation.Before;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.solo.util.AdminPaths;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.*;

/** Registers Bolo's routes and authorization middleware with Latke 3. */
public final class Routes {
    public static final List<Handler> HANDLERS = Dispatcher.HANDLERS;
    private Routes() { }

    private static void add(final String[] uris, final HttpMethod[] methods, final Method method, final Handler handler) {
        final List<Handler> middleware = new ArrayList<>();
        final Set<Class<? extends Handler>> types = new LinkedHashSet<>();
        final Before classAdvice = method.getDeclaringClass().getAnnotation(Before.class);
        final Before methodAdvice = method.getAnnotation(Before.class);
        if (classAdvice != null) types.addAll(Arrays.asList(classAdvice.value()));
        if (methodAdvice != null) types.addAll(Arrays.asList(methodAdvice.value()));
        for (final Class<? extends Handler> type : types) middleware.add(BeanManager.getInstance().getReference(type));
        final ContextHandlerMeta meta = new ContextHandlerMeta();
        final List<String> paths = new ArrayList<>();
        for (final String uri : uris) {
            if (AdminPaths.customized() && "/root".equals(uri)) continue;
            paths.add(AdminPaths.route(uri));
            if (AdminPaths.customized() && "/admin-index.do".equals(uri)) paths.add(AdminPaths.panel() + "/");
        }
        if (paths.isEmpty()) return;
        meta.setUriTemplates(paths.toArray(String[]::new));
        meta.setHttpMethods(methods);
        meta.setInvokeHolder(method);
        meta.setHandler(handler);
        meta.setMiddlewares(middleware);
        RouteHandler.addContextHandlerMeta(meta);
    }

    private static void bind(final String[] uris, final HttpMethod method, final Handler handler) {
        try {
            final Method replace = handler.getClass().getDeclaredMethod("writeReplace");
            replace.setAccessible(true);
            final SerializedLambda lambda = (SerializedLambda) replace.invoke(handler);
            final Class<?> type = Class.forName(lambda.getImplClass().replace('/', '.'));
            add(uris, new HttpMethod[]{method}, type.getDeclaredMethod(lambda.getImplMethodName(), RequestContext.class), handler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot bind route " + Arrays.toString(uris), e);
        }
    }
    public static void get(final String uri, final Handler handler) { bind(new String[]{uri}, HttpMethod.GET, handler); }
    public static void get(final String[] uris, final Handler handler) { bind(uris, HttpMethod.GET, handler); }
    public static void post(final String uri, final Handler handler) { bind(new String[]{uri}, HttpMethod.POST, handler); }
    public static void put(final String uri, final Handler handler) { bind(new String[]{uri}, HttpMethod.PUT, handler); }
    public static void delete(final String uri, final Handler handler) { bind(new String[]{uri}, HttpMethod.DELETE, handler); }
    public static void mapping() {
        for (final Class<?> type : Controllers.TYPES) {
            for (final Method method : type.getDeclaredMethods()) {
                final RequestProcessing route = method.getAnnotation(RequestProcessing.class);
                if (route != null) add(route.value(), route.method(), method, null);
            }
        }
    }
}
