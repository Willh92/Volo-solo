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
package org.b3log.solo.service;

import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Locales;
import org.b3log.solo.bolo.prop.MailService;
import org.b3log.solo.bolo.prop.Options;
import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.*;

/**
 * Preference management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.0
 */
@Service
public class PreferenceMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PreferenceMgmtService.class);

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Crontab management service.
     */
    @Inject
    private CronMgmtService cronMgmtService;
    public static final String CLEAR_SECRET_SENTINEL = "__VOLO_CLEAR_SECRET__";

    public static int secretIndexForTuChuang(final String type) {
        if (type == null) {
            return -1;
        }
        return switch (type) {
            case "picuang", "qiniu", "aliyun", "tencent" -> 2;
            case "upyun" -> 3;
            default -> -1;
        };
    }

    public static boolean hasTuChuangSecret(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        final String[] parts = raw.split("<<>>", -1);
        final int idx = secretIndexForTuChuang(parts[0]);
        return idx > 0 && idx < parts.length && !parts[idx].isEmpty();
    }

    public static String redactTuChuangConfig(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        final String[] parts = raw.split("<<>>", -1);
        final int idx = secretIndexForTuChuang(parts[0]);
        if (idx > 0 && idx < parts.length && !parts[idx].isEmpty()) {
            parts[idx] = "";
            return String.join("<<>>", parts);
        }
        return raw;
    }

    public static String mergeTuChuangConfig(final String incoming, final String stored) {
        if (incoming == null || incoming.isEmpty()) {
            return stored == null ? "" : stored;
        }
        final String[] inParts = incoming.split("<<>>", -1);
        final int idx = secretIndexForTuChuang(inParts[0]);
        if (idx < 0 || idx >= inParts.length) {
            return incoming;
        }
        if (CLEAR_SECRET_SENTINEL.equals(inParts[idx])) {
            inParts[idx] = "";
            return String.join("<<>>", inParts);
        }
        if (inParts[idx].isEmpty() && stored != null && !stored.isEmpty()) {
            final String[] oldParts = stored.split("<<>>", -1);
            if (oldParts.length > idx && inParts[0].equals(oldParts[0]) && !oldParts[idx].isEmpty()) {
                inParts[idx] = oldParts[idx];
                return String.join("<<>>", inParts);
            }
        }
        return incoming;
    }

    @Inject
    private CommentFilterService commentFilterService;

    /**
     * Updates the preference with the specified preference.
     *
     * @param preference the specified preference
     * @throws ServiceException service exception
     */
    public synchronized void updatePreference(final JSONObject preference) throws ServiceException {
        preference.put(Option.ID_C_HELP_IMPROVE_PLAN, "false");
        final JSONObject oldPreference = optionQueryService.getPreference();
        final String loginEntry = Option.normalizeBooleanSwitch(
                preference.has(Option.ID_C_SHOW_LOGIN_ENTRY) ? preference.opt(Option.ID_C_SHOW_LOGIN_ENTRY) : null,
                Option.optBooleanSwitch(oldPreference, Option.ID_C_SHOW_LOGIN_ENTRY, false),
                "前端登录入口开关不正确。");
        preference.put(Option.ID_C_SHOW_LOGIN_ENTRY, loginEntry);
        final String comments = Option.normalizeBooleanSwitch(
                preference.has(Option.ID_C_INTERACTIVE) ? preference.opt(Option.ID_C_INTERACTIVE) : null,
                Option.optBooleanSwitch(oldPreference, Option.ID_C_INTERACTIVE, true),
                "前端评论开关不正确。");
        preference.put(Option.ID_C_INTERACTIVE, comments);
        if (!preference.has(Option.ID_C_COMMENT_FILTER_BUILTIN)) {
            preference.put(Option.ID_C_COMMENT_FILTER_BUILTIN,
                    Boolean.toString(Option.optBooleanSwitch(oldPreference, Option.ID_C_COMMENT_FILTER_BUILTIN, true)));
        }
        if (!preference.has(Option.ID_C_COMMENT_FILTER_ALLOW)) {
            preference.put(Option.ID_C_COMMENT_FILTER_ALLOW, oldPreference.optString(Option.ID_C_COMMENT_FILTER_ALLOW, ""));
        }
        // Settings moved out of the preference form must survive unrelated preference saves.
        for (final String key : new String[]{Option.ID_C_WAF_POWER, Option.ID_C_WAF_CURRENT_LIMIT_SECOND,
                Option.ID_C_WAF_CURRENT_LIMIT_TIMES, Option.ID_C_SPAM,
                Option.ID_C_ALLOW_REGISTER, Option.ID_C_COMMENT_ACCESS_MODE, Option.ID_C_COMMENT_MODERATION_MODE,
                Option.ID_C_ARTICLE_OUTDATED_DAYS, Option.ID_C_ENABLE_READING_STATS, Option.ID_C_ENABLE_INSTANT_SEARCH,
                Option.ID_C_LOCAL_BACKUP_AUTO, Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, Option.ID_C_LOCAL_BACKUP_DIR}) {
            if (!preference.has(key)) {
                final String val = switch (key) {
                    case Option.ID_C_WAF_POWER, Option.ID_C_ENABLE_READING_STATS, Option.ID_C_ENABLE_INSTANT_SEARCH ->
                            Boolean.toString(Option.optBooleanSwitch(oldPreference, key, true));
                    case Option.ID_C_ALLOW_REGISTER, Option.ID_C_LOCAL_BACKUP_AUTO ->
                            Boolean.toString(Option.optBooleanSwitch(oldPreference, key, false));
                    case Option.ID_C_COMMENT_ACCESS_MODE -> oldPreference.optString(key, "all");
                    case Option.ID_C_ARTICLE_OUTDATED_DAYS -> oldPreference.optString(key, "365");
                    case Option.ID_C_LOCAL_BACKUP_KEEP_COUNT -> oldPreference.optString(key, "7");
                    case Option.ID_C_COMMENT_MODERATION_MODE -> oldPreference.optString(key, "off");
                    default -> oldPreference.optString(key, "");
                };
                preference.put(key, val);
            }
        }
        if ("true".equals(comments)
                && !Option.optBooleanSwitch(oldPreference, Option.ID_C_INTERACTIVE, true)
                && "off".equals(preference.optString(Option.ID_C_COMMENT_ACCESS_MODE))) {
            preference.put(Option.ID_C_COMMENT_ACCESS_MODE, "all");
        }
        preference.put(Option.ID_C_ALLOW_REGISTER, Option.normalizeBooleanSwitch(
                preference.opt(Option.ID_C_ALLOW_REGISTER),
                Option.optBooleanSwitch(oldPreference, Option.ID_C_ALLOW_REGISTER, false),
                "读者注册开关配置不正确。"));
        for (final String trueToggleKey : new String[]{
                Option.ID_C_ENABLE_READING_STATS,
                Option.ID_C_ENABLE_INSTANT_SEARCH,
                Option.ID_C_ADMIN_ACTIVE_SENT_TO_MAILBOX,
                Option.ID_C_COMMENTABLE,
                Option.ID_C_SYNC_GITHUB,
                Option.ID_C_PULL_GITHUB
        }) {
            if (preference.has(trueToggleKey)) {
                preference.put(trueToggleKey, Option.normalizeBooleanSwitch(
                        preference.opt(trueToggleKey),
                        Option.optBooleanSwitch(oldPreference, trueToggleKey, true),
                        "偏好开关配置不正确。"));
            }
        }
        for (final String falseToggleKey : new String[]{
                Option.ID_C_LOCAL_BACKUP_AUTO,
                Option.ID_C_SHOW_CODE_BLOCK_LN,
                Option.ID_C_ENABLE_ARTICLE_UPDATE_HINT,
                Option.ID_C_ALLOW_VISIT_DRAFT_VIA_PERMALINK,
                Option.ID_C_WELFARE_LUTE_SERVICE,
                Option.ID_C_ENABLE_AUTO_FLUSH_GITHUB,
                Option.ID_C_ENABLE_AUTO_FLUSH_BLOG_TO_GITHUB_PROFILE
        }) {
            if (preference.has(falseToggleKey)) {
                preference.put(falseToggleKey, Option.normalizeBooleanSwitch(
                        preference.opt(falseToggleKey),
                        Option.optBooleanSwitch(oldPreference, falseToggleKey, false),
                        "偏好开关配置不正确。"));
            }
        }
        final String rawOutdatedDays = preference.optString(Option.ID_C_ARTICLE_OUTDATED_DAYS, "365").strip();
        final String outdatedDays = rawOutdatedDays.isEmpty() ? "365" : rawOutdatedDays;
        if (!outdatedDays.matches("[0-9]{1,4}") || Integer.parseInt(outdatedDays) > 3650) {
            throw new IllegalArgumentException("陈旧文章提醒天数应为 0 至 3650 的整数。");
        }
        preference.put(Option.ID_C_ARTICLE_OUTDATED_DAYS, outdatedDays);
        final String rawKeepCount = preference.optString(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, "7").strip();
        final String keepCount = rawKeepCount.isEmpty() ? "7" : rawKeepCount;
        if (!keepCount.matches("[1-9][0-9]{0,2}") || Integer.parseInt(keepCount) > 100) {
            throw new IllegalArgumentException("本地自动备份保留份数应为 1 至 100 的整数。");
        }
        preference.put(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, keepCount);
        final String backupDir = preference.optString(Option.ID_C_LOCAL_BACKUP_DIR, "").strip();
        if (backupDir.length() > 255 || backupDir.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("本地备份目录路径格式不正确。");
        }
        preference.put(Option.ID_C_LOCAL_BACKUP_DIR, backupDir);
        for (final String secretKey : new String[]{Option.ID_C_MAIL_PASSWORD, Option.ID_C_GITHUB_PAT,
                Option.ID_C_B3LOG_KEY, Option.ID_C_FISH_KEY, Option.ID_C_SEND_KEY}) {
            final String incomingSecret = preference.optString(secretKey);
            if (CLEAR_SECRET_SENTINEL.equals(incomingSecret) || preference.optBoolean(secretKey + "Clear")) {
                preference.put(secretKey, "");
            } else if (incomingSecret.isEmpty()) {
                preference.put(secretKey, oldPreference.optString(secretKey));
            }
        }
        if (preference.has(Option.ID_C_TUCHUANG_CONFIG)) {
            preference.put(Option.ID_C_TUCHUANG_CONFIG, mergeTuChuangConfig(
                    preference.optString(Option.ID_C_TUCHUANG_CONFIG),
                    oldPreference.optString(Option.ID_C_TUCHUANG_CONFIG)));
        } else if (oldPreference.has(Option.ID_C_TUCHUANG_CONFIG)) {
            preference.put(Option.ID_C_TUCHUANG_CONFIG, oldPreference.optString(Option.ID_C_TUCHUANG_CONFIG));
        }
        for (final String key : org.b3log.solo.bolo.prop.SmtpSettings.KEYS) {
            if (!preference.has(key)) preference.put(key, oldPreference.optString(key));
        }
        org.b3log.solo.bolo.prop.SmtpSettings.read(preference).fill(preference);
        if ("true".equals(preference.optString(Option.ID_C_ALLOW_REGISTER))
                && !Option.optBooleanSwitch(oldPreference, Option.ID_C_ALLOW_REGISTER, false)
                && !org.b3log.solo.bolo.prop.SmtpSettings.read(preference).configured()) {
            throw new IllegalArgumentException("开启公开读者注册前，请先在「偏好设定 → 动态提醒」中配置有效的 SMTP 发信参数。");
        }
        // Compile before opening a database transaction. Publish only after a successful commit.
        final CommentFilterService.Prepared filter = commentFilterService.prepare(preference);
        final Iterator<String> keys = preference.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (preference.isNull(key)) {
                LOGGER.log(Level.ERROR, "A value is null of preference [key=" + key + "]");
            }
        }

        final Transaction transaction = optionRepository.beginTransaction();

        try {
            preference.put(Option.ID_C_SIGNS, preference.get(Option.ID_C_SIGNS).toString());

            final String version = oldPreference.optString(Option.ID_C_VERSION);
            preference.put(Option.ID_C_VERSION, version);

            final String localeString = preference.getString(Option.ID_C_LOCALE_STRING);
            Latkes.setLocale(new Locale(Locales.getLanguage(localeString), Locales.getCountry(localeString)));

            List<String> optionList = Options.loadOptions();
            for (String i : optionList) {
                if (preference.has(i)) emptyPreferenceOptSave(i, preference.optString(i));
            }

            transaction.commit();
            commentFilterService.activate(filter);

            final String showCodeBlockLnVal = preference.optString(Option.ID_C_SHOW_CODE_BLOCK_LN);
            Markdowns.SHOW_CODE_BLOCK_LN = "true".equalsIgnoreCase(showCodeBlockLnVal);

            LOGGER.log(Level.INFO, "Reloading settings ...");
            Markdowns.clearCache();
            WAF.set();
            MailService.loadMailSettings();
            Solos.enableWelfareLuteService();
            cronMgmtService.restart();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Updates preference failed", e);
            throw new ServiceException(langPropsService.get("updateFailLabel"));
        }

        LOGGER.log(Level.DEBUG, "Updates preference successfully");
    }

    private void emptyPreferenceOptSave(final String optID, final String val) throws Exception {
        // 该方法用于向后兼容，如果数据库中不存在该配置项则创建再保存
        JSONObject opt = optionRepository.get(optID);
        if (null == opt) {
            opt = new JSONObject();
            opt.put(Keys.OBJECT_ID, optID);
            opt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
            opt.put(Option.OPTION_VALUE, val);
            optionRepository.add(opt);
        } else {
            opt.put(Option.OPTION_VALUE, val);
            optionRepository.update(optID, opt);
        }
    }

    public static final java.util.Set<String> SECURITY_KEYS = java.util.Set.of("riskControlEnabled", "commentCaptchaEnabled", "adminLoginPath",
            Option.ID_C_WAF_POWER, Option.ID_C_WAF_CURRENT_LIMIT_SECOND, Option.ID_C_WAF_CURRENT_LIMIT_TIMES,
            Option.ID_C_SPAM, Option.ID_C_COMMENT_FILTER_BUILTIN, Option.ID_C_COMMENT_FILTER_ALLOW,
            Option.ID_C_COMMENT_ACCESS_MODE, Option.ID_C_COMMENT_MODERATION_MODE);

    /** Independent updates cannot overwrite an unrelated, stale preferences form. */
    public synchronized void updateSecurity(final JSONObject values) throws Exception {
        for (final String key : values.keySet()) {
            if (!SECURITY_KEYS.contains(key) || !(values.opt(key) instanceof String)) throw new IllegalArgumentException("安全配置字段不正确。");
        }
        for (final String key : new String[]{"riskControlEnabled", "commentCaptchaEnabled", Option.ID_C_WAF_POWER,
                Option.ID_C_COMMENT_FILTER_BUILTIN}) {
            if (values.has(key) && !java.util.Set.of("true", "false").contains(values.getString(key))) throw new IllegalArgumentException("安全开关配置不正确。");
        }
        if (values.has(Option.ID_C_COMMENT_ACCESS_MODE)
                && !java.util.Set.of("all", "registered", "off").contains(values.getString(Option.ID_C_COMMENT_ACCESS_MODE))) {
            throw new IllegalArgumentException("评论开放权限配置不正确。");
        }
        if (values.has(Option.ID_C_COMMENT_MODERATION_MODE)
                && !java.util.Set.of("off", "smart", "all").contains(values.getString(Option.ID_C_COMMENT_MODERATION_MODE))) {
            throw new IllegalArgumentException("评论审核模式配置不正确。");
        }
        if (values.has("adminLoginPath")) {
            final String path = org.b3log.solo.util.AdminPaths.validateLogin(values.getString("adminLoginPath"));
            final String effective = path.isEmpty() ? "/start" : path;
            final var beans = org.b3log.latke.ioc.BeanManager.getInstance();
            if (beans.getReference(org.b3log.solo.repository.ArticleRepository.class).getByPermalink(effective) != null
                    || beans.getReference(org.b3log.solo.repository.PageRepository.class).getByPermalink(effective) != null) {
                throw new IllegalArgumentException("登录路径已被文章或导航页面使用。");
            }
            values.put("adminLoginPath", path);
        }
        for (final String key : new String[]{Option.ID_C_WAF_CURRENT_LIMIT_SECOND, Option.ID_C_WAF_CURRENT_LIMIT_TIMES}) {
            if (values.has(key) && (!values.getString(key).matches("[1-9][0-9]{0,5}"))) throw new IllegalArgumentException("访问次数和时间范围应为 1 至 999999 的整数。");
        }
        final JSONObject merged = new JSONObject(optionQueryService.getPreference().toString());
        if (!values.has(Option.ID_C_COMMENT_FILTER_BUILTIN)) {
            merged.put(Option.ID_C_COMMENT_FILTER_BUILTIN,
                    Boolean.toString(Option.optBooleanSwitch(merged, Option.ID_C_COMMENT_FILTER_BUILTIN, true)));
        }
        for (final String key : values.keySet()) merged.put(key, values.get(key));
        CommentFilterService.validate(merged);
        final var filter = commentFilterService.prepare(merged);
        final var transaction = optionRepository.beginTransaction();
        try {
            for (final String key : values.keySet()) emptyPreferenceOptSave(key, values.getString(key));
            if (values.has(Option.ID_C_COMMENT_ACCESS_MODE)) {
                emptyPreferenceOptSave(Option.ID_C_INTERACTIVE,
                        "off".equals(values.getString(Option.ID_C_COMMENT_ACCESS_MODE)) ? "false" : "true");
            }
            transaction.commit();
        } catch (Exception failure) { if (transaction.isActive()) transaction.rollback(); throw failure; }
        commentFilterService.activate(filter);
        if (values.has("riskControlEnabled")) org.b3log.latke.ioc.BeanManager.getInstance().getReference(RiskControlService.class).configure("true".equals(values.getString("riskControlEnabled")));
        if (values.has("commentCaptchaEnabled")) org.b3log.latke.ioc.BeanManager.getInstance().getReference(CaptchaService.class).configureComments("true".equals(values.getString("commentCaptchaEnabled")));
        if (values.has("adminLoginPath")) org.b3log.solo.util.AdminPaths.configureLogin(values.getString("adminLoginPath"));
        WAF.set();
    }
}
