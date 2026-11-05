/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.OutboundUrls;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generic SSRF-safe Webhook notification service supporting Feishu, WeCom, DingTalk, Bark, Telegram, and Custom JSON.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class WebhookNotifyService {

    private static final Logger LOGGER = Logger.getLogger(WebhookNotifyService.class);
    private static final int MAX_WEBHOOKS = 20;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final Set<String> ALLOWED_PROVIDERS = Set.of(
            "feishu", "wecom", "dingtalk", "bark", "telegram", "custom");
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "comment", "moderation", "register", "security", "backup");

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);
    private static final ExecutorService DISPATCH_POOL = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
                final Thread t = new Thread(runnable, "volo-webhook-" + THREAD_SEQ.getAndIncrement());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    @Inject
    private OptionRepository optionRepository;

    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Lists configured webhooks with {@code secret} masked for admin UI rendering.
     */
    public JSONArray listMasked() {
        final JSONArray raw = loadRawArray();
        final JSONArray masked = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            final JSONObject item = raw.optJSONObject(i);
            if (item == null) {
                continue;
            }
            masked.put(maskItem(item));
        }
        return masked;
    }

    /**
     * Masks secrets inside a raw {@code voloWebhooks} JSON string for safe data export.
     */
    public static String maskJsonArrayString(final String rawJson) {
        if (StringUtils.isBlank(rawJson)) {
            return "[]";
        }
        try {
            final JSONArray arr = new JSONArray(rawJson);
            final JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject item = arr.optJSONObject(i);
                if (item != null) {
                    out.put(maskItem(item));
                }
            }
            return out.toString();
        } catch (final Exception ignored) {
            return "[]";
        }
    }

    private static JSONObject maskItem(final JSONObject item) {
        final JSONObject copy = new JSONObject(item.toString());
        final String secret = copy.optString("secret", "");
        copy.put("secretSet", !secret.isBlank());
        copy.put("secret", "");
        return copy;
    }

    /**
     * Validates and saves (creates or updates) a webhook endpoint.
     */
    public synchronized JSONObject saveWebhook(final JSONObject payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Webhook 参数不能为空。");
        }
        final String name = StringUtils.trimToEmpty(payload.optString("name"));
        if (name.isEmpty() || name.length() > 64) {
            throw new IllegalArgumentException("推送通道名称长度需在 1 ~ 64 个字符之间。");
        }
        final String provider = StringUtils.trimToEmpty(payload.optString("provider")).toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("不支持的 Webhook 平台类型。");
        }
        final String url = StringUtils.trimToEmpty(payload.optString("url"));
        validateWebhookUrl(url);

        final Set<String> events = new LinkedHashSet<>();
        final JSONArray reqEvents = payload.optJSONArray("events");
        if (reqEvents != null) {
            for (int i = 0; i < reqEvents.length(); i++) {
                final String ev = StringUtils.trimToEmpty(reqEvents.optString(i)).toLowerCase(Locale.ROOT);
                if (ALLOWED_EVENTS.contains(ev)) {
                    events.add(ev);
                }
            }
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("请至少勾选一项要订阅的通知事件。");
        }

        final String incomingSecret = StringUtils.trimToEmpty(payload.optString("secret"));
        if (incomingSecret.length() > 256) {
            throw new IllegalArgumentException("签名密钥长度不能超过 256 个字符。");
        }
        final boolean clearSecret = payload.optBoolean("clearSecret", false);
        final String chatId = StringUtils.trimToEmpty(payload.optString("chatId"));
        if (chatId.length() > 80) {
            throw new IllegalArgumentException("Chat ID 长度不能超过 80 个字符。");
        }
        final boolean enabled = payload.optBoolean("enabled", true);

        final JSONArray list = loadRawArray();
        String id = StringUtils.trimToEmpty(payload.optString("id"));
        int existingIndex = -1;
        if (!id.isEmpty()) {
            for (int i = 0; i < list.length(); i++) {
                final JSONObject cur = list.optJSONObject(i);
                if (cur != null && id.equals(cur.optString("id"))) {
                    existingIndex = i;
                    break;
                }
            }
        }
        if (existingIndex < 0 && list.length() >= MAX_WEBHOOKS) {
            throw new IllegalArgumentException("最多支持配置 " + MAX_WEBHOOKS + " 条 Webhook 通道。");
        }
        if (id.isEmpty() || existingIndex < 0) {
            id = "wh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        String resolvedSecret = incomingSecret;
        if (existingIndex >= 0 && incomingSecret.isEmpty() && !clearSecret) {
            resolvedSecret = list.getJSONObject(existingIndex).optString("secret", "");
        }

        final JSONObject saved = new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("provider", provider)
                .put("url", url)
                .put("secret", resolvedSecret)
                .put("chatId", chatId)
                .put("events", new JSONArray(events))
                .put("enabled", enabled)
                .put("updatedAt", System.currentTimeMillis());

        if (existingIndex >= 0) {
            list.put(existingIndex, saved);
        } else {
            list.put(saved);
        }
        persistRawArray(list);
        return maskItem(saved);
    }

    /**
     * Removes a webhook endpoint by id.
     */
    public synchronized boolean removeWebhook(final String id) {
        if (StringUtils.isBlank(id)) {
            return false;
        }
        final JSONArray list = loadRawArray();
        final JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < list.length(); i++) {
            final JSONObject cur = list.optJSONObject(i);
            if (cur == null) {
                continue;
            }
            if (id.equals(cur.optString("id"))) {
                removed = true;
            } else {
                next.put(cur);
            }
        }
        if (removed) {
            persistRawArray(next);
        }
        return removed;
    }

    /**
     * Sends a synchronous connectivity test notification to the specified webhook endpoint.
     */
    public int testWebhook(final String id) throws IOException {
        final JSONArray list = loadRawArray();
        JSONObject target = null;
        for (int i = 0; i < list.length(); i++) {
            final JSONObject cur = list.optJSONObject(i);
            if (cur != null && id.equals(cur.optString("id"))) {
                target = cur;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("未找到指定的 Webhook 通道。");
        }
        return deliverOne(target, "webhook.test", "Volo Webhook 连通性测试成功",
                new JSONObject().put("channel", target.optString("name")));
    }

    /**
     * Dispatches an event asynchronously to all subscribed, enabled webhooks.
     */
    public void notifyEvent(final String eventType, final String summary, final JSONObject details) {
        if (StringUtils.isBlank(eventType)) {
            return;
        }
        final String category = normalizeCategory(eventType);
        final JSONArray list = loadRawArray();
        if (list.isEmpty()) {
            return;
        }
        final JSONObject safeDetails = details == null ? new JSONObject() : new JSONObject(details.toString());
        for (int i = 0; i < list.length(); i++) {
            final JSONObject item = list.optJSONObject(i);
            if (item == null || !item.optBoolean("enabled", true)) {
                continue;
            }
            if (!subscribes(item.optJSONArray("events"), category)) {
                continue;
            }
            final JSONObject snapshot = new JSONObject(item.toString());
            DISPATCH_POOL.execute(() -> {
                try {
                    deliverOne(snapshot, eventType, summary, safeDetails);
                } catch (final Exception e) {
                    LOGGER.log(Level.WARN, "Webhook delivery failed for [" + snapshot.optString("name") + "]: " + e.getMessage());
                }
            });
        }
    }

    public static void validateWebhookUrl(final String rawUrl) {
        if (!OutboundUrls.isHttpOrHttps(rawUrl)) {
            throw new IllegalArgumentException("Webhook 地址必须是合法的 http:// 或 https:// 公网 URL。");
        }
        try {
            OutboundUrls.requirePublicHttpUri(rawUrl, false);
        } catch (final IOException blocked) {
            throw new IllegalArgumentException("Webhook 地址不可用或指向受限的内网/本机地址：" + blocked.getMessage());
        }
    }

    int deliverOne(final JSONObject endpoint, final String eventType, final String summary, final JSONObject details)
            throws IOException {
        final String provider = endpoint.optString("provider", "custom").toLowerCase(Locale.ROOT);
        String targetUrl = endpoint.optString("url", "").trim();
        final String secret = endpoint.optString("secret", "");
        final String chatId = endpoint.optString("chatId", "");
        final String blogTitle = resolveBlogTitle();
        final long nowMs = System.currentTimeMillis();
        final String textMessage = buildReadableText(blogTitle, summary, eventType, details);

        final Map<String, String> headers = new LinkedHashMap<>();
        final JSONObject body;
        switch (provider) {
            case "feishu" -> {
                body = new JSONObject()
                        .put("msg_type", "text")
                        .put("content", new JSONObject().put("text", textMessage));
                if (!secret.isBlank()) {
                    final long tsSec = nowMs / 1000L;
                    final String stringToSign = tsSec + "\n" + secret;
                    body.put("timestamp", String.valueOf(tsSec));
                    body.put("sign", hmacBase64(stringToSign.getBytes(StandardCharsets.UTF_8), new byte[0]));
                }
            }
            case "dingtalk" -> {
                body = new JSONObject()
                        .put("msgtype", "text")
                        .put("text", new JSONObject().put("content", textMessage));
                if (!secret.isBlank()) {
                    final String stringToSign = nowMs + "\n" + secret;
                    final String sign = URLEncoder.encode(
                            hmacBase64(secret.getBytes(StandardCharsets.UTF_8), stringToSign.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8);
                    targetUrl = targetUrl + (targetUrl.contains("?") ? "&" : "?")
                            + "timestamp=" + nowMs + "&sign=" + sign;
                }
            }
            case "wecom" -> body = new JSONObject()
                    .put("msgtype", "text")
                    .put("text", new JSONObject().put("content", textMessage));
            case "bark" -> body = new JSONObject()
                    .put("title", "[" + blogTitle + "] " + summary)
                    .put("body", textMessage)
                    .put("group", "Volo");
            case "telegram" -> body = new JSONObject()
                    .put("chat_id", !chatId.isBlank() ? chatId : secret)
                    .put("text", textMessage);
            default -> {
                body = new JSONObject()
                        .put("event", eventType)
                        .put("summary", summary)
                        .put("blogTitle", blogTitle)
                        .put("timestamp", nowMs)
                        .put("details", details == null ? new JSONObject() : details);
                if (!secret.isBlank()) {
                    final String jsonStr = body.toString();
                    headers.put("X-Volo-Signature", "sha256=" + hmacHex(
                            secret.getBytes(StandardCharsets.UTF_8), jsonStr.getBytes(StandardCharsets.UTF_8)));
                    final int code = OutboundUrls.postJson(targetUrl, jsonStr, headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, false);
                    if (code < 200 || code >= 300) {
                        throw new IOException("远端 Webhook 返回 HTTP " + code);
                    }
                    return code;
                }
            }
        }

        final int status = OutboundUrls.postJson(targetUrl, body.toString(), headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, false);
        if (status < 200 || status >= 300) {
            throw new IOException("远端 Webhook 返回 HTTP " + status);
        }
        return status;
    }

    private String resolveBlogTitle() {
        try {
            if (optionQueryService != null) {
                final JSONObject pref = optionQueryService.getPreference();
                if (pref != null) {
                    final String t = pref.optString(Option.ID_C_BLOG_TITLE);
                    if (StringUtils.isNotBlank(t)) {
                        return t.trim();
                    }
                }
            }
        } catch (final Exception ignored) {
        }
        return "Volo";
    }

    private static String buildReadableText(final String blogTitle, final String summary,
                                            final String eventType, final JSONObject details) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[').append(blogTitle).append("] ").append(summary);
        sb.append("\n事件类型：").append(eventType);
        if (details != null && !details.isEmpty()) {
            for (final String key : details.keySet()) {
                final Object val = details.opt(key);
                if (val != null) {
                    sb.append('\n').append(key).append("：").append(StringUtils.abbreviate(String.valueOf(val), 180));
                }
            }
        }
        return sb.toString();
    }

    private static String normalizeCategory(final String eventType) {
        final String lower = eventType.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("pending") || lower.contains("moderat")) {
            return "moderation";
        }
        if (lower.contains("comment")) {
            return "comment";
        }
        if (lower.contains("register") || lower.contains("user")) {
            return "register";
        }
        if (lower.contains("ban") || lower.contains("security")) {
            return "security";
        }
        if (lower.contains("backup")) {
            return "backup";
        }
        return lower;
    }

    private static boolean subscribes(final JSONArray events, final String category) {
        if (events == null) {
            return false;
        }
        for (int i = 0; i < events.length(); i++) {
            final String ev = events.optString(i, "").toLowerCase(Locale.ROOT);
            if ("*".equals(ev) || category.equals(ev)) {
                return true;
            }
        }
        return false;
    }

    private static String hmacBase64(final byte[] key, final byte[] message) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message));
        } catch (final Exception e) {
            throw new IllegalStateException("HMAC-SHA256 calculation failed", e);
        }
    }

    private static String hmacHex(final byte[] key, final byte[] message) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (final Exception e) {
            throw new IllegalStateException("HMAC-SHA256 calculation failed", e);
        }
    }

    private JSONArray loadRawArray() {
        try {
            if (optionRepository == null) {
                return new JSONArray();
            }
            final JSONObject opt = optionRepository.get(Option.ID_C_VOLO_WEBHOOKS);
            if (opt == null) {
                return new JSONArray();
            }
            final String val = opt.optString(Option.OPTION_VALUE, "[]");
            return StringUtils.isBlank(val) ? new JSONArray() : new JSONArray(val);
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Failed to read voloWebhooks option", e);
            return new JSONArray();
        }
    }

    private void persistRawArray(final JSONArray array) {
        final String serialized = array.toString();
        if (serialized.length() > 9800) {
            throw new IllegalArgumentException("Webhook 配置总体积超出上限，请精简通道数量。");
        }
        final Transaction tx = optionRepository.beginTransaction();
        try {
            JSONObject opt = optionRepository.get(Option.ID_C_VOLO_WEBHOOKS);
            if (opt == null) {
                opt = new JSONObject();
                opt.put(Keys.OBJECT_ID, Option.ID_C_VOLO_WEBHOOKS);
                opt.put(Option.OPTION_CATEGORY, "webhooks");
                opt.put(Option.OPTION_VALUE, serialized);
                optionRepository.add(opt);
            } else {
                opt.put(Option.OPTION_VALUE, serialized);
                optionRepository.update(Option.ID_C_VOLO_WEBHOOKS, opt);
            }
            tx.commit();
        } catch (final Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw new IllegalStateException("保存 Webhook 配置失败", e);
        }
    }
}
