/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import org.b3log.solo.bolo.prop.SmtpSettings;
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
        final JSONObject stored = new JSONObject().put("mailBox", "QQ").put("mailUsername", "sender@example.test").put("mailPassword", "secret");
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
            Assert.assertEquals(data.getString("smtpHost"), "smtp.qq.com");
            Assert.assertEquals(stored.getString("mailPassword"), "secret");
            Assert.assertFalse(stored.has("smtpHost"), "Read-time legacy conversion must not mutate cached settings");
        } finally { request.release(); response.release(); }
    }
}
