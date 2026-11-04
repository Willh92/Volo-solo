package org.b3log.solo.util;

import java.net.URI;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.RequestContext;
import org.json.JSONObject;

/** Checks the browser origin before accepting a management mutation. */
public final class AdminRequests {
    private AdminRequests() { }

    /** A denied HTML management page stays on its URL and returns 403. */
    public static boolean rejectPage(final RequestContext context) {
        if (!"GET".equals(context.method()) && !"HEAD".equals(context.method())) return false;
        final String prefix = Latkes.getContextPath();
        final String requestURI = context.requestURI();
        final String uri = !prefix.isEmpty() && requestURI.startsWith(prefix + "/") ? requestURI.substring(prefix.length()) : requestURI;
        if (!uri.equals(AdminPaths.panel()) && !uri.equals(AdminPaths.panel() + "/")
                && !(uri.startsWith(AdminPaths.prefix() + "/admin-") && uri.endsWith(".do"))) return false;
        context.sendError(403);
        context.abort();
        return true;
    }

    public static boolean allowWrite(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) {
            reject(context, 401, "请先登录管理后台。");
            return false;
        }
        return verifySameOriginWrite(context);
    }

    /** Verifies same-origin XHR headers for an already-authenticated console write request. */
    public static boolean verifySameOriginWrite(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        final String expectedOrigin = expectedOrigin();
        final String origin = context.header("Origin");
        if (!"XMLHttpRequest".equals(context.header("X-Requested-With"))
                || (origin != null && !expectedOrigin.equals(origin))
                || "cross-site".equalsIgnoreCase(context.header("Sec-Fetch-Site"))) {
            reject(context, 403, "请求来源不正确，请从管理后台重试。");
            return false;
        }
        return true;
    }

    /** Verifies same-origin browser state for multipart/editor endpoints where custom headers may be optional. */
    public static boolean allowEditorWrite(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        final String expectedOrigin = expectedOrigin();
        final String origin = context.header("Origin");
        final String secFetchSite = context.header("Sec-Fetch-Site");
        final boolean hasXhr = "XMLHttpRequest".equals(context.header("X-Requested-With"));
        final boolean sameFetchSite = "same-origin".equalsIgnoreCase(secFetchSite);
        if ("cross-site".equalsIgnoreCase(secFetchSite)
                || (origin != null && !expectedOrigin.equals(origin))
                || (origin == null && !hasXhr && !sameFetchSite)) {
            reject(context, 403, "请求来源不正确，请从管理后台重试。");
            return false;
        }
        return true;
    }

    public static boolean isCrossSiteRequest(final RequestContext context) {
        final String secFetchSite = context.header("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(secFetchSite)) {
            return true;
        }
        final String origin = context.header("Origin");
        return origin != null && !"null".equals(origin) && !expectedOrigin().equals(origin);
    }

    private static String expectedOrigin() {
        final URI publicUrl = URI.create(Latkes.getServePath());
        return publicUrl.getScheme() + "://" + publicUrl.getRawAuthority();
    }

    public static void reject(final RequestContext context, final int status, final String message) {
        org.b3log.solo.service.RiskControlService.recordFailure(context, status);
        context.setStatus(status);
        context.renderJSON(new JSONObject().put("sc", false).put("msg", message));
    }
}
