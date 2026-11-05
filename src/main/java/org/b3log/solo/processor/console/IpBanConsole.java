/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.processor.console;

import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.service.IpBanService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.IpAddresses;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

/** Administrator-only IP management, independent of the optional page-rate WAF. */
@Singleton
public class IpBanConsole {
    @Inject private IpBanService ipBanService;
    @Inject private CommentRepository commentRepository;
    @Inject private org.b3log.solo.service.GeoAccessService geoAccessService;
    @Inject private org.b3log.solo.service.WebhookNotifyService webhookNotifyService;
    @Inject private org.b3log.solo.service.OptionQueryService optionQueryService;
    @Inject private org.b3log.solo.service.PreferenceMgmtService preferenceMgmtService;

    @RequestProcessing(value = "/console/security/settings", method = HttpMethod.GET)
    public void securitySettings(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) { AdminRequests.reject(context, 403, "仅管理员可以查看安全设置。"); return; }
        try {
            final JSONObject settings = new JSONObject(), preferences = optionQueryService.getPreference();
            for (final String key : org.b3log.solo.service.PreferenceMgmtService.SECURITY_KEYS) {
                final String def = switch (key) {
                    case "commentCaptchaEnabled", "riskControlEnabled", "commentFilterBuiltin", "wafPower" -> "true";
                    case "wafCurrentLimitSecond", "wafCurrentLimitTimes" -> "180";
                    case org.b3log.solo.model.Option.ID_C_COMMENT_MODERATION_MODE -> "off";
                    case org.b3log.solo.model.Option.ID_C_COMMENT_ACCESS_MODE -> "all";
                    default -> "";
                };
                settings.put(key, preferences.optString(key, def));
            }
            settings.put(org.b3log.solo.model.Option.ID_C_COMMENT_ACCESS_MODE,
                    org.b3log.solo.model.Option.effectiveCommentAccessMode(preferences));
            context.renderJSON(new JSONObject().put("sc", true).put("settings", settings));
        } catch (Exception failure) { AdminRequests.reject(context, 503, "安全设置暂时无法读取。"); }
    }

    @RequestProcessing(value = "/console/security/settings", method = HttpMethod.PUT)
    public void saveSecuritySettings(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) return;
        try {
            preferenceMgmtService.updateSecurity(context.requestJSON());
            context.renderJSON(new JSONObject().put("sc", true).put("msg", "安全设置已保存，立即生效。"));
        } catch (IllegalArgumentException invalid) { AdminRequests.reject(context, 400, invalid.getMessage()); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "安全设置保存失败，请重试。"); }
    }

    @RequestProcessing(value = "/console/geo-access", method = HttpMethod.GET)
    public void geoSettings(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) { AdminRequests.reject(context, 403, "仅管理员可以查看地区访问规则。"); return; }
        try { context.renderJSON(geoAccessService.settings(LoginThrottle.clientAddress(context.getRequest())).put("sc", true)); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "地区访问规则暂时无法读取。"); }
    }

    @RequestProcessing(value = "/console/geo-access", method = HttpMethod.PUT)
    public void saveGeoSettings(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) return;
        try {
            geoAccessService.save(context.requestJSON(), LoginThrottle.clientAddress(context.getRequest()));
            context.renderJSON(new JSONObject().put("sc", true).put("msg", "地区访问规则已保存，立即生效。"));
        } catch (IllegalArgumentException invalid) { AdminRequests.reject(context, 400, invalid.getMessage()); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "地区访问规则保存失败，请重试。"); }
    }

    @RequestProcessing(value = "/console/ip-bans", method = HttpMethod.GET)
    public void list(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) { AdminRequests.reject(context, 403, "仅管理员可以查看封禁规则。"); return; }
        try {
            final String page = context.param("page"), size = context.param("pageSize"), keyword = context.param("keyword");
            context.renderJSON(ipBanService.page(page == null ? 1 : Integer.parseInt(page),
                    size == null ? 10 : Integer.parseInt(size), keyword == null ? "" : keyword,
                    context.param("source") == null ? "all" : context.param("source"),
                    context.param("status") == null ? "active" : context.param("status")).put("sc", true));
        }
        catch (IllegalArgumentException invalid) { AdminRequests.reject(context, 400, "封禁列表查询参数不正确。"); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "封禁列表暂时无法读取。"); }
    }

    @RequestProcessing(value = "/console/ip-bans", method = HttpMethod.POST)
    public void add(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) return;
        try {
            final JSONObject request = context.requestJSON();
            String range = request.optString("range");
            if (!request.optString("commentId").isBlank()) {
                final JSONObject comment = commentRepository.get(request.getString("commentId"));
                if (comment == null || comment.optString(IpAddresses.COMMENT_IP).isBlank()) {
                    throw new IllegalArgumentException("这条评论没有可封禁的访客 IP。");
                }
                range = comment.optString(IpAddresses.COMMENT_IP);
            }
            ipBanService.add(range, request.optString("reason"), LoginThrottle.clientAddress(context.getRequest()), duration(request));
            if (webhookNotifyService != null) {
                webhookNotifyService.notifyEvent("security.ip_ban", "新增 IP 封禁规则：" + range,
                        new JSONObject().put("range", range).put("reason", request.optString("reason")));
            }
            context.renderJSON(new JSONObject().put("sc", true).put("msg", "已封禁，规则立即生效。"));
        } catch (IllegalArgumentException invalid) { AdminRequests.reject(context, 400, invalid.getMessage()); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "封禁规则保存失败，请重试。"); }
    }

    @RequestProcessing(value = "/console/ip-bans", method = HttpMethod.PUT)
    public void update(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) return;
        try {
            final JSONObject request = context.requestJSON();
            if (!(request.opt("range") instanceof String) || !(request.opt("note") instanceof String)) {
                throw new IllegalArgumentException("封禁地址或备注格式不正确。");
            }
            ipBanService.update(request.getString("range"), request.getString("note"),
                    LoginThrottle.clientAddress(context.getRequest()), request.has("durationSeconds") ? duration(request) : null,
                    occurrences(request));
            context.renderJSON(new JSONObject().put("sc", true).put("msg", "封禁规则已更新，立即生效。"));
        } catch (IllegalArgumentException invalid) { AdminRequests.reject(context, 400, invalid.getMessage()); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "封禁规则保存失败，请重试。"); }
    }

    private static long duration(final JSONObject data) {
        if (!data.has("durationSeconds")) return 0;
        final Object value = data.get("durationSeconds");
        if (!(value instanceof Number) || !value.toString().matches("[0-9]{1,10}")) throw new IllegalArgumentException("封禁时长格式不正确。");
        return ((Number) value).longValue();
    }

    private static Integer occurrences(final JSONObject data) {
        if (!data.has("occurrences")) return null;
        final Object value = data.get("occurrences");
        if (!(value instanceof Number) || !value.toString().matches("[0-9]{1,7}")) {
            throw new IllegalArgumentException("自动风控次数应为 0 至 1000000 的整数。");
        }
        return ((Number) value).intValue();
    }

    @RequestProcessing(value = "/console/ip-bans/remove", method = HttpMethod.POST)
    public void remove(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) return;
        try {
            final String range = context.requestJSON().getString("range");
            ipBanService.remove(range);
            org.b3log.latke.ioc.BeanManager.getInstance().getReference(org.b3log.solo.service.RiskControlService.class).clear(range);
            context.renderJSON(new JSONObject().put("sc", true).put("msg", "已解除封禁。"));
        } catch (IllegalArgumentException invalid) { AdminRequests.reject(context, 400, invalid.getMessage()); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "解除封禁失败，请重试。"); }
    }
}
