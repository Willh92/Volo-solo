/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.IpBanService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.AttackTracker;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RiskControlTest {
    @Test public void executableXssIsPermanentButCodeExamplesRemainAllowed() throws Exception {
        for (String payload : new String[]{"<svg/onload=document.body.dataset.p='x'>", "<img src=x onerror=alert(1)>",
                "%253Cscript%253Ealert(1)", "&#x3c;iframe src=x>", "JaVa\nScRiPt:alert(1)"}) {
            Assert.assertTrue(org.b3log.solo.util.XssPayloads.executable(payload), payload);
        }
        Assert.assertFalse(org.b3log.solo.util.XssPayloads.executable("普通昵称 & 阅读"));
        Assert.assertFalse(org.b3log.solo.util.XssPayloads.markdown("代码示例：`<img onerror=alert(1)>`"));
        Assert.assertFalse(org.b3log.solo.util.XssPayloads.markdown("```html\n<script>alert(1)</script>\n```"));
        Assert.assertTrue(org.b3log.solo.util.XssPayloads.markdown("正文 <img onerror=alert(1)>"));
        final AtomicLong clock = new AtomicLong(1_000_000);
        final JSONObject[] stored = {null}; final IpBanService service = create(clock, stored);
        service.automatic("8.8.8.8", new AttackTracker.Trigger("xss_payload", "评论 XSS"));
        clock.addAndGet(1000L * 86_400_000);
        Assert.assertTrue(create(clock, stored).blocked("8.8.8.8"));
        Assert.assertEquals(service.page(1, 10, "").getJSONArray("rules").getJSONObject(0).getLong("expiresAt"), 0L);
    }
    @Test public void scanningDoesNotDependOn404AndExplainsTheTrigger() {
        final AttackTracker tracker = new AttackTracker();
        Assert.assertNull(tracker.record("172.213.9.3", "/one.php", 0, false));
        Assert.assertNull(tracker.record("172.213.9.3", "/two.php", 0, false));
        final var trigger = tracker.record("172.213.9.3", "/wp-admin/filemanager.php", 0, false);
        Assert.assertEquals(trigger.code(), "script_scan");
        Assert.assertTrue(trigger.reason().contains("3 个路径"));
        Assert.assertEquals(tracker.record("8.8.8.8", "/%252e%252e/etc/passwd", 0, false).code(), "path_traversal");
    }
    @Test public void legitimateMissingAssetsAndAdministratorRequestsAreExcluded() {
        final AttackTracker tracker = new AttackTracker();
        for (int i = 0; i < 500; i++) {
            Assert.assertNull(tracker.record("8.8.8.8", "/images/missing-" + i + ".png", 404, false));
            Assert.assertNull(tracker.record("1.1.1.1", "/one.php", 0, true));
            Assert.assertNull(tracker.record("172.16.1.9", "/../../etc/passwd", 0, false));
        }
        for (String ip : new String[]{"127.0.0.1", "10.2.3.4", "192.168.1.2", "100.64.1.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1"}) Assert.assertTrue(AttackTracker.internal(ip), ip);
        Assert.assertFalse(AttackTracker.internal("172.213.9.3"));
        Assert.assertFalse(AttackTracker.internal("240e::123"));
    }
    @Test public void notFoundInvalidRequestsAndFloodsUseSeparateThresholdsAndExpire() {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final AttackTracker tracker = new AttackTracker(clock::get);
        for (int i = 0; i < 19; i++) Assert.assertNull(tracker.record("8.8.4.4", "/missing" + i, 404, false));
        Assert.assertEquals(tracker.record("8.8.4.4", "/missing19", 404, false).code(), "not_found_scan");
        for (int i = 0; i < 39; i++) Assert.assertNull(tracker.record("9.9.9.9", "/console/private", 403, false));
        Assert.assertEquals(tracker.record("9.9.9.9", "/console/private", 401, false).code(), "rejected_flood");
        for (int i = 0; i < 479; i++) Assert.assertNull(tracker.record("1.0.0.1", "/", 0, false));
        Assert.assertEquals(tracker.record("1.0.0.1", "/", 0, false).code(), "request_flood");
        clock.addAndGet(120_000);
        Assert.assertNull(tracker.record("8.8.4.4", "/missing20", 404, false));
        tracker.clear("9.9.9.9");
        Assert.assertNull(tracker.record("9.9.9.9", "/console/private", 403, false));
    }
    @Test public void timedBansSurviveReloadExpireExactlyAndNeverReplaceManualBans() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final JSONObject[] stored = {null};
        final IpBanService service = create(clock, stored);
        final var trigger = new AttackTracker.Trigger("script_scan", "批量脚本扫描");
        service.automatic("172.213.9.3", trigger);
        Assert.assertTrue(service.blocked("172.213.9.3"));
        Assert.assertEquals(service.page(1, 10, "", "automatic", "active").getInt("total"), 1);
        final IpBanService reloaded = create(clock, stored);
        clock.addAndGet(86_400_000L - 1);
        Assert.assertTrue(reloaded.blocked("172.213.9.3"));
        clock.incrementAndGet();
        Assert.assertFalse(reloaded.blocked("172.213.9.3"));
        Assert.assertEquals(reloaded.page(1, 10, "", "automatic", "expired").getInt("total"), 1);
        reloaded.add("172.213.9.3", "人工确认", "8.8.8.8", 0);
        reloaded.automatic("172.213.9.3", trigger);
        Assert.assertEquals(reloaded.page(1, 10, "", "manual", "active").getInt("total"), 1);
        clock.addAndGet(100L * 86_400_000);
        Assert.assertTrue(reloaded.blocked("172.213.9.3"));
        reloaded.remove("172.213.9.3"); Assert.assertFalse(reloaded.blocked("172.213.9.3"));
    }
    @Test public void legacyRulesRemainPermanentAndDurationsAreBounded() throws Exception {
        final JSONObject[] stored = {new JSONObject().put(Option.OPTION_VALUE, new JSONArray().put(new JSONObject().put("range", "8.8.8.8").put("created", 1)).toString())};
        final AtomicLong clock = new AtomicLong(1_000_000);
        final IpBanService service = create(clock, stored);
        Assert.assertTrue(service.blocked("8.8.8.8"));
        Assert.assertEquals(service.page(1, 10, "", "manual", "active").getInt("total"), 1);
        for (int days : new int[]{1, 3, 7, 30}) {
            service.add("1.1.1.1", "测试", "9.9.9.9", days * 86400L);
            Assert.assertEquals(service.page(1, 10, "1.1.1.1").getJSONArray("rules").getJSONObject(0).getLong("expiresAt"), clock.get() + days * 86_400_000L);
        }
        Assert.expectThrows(IllegalArgumentException.class, () -> service.add("9.9.9.0/24", "", "9.9.9.9", 0));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.add("1.1.1.1", "", "9.9.9.9", Long.MAX_VALUE));
        service.automatic("127.0.0.1", new AttackTracker.Trigger("test", "test"));
        Assert.assertFalse(service.blocked("127.0.0.1"));
    }
    @Test public void editKeepsDeadlineAttributionAndOriginalCreationUntilExplicitlyChanged() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final JSONObject[] stored = {null}; final IpBanService service = create(clock, stored);
        service.automatic("8.8.8.8", new AttackTracker.Trigger("script_scan", "批量脚本扫描"));
        clock.addAndGet(1000);
        service.update("8.8.8.8", "已核实", "9.9.9.9", null);
        final JSONObject row = create(clock, stored).page(1, 10, "已核实").getJSONArray("rules").getJSONObject(0);
        Assert.assertEquals(row.getString("source"), "automatic");
        Assert.assertEquals(row.getString("reasonCode"), "script_scan");
        Assert.assertEquals(row.getString("reason"), "批量脚本扫描");
        Assert.assertEquals(row.getLong("created"), 1_000_000L);
        Assert.assertEquals(row.getLong("updated"), clock.get());
        Assert.assertEquals(row.getLong("expiresAt"), 1_000_000L + 86_400_000L);
        service.update("8.8.8.8", "", "9.9.9.9", 3L * 86400);
        Assert.assertEquals(service.page(1, 10, "").getJSONArray("rules").getJSONObject(0).getLong("expiresAt"), clock.get() + 3L * 86_400_000);
        service.update("8.8.8.8", "永久", "9.9.9.9", 0L);
        Assert.assertEquals(service.page(1, 10, "").getJSONArray("rules").getJSONObject(0).getLong("expiresAt"), 0L);
    }
    @Test public void expiredBansCanBeReactivatedWithValidationAndDurableDeadlines() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final JSONObject[] stored = {null}; final IpBanService service = create(clock, stored);
        service.add("8.8.8.0/24", "人工确认", "9.9.9.9", 86400);
        clock.addAndGet(86_400_000);
        Assert.assertFalse(service.blocked("8.8.8.8"));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("8.8.8.0/24", "", "8.8.8.8", 86400L));
        service.update("8.8.8.0/24", "仅修改备注", "9.9.9.9", null);
        Assert.assertFalse(service.blocked("8.8.8.8"));
        service.update("8.8.8.0/24", "再次生效", "9.9.9.9", 7L * 86400);
        final IpBanService reloaded = create(clock, stored);
        Assert.assertTrue(reloaded.blocked("8.8.8.8"));
        Assert.assertEquals(reloaded.page(1, 10, "", "manual", "active").getInt("total"), 1);
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("1.1.1.1", "", "9.9.9.9", 0L));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("8.8.8.0/24", "x".repeat(201), "9.9.9.9", 0L));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("8.8.8.0/24", "", "9.9.9.9", -1L));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("8.8.8.0/24", "", "9.9.9.9", Long.MAX_VALUE));
    }
    @Test public void repeatedAutomaticBansReuseHistoryAndEscalateAfterExpiryOnly() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final JSONObject[] stored = {null}; IpBanService service = create(clock, stored);
        final var trigger = new AttackTracker.Trigger("script_scan", "批量脚本扫描");
        service.automatic("8.8.8.8", trigger);
        for (int count = 1; count <= 5; count++) {
            final JSONObject row = service.page(1, 10, "", "automatic", "active").getJSONArray("rules").getJSONObject(0);
            final long days = count == 1 ? 1 : count == 2 ? 3 : count == 3 ? 7 : 30;
            Assert.assertEquals(row.getInt("occurrences"), count);
            Assert.assertEquals(row.getLong("created"), 1_000_000L);
            Assert.assertEquals(row.getLong("expiresAt"), clock.get() + days * 86_400_000L);
            service.automatic("8.8.8.8", trigger);
            Assert.assertEquals(service.list().length(), 1);
            Assert.assertEquals(service.list().getJSONObject(0).getInt("occurrences"), count);
            clock.set(row.getLong("expiresAt"));
            service = create(clock, stored);
            Assert.assertFalse(service.blocked("8.8.8.8"));
            service.automatic("8.8.8.8", trigger);
        }
        clock.set(service.list().getJSONObject(0).getLong("expiresAt"));
        service.automatic("8.8.8.8", new AttackTracker.Trigger("xss_payload", "评论 XSS"));
        Assert.assertEquals(service.list().length(), 1);
        Assert.assertEquals(service.list().getJSONObject(0).getLong("expiresAt"), 0L);
        Assert.assertEquals(service.list().getJSONObject(0).getString("reasonCode"), "xss_payload");
    }

    @Test public void administratorsCanCorrectOrResetAutomaticCountsWithoutChangingTheDeadline() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final JSONObject[] stored = {null}; final IpBanService service = create(clock, stored);
        final var trigger = new AttackTracker.Trigger("script_scan", "扫描");
        service.automatic("8.8.8.8", trigger);
        service.update("8.8.8.8", "复核", "9.9.9.9", null, 3);
        final long expires = service.list().getJSONObject(0).getLong("expiresAt");
        Assert.assertEquals(service.list().getJSONObject(0).getInt("occurrences"), 3);
        service.update("8.8.8.8", "已清空次数", "9.9.9.9", null, 0);
        Assert.assertEquals(service.list().getJSONObject(0).getLong("expiresAt"), expires);
        Assert.assertEquals(create(clock, stored).list().getJSONObject(0).getInt("occurrences"), 0);
        clock.set(expires); service.automatic("8.8.8.8", trigger);
        Assert.assertEquals(service.list().getJSONObject(0).getInt("occurrences"), 1);
        Assert.assertEquals(service.list().getJSONObject(0).getLong("expiresAt"), expires + 86_400_000L);
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("8.8.8.8", "", "9.9.9.9", null, -1));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("8.8.8.8", "", "9.9.9.9", null, 1_000_001));
        service.add("1.1.1.1", "人工", "9.9.9.9");
        Assert.expectThrows(IllegalArgumentException.class, () -> service.update("1.1.1.1", "", "9.9.9.9", null, 0));
        service.remove("8.8.8.8"); service.automatic("8.8.8.8", trigger);
        Assert.assertEquals(service.list().getJSONObject(1).getInt("occurrences"), 1);
    }

    private static IpBanService create(final AtomicLong clock, final JSONObject[] stored) throws Exception {
        final IpBanService service = new IpBanService() { @Override protected long now() { return clock.get(); } };
        inject(service, "optionQueryService", new OptionQueryService() { @Override public JSONObject getOptionById(String id) { return stored[0]; } });
        inject(service, "optionMgmtService", new OptionMgmtService() { @Override public void storeDocument(String id, String category, String value) { stored[0] = new JSONObject().put("oId", id).put(Option.OPTION_CATEGORY, category).put(Option.OPTION_VALUE, value); } });
        return service;
    }
    private static void inject(Object target, String name, Object value) throws Exception { final Field field = IpBanService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
