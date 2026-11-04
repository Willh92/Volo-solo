/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import org.b3log.solo.bolo.prop.SmtpSettings;
import org.b3log.solo.model.Option;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SmtpSettingsTest {
    @Test public void customSmtpSupportsTlsStartTlsAndInternalRelays() {
        final JSONObject data = new JSONObject().put("smtpHost", "smtp.example.test").put("mailUsername", "sender@example.test").put("mailPassword", "secret");
        final SmtpSettings ssl = SmtpSettings.read(data);
        Assert.assertEquals(ssl.port(), 465); Assert.assertTrue(ssl.configured());
        Assert.assertEquals(ssl.properties().getProperty("mail.smtp.ssl.checkserveridentity"), "true");
        final SmtpSettings tls = SmtpSettings.read(data.put("smtpSecurity", "starttls"));
        Assert.assertEquals(tls.port(), 587); Assert.assertEquals(tls.properties().getProperty("mail.smtp.starttls.required"), "true");
        final SmtpSettings relay = SmtpSettings.read(new JSONObject().put("smtpHost", "127.0.0.1").put("smtpAuth", "off").put("smtpSecurity", "none").put("smtpFrom", "notifications@example.test"));
        Assert.assertTrue(relay.configured()); Assert.assertEquals(relay.port(), 25);
    }
    @Test public void legacyProviderSettingsConvertWithoutDatabaseMigration() {
        for (String legacy : new String[]{"QQ", "QQ_ENT", "163"}) {
            final JSONObject data = new JSONObject().put("mailBox", legacy).put("mailUsername", "sender@example.test").put("mailPassword", "secret");
            final SmtpSettings settings = SmtpSettings.read(data); settings.fill(data);
            Assert.assertFalse(settings.host().isBlank()); Assert.assertTrue(settings.configured());
            Assert.assertTrue(data.has("smtpHost")); Assert.assertEquals(data.getString("smtpPort"), "465");
        }
        Assert.assertFalse(SmtpSettings.read(new JSONObject()).configured());
    }
    @Test public void invalidPortsHeadersAndTransportOptionsAreRejected() {
        for (JSONObject data : new JSONObject[]{new JSONObject().put("smtpPort", "0"), new JSONObject().put("smtpPort", "65536"),
                new JSONObject().put("smtpSecurity", "unknown"), new JSONObject().put("smtpHost", "x\r\nInjected: true"),
                new JSONObject().put("smtpHost", "https://smtp.example.com"), new JSONObject().put("smtpHost", "example.com").put("smtpFrom", "a@example.com\r\nb@example.com")}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> SmtpSettings.read(data));
        }
    }
    @Test public void readingPreferencesRedactsTheResponseWithoutChangingCachedMailCredentials() throws Exception {
        LatkeTestSupport.configure();
        final JSONObject stored = new JSONObject()
                .put("mailBox", "QQ")
                .put("mailUsername", "sender@example.test")
                .put("mailPassword", "secret")
                .put(Option.ID_C_GITHUB_PAT, "ghp_secretToken")
                .put(Option.ID_C_B3LOG_KEY, "custom-b3-key")
                .put(Option.ID_C_FISH_KEY, "fish-secret")
                .put(Option.ID_C_SEND_KEY, "SCT-secret")
                .put(Option.ID_C_TUCHUANG_CONFIG, "qiniu<<>>myAK<<>>mySuperSecret<<>>myBucket<<>>https://img.example.test<<>>");
        final var processor = new org.b3log.solo.processor.console.PreferenceConsole();
        final var options = processor.getClass().getDeclaredField("optionQueryService");
        options.setAccessible(true);
        options.set(processor, new org.b3log.solo.service.OptionQueryService() {
            @Override public JSONObject getPreference() { return stored; }
            @Override public JSONObject getOptionById(String id) { return null; }
        });
        final var request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.GET, "/console/preference/");
        final var response = new io.netty.handler.codec.http.DefaultFullHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpResponseStatus.OK);
        try {
            final var context = new org.b3log.latke.http.RequestContext(new org.b3log.latke.http.Request(null, request), new org.b3log.latke.http.Response(null, response));
            processor.getPreference(context);
            final JSONObject data = ((org.b3log.latke.http.renderer.JsonRenderer) context.getRenderer()).getJSONObject().getJSONObject("preference");
            Assert.assertEquals(data.getString("mailPassword"), "");
            Assert.assertTrue(data.getBoolean("smtpPasswordSet"));
            Assert.assertEquals(data.getString(Option.ID_C_GITHUB_PAT), "");
            Assert.assertTrue(data.getBoolean("githubPATSet"));
            Assert.assertEquals(data.getString(Option.ID_C_B3LOG_KEY), "");
            Assert.assertTrue(data.getBoolean("b3logKeySet"));
            Assert.assertEquals(data.getString(Option.ID_C_FISH_KEY), "");
            Assert.assertTrue(data.getBoolean("fishKeySet"));
            Assert.assertEquals(data.getString(Option.ID_C_SEND_KEY), "");
            Assert.assertTrue(data.getBoolean("sendKeySet"));
            Assert.assertEquals(data.getString(Option.ID_C_TUCHUANG_CONFIG), "qiniu<<>>myAK<<>><<>>myBucket<<>>https://img.example.test<<>>");
            Assert.assertTrue(data.getBoolean("tuChuangSecretSet"));
            Assert.assertEquals(data.getString("smtpHost"), "smtp.qq.com");
            Assert.assertEquals(stored.getString("mailPassword"), "secret");
            Assert.assertEquals(stored.getString(Option.ID_C_TUCHUANG_CONFIG), "qiniu<<>>myAK<<>>mySuperSecret<<>>myBucket<<>>https://img.example.test<<>>");
            Assert.assertEquals(stored.getString(Option.ID_C_GITHUB_PAT), "ghp_secretToken");
            Assert.assertEquals(stored.getString(Option.ID_C_B3LOG_KEY), "custom-b3-key");
            Assert.assertEquals(stored.getString(Option.ID_C_FISH_KEY), "fish-secret");
            Assert.assertEquals(stored.getString(Option.ID_C_SEND_KEY), "SCT-secret");
            Assert.assertFalse(stored.has("smtpHost"), "Read-time legacy conversion must not mutate cached settings");
        } finally { request.release(); response.release(); }
    }

    @Test public void tuChuangSecretsRedactRestoreOnBlankAndClearOnSentinel() {
        final String storedQiniu = "qiniu<<>>ak1<<>>sk-secret<<>>bucket-a<<>>https://cdn.example.test<<>>";
        Assert.assertTrue(org.b3log.solo.service.PreferenceMgmtService.hasTuChuangSecret(storedQiniu));
        final String redactedQiniu = org.b3log.solo.service.PreferenceMgmtService.redactTuChuangConfig(storedQiniu);
        Assert.assertEquals(redactedQiniu, "qiniu<<>>ak1<<>><<>>bucket-a<<>>https://cdn.example.test<<>>");
        Assert.assertFalse(org.b3log.solo.service.PreferenceMgmtService.hasTuChuangSecret(redactedQiniu));

        // Saving unrelated preferences or non-secret fields with blank secret slot restores existing secret
        Assert.assertEquals(
                org.b3log.solo.service.PreferenceMgmtService.mergeTuChuangConfig("qiniu<<>>ak1<<>><<>>bucket-b<<>>https://cdn.example.test<<>>", storedQiniu),
                "qiniu<<>>ak1<<>>sk-secret<<>>bucket-b<<>>https://cdn.example.test<<>>");

        // Switching provider never copies the previous provider's secret
        Assert.assertEquals(
                org.b3log.solo.service.PreferenceMgmtService.mergeTuChuangConfig("aliyun<<>>aliAK<<>><<>>oss-cn<<>>https://oss.example.test<<>>bucket<<>>dir<<>>", storedQiniu),
                "aliyun<<>>aliAK<<>><<>>oss-cn<<>>https://oss.example.test<<>>bucket<<>>dir<<>>");

        // Upyun stores operator password at index 3
        final String storedUpyun = "upyun<<>>bucket<<>>operator<<>>up-secret<<>>https://up.example.test<<>>";
        Assert.assertEquals(
                org.b3log.solo.service.PreferenceMgmtService.redactTuChuangConfig(storedUpyun),
                "upyun<<>>bucket<<>>operator<<>><<>>https://up.example.test<<>>");
        Assert.assertEquals(
                org.b3log.solo.service.PreferenceMgmtService.mergeTuChuangConfig(
                        "upyun<<>>bucket<<>>operator<<>>" + org.b3log.solo.service.PreferenceMgmtService.CLEAR_SECRET_SENTINEL + "<<>>https://up.example.test<<>>",
                        storedUpyun),
                "upyun<<>>bucket<<>>operator<<>><<>>https://up.example.test<<>>");
    }
}
