/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.bolo.prop;

import java.util.Properties;
import java.util.Set;
import org.json.JSONObject;

/** Explicit SMTP transport settings, with read compatibility for old provider selections. */
public record SmtpSettings(String host, int port, String security, boolean auth, String username, String password, String from) {
    public static final Set<String> KEYS = Set.of("smtpHost", "smtpPort", "smtpSecurity", "smtpAuth", "smtpFrom");
    public static SmtpSettings read(final JSONObject data) {
        String host = data.optString("smtpHost").strip();
        String security = data.optString("smtpSecurity", "ssl");
        if (host.isEmpty() && !data.optString("mailBox").isEmpty()) {
            host = switch (data.optString("mailBox")) { case "QQ" -> "smtp.qq.com"; case "QQ_ENT" -> "smtp.exmail.qq.com"; case "163" -> "smtp.163.com"; default -> ""; };
            security = "ssl";
        }
        if (security.isBlank()) security = "ssl";
        if (!Set.of("ssl", "starttls", "none").contains(security)) throw new IllegalArgumentException("SMTP 加密方式不正确。");
        final String portText = data.optString("smtpPort").strip();
        final int port;
        try { port = portText.isEmpty() ? security.equals("ssl") ? 465 : security.equals("starttls") ? 587 : 25 : Integer.parseInt(portText); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("SMTP 端口应为 1 至 65535。"); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("SMTP 端口应为 1 至 65535。");
        if (host.length() > 253 || !host.isEmpty() && !host.matches("[A-Za-z0-9_.:\\[\\]-]+")) throw new IllegalArgumentException("SMTP 服务器请填写主机名或 IP，不包含协议、路径或空白。");
        final String auth = data.optString("smtpAuth", "true").strip();
        if (!auth.isEmpty() && !Set.of("true", "false").contains(auth)) throw new IllegalArgumentException("SMTP 身份验证配置不正确。");
        final String user = data.optString("mailUsername").strip(), password = data.optString("mailPassword");
        if (user.length() > 254 || user.chars().anyMatch(Character::isISOControl) || password.length() > 1024) throw new IllegalArgumentException("SMTP 用户名或密码过长，或含有无效字符。");
        final String from = data.optString("smtpFrom", user).strip();
        final String sender = from.isEmpty() ? user : from;
        if (!host.isEmpty() && !sender.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) throw new IllegalArgumentException("请填写有效的 SMTP 发件人邮箱地址。");
        return new SmtpSettings(host, port, security, !"false".equals(auth), user, password, sender);
    }
    public boolean configured() { return !host.isBlank() && (!auth || !username.isBlank() && !password.isEmpty()); }
    public Properties properties() {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", host);
        properties.setProperty("mail.smtp.port", Integer.toString(port));
        properties.setProperty("mail.smtp.auth", Boolean.toString(auth));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(security.equals("ssl")));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(security.equals("starttls")));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(security.equals("starttls")));
        for (String key : new String[]{"connectiontimeout", "timeout", "writetimeout"}) properties.setProperty("mail.smtp." + key, "10000");
        return properties;
    }
    public void fill(final JSONObject data) {
        data.put("smtpHost", host).put("smtpPort", Integer.toString(port)).put("smtpSecurity", security)
                .put("smtpAuth", Boolean.toString(auth)).put("smtpFrom", from);
    }
}
