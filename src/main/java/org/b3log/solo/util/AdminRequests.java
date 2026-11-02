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
        final URI publicUrl = URI.create(Latkes.getServePath());
        final String expectedOrigin = publicUrl.getScheme() + "://" + publicUrl.getRawAuthority();
        final String origin = context.header("Origin");
        if (!"XMLHttpRequest".equals(context.header("X-Requested-With"))
                || (origin != null && !expectedOrigin.equals(origin))
                || "cross-site".equals(context.header("Sec-Fetch-Site"))) {
            reject(context, 403, "请求来源不正确，请从管理后台重试。");
            return false;
        }
        return true;
    }

    public static void reject(final RequestContext context, final int status, final String message) {
        org.b3log.solo.service.RiskControlService.recordFailure(context, status);
        context.setStatus(status);
        context.renderJSON(new JSONObject().put("sc", false).put("msg", message));
    }
}
