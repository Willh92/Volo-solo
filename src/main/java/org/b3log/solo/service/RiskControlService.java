/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.util.AttackTracker;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.util.Solos;

/** Public request risk assessment. Authenticated administrators never enter the counters. */
@Service
public class RiskControlService {
    private static final String PATH = "volo.risk.path", SKIP = "volo.risk.skip", FAILED = "volo.risk.failed";
    private final AttackTracker tracker = new AttackTracker();
    @Inject private IpBanService ipBanService;
    @Inject private OptionQueryService optionQueryService;

    public boolean inspect(final RequestContext context) throws Exception {
        final String address = LoginThrottle.clientAddress(context.getRequest());
        final boolean administrator = Solos.isAdminLoggedIn(context);
        context.attr(PATH, context.requestURI());
        context.attr(SKIP, administrator || AttackTracker.internal(address));
        if (administrator) return false;
        if (ipBanService.blocked(address)) { context.attr(SKIP, true); return true; }
        if (!Boolean.TRUE.equals(context.attr(SKIP)) && enabled()) {
            final String rawUri = context.requestURI();
            final int qIdx = rawUri.indexOf('?');
            if (org.b3log.solo.util.XssPayloads.executable(qIdx < 0 ? rawUri : rawUri.substring(0, qIdx))) {
                ipBanService.automatic(address, new AttackTracker.Trigger("xss_payload", "请求路径包含可执行 XSS 载荷，永久封禁"));
                context.attr(SKIP, true); return true;
            }
            final AttackTracker.Trigger trigger = tracker.record(address, context.requestURI(), 0, false);
            if (trigger != null) { ipBanService.automatic(address, trigger); context.attr(SKIP, true); return true; }
        }
        return false;
    }
    private volatile Boolean power;
    public void configure(final boolean enabled) { power = enabled; }
    protected boolean enabled() throws Exception {
        if (power != null) return power;
        final var option = optionQueryService.getOptionById("riskControlEnabled");
        power = option == null || !"off".equals(option.optString(org.b3log.solo.model.Option.OPTION_VALUE));
        return power;
    }
    public void failure(final RequestContext context, final int status) throws Exception {
        if (context.attr(PATH) == null || Boolean.TRUE.equals(context.attr(SKIP)) || Boolean.TRUE.equals(context.attr(FAILED))) return;
        context.attr(FAILED, true);
        if (!enabled()) return;
        final String address = LoginThrottle.clientAddress(context.getRequest());
        final var trigger = tracker.record(address, (String) context.attr(PATH), status, false);
        if (trigger != null) ipBanService.automatic(address, trigger);
    }
    public void clear(final String address) { tracker.clear(org.b3log.solo.util.IpAddresses.canonical(address)); }

    public static boolean rejectCommentXss(final RequestContext context, final org.json.JSONObject data) {
        if (data == null || Solos.isAdminLoggedIn(context)) return false;
        String field = null;
        for (final String key : new String[]{"boloUser", "boloSite", "email", "commentName", "commentURL", "commentOriginalCommentName", "commentOriginalCommentId", "oId"}) {
            if (org.b3log.solo.util.XssPayloads.executable(data.optString(key))) { field = key; break; }
        }
        if (field == null && org.b3log.solo.util.XssPayloads.markdown(data.optString("commentContent"))) field = "commentContent";
        if (field == null) return false;
        final String address = LoginThrottle.clientAddress(context.getRequest());
        try {
            final var service = BeanManager.getInstance().getReference(RiskControlService.class);
            if (!AttackTracker.internal(address) && service.enabled()) service.ipBanService.automatic(address,
                    new AttackTracker.Trigger("xss_payload", "评论字段 " + field + " 包含可执行 XSS 载荷，永久封禁"));
        } catch (Exception failure) { org.b3log.solo.logging.Logger.getLogger(RiskControlService.class)
                .log(org.apache.logging.log4j.Level.WARN, "XSS ban could not be saved", failure); }
        context.setStatus(403);
        context.renderJSON(new org.json.JSONObject().put("sc", false).put("msg", "请求包含不允许的可执行内容。"));
        return true;
    }

    /** Failure reporting must never replace the intended application response. */
    public static void recordFailure(final RequestContext context, final int status) {
        if (status != 400 && status != 401 && status != 403 && status != 404 && status != 405 && status != 429) return;
        try { BeanManager.getInstance().getReference(RiskControlService.class).failure(context, status); }
        catch (Exception failure) { org.b3log.solo.logging.Logger.getLogger(RiskControlService.class)
                .log(org.apache.logging.log4j.Level.WARN, "Automatic risk rule could not be saved", failure); }
    }
}
