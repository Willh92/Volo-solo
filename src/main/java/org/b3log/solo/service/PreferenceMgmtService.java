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
        if (preference.has(Option.ID_C_SHOW_LOGIN_ENTRY) && !(preference.opt(Option.ID_C_SHOW_LOGIN_ENTRY) instanceof String)) {
            throw new ServiceException("前端登录入口开关不正确。");
        }
        final String loginEntry = preference.optString(Option.ID_C_SHOW_LOGIN_ENTRY,
                "on".equals(oldPreference.optString(Option.ID_C_SHOW_LOGIN_ENTRY)) ? "on" : "off");
        if (!java.util.Set.of("on", "off").contains(loginEntry)) throw new ServiceException("前端登录入口开关不正确。");
        preference.put(Option.ID_C_SHOW_LOGIN_ENTRY, loginEntry);
        if (preference.has(Option.ID_C_INTERACTIVE) && !(preference.opt(Option.ID_C_INTERACTIVE) instanceof String)) {
            throw new ServiceException("前端评论开关不正确。");
        }
        final String comments = preference.optString(Option.ID_C_INTERACTIVE,
                "off".equals(oldPreference.optString(Option.ID_C_INTERACTIVE)) ? "off" : "on");
        if (!java.util.Set.of("on", "off").contains(comments)) throw new ServiceException("前端评论开关不正确。");
        preference.put(Option.ID_C_INTERACTIVE, comments);
        for (final String filterKey : new String[]{Option.ID_C_COMMENT_FILTER_BUILTIN, Option.ID_C_COMMENT_FILTER_ALLOW}) {
            if (!preference.has(filterKey)) preference.put(filterKey, oldPreference.optString(filterKey,
                    filterKey.equals(Option.ID_C_COMMENT_FILTER_BUILTIN) ? "on" : ""));
        }
        // Settings moved out of the preference form must survive unrelated preference saves.
        for (final String key : new String[]{Option.ID_C_WAF_POWER, Option.ID_C_WAF_CURRENT_LIMIT_SECOND,
                Option.ID_C_WAF_CURRENT_LIMIT_TIMES, Option.ID_C_SPAM}) {
            if (!preference.has(key)) preference.put(key, oldPreference.optString(key));
        }
        if (preference.optString(Option.ID_C_MAIL_PASSWORD).isEmpty()) preference.put(Option.ID_C_MAIL_PASSWORD, oldPreference.optString(Option.ID_C_MAIL_PASSWORD));
        for (final String key : org.b3log.solo.bolo.prop.SmtpSettings.KEYS) {
            if (!preference.has(key)) preference.put(key, oldPreference.optString(key));
        }
        org.b3log.solo.bolo.prop.SmtpSettings.read(preference).fill(preference);
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
            Option.ID_C_SPAM, Option.ID_C_COMMENT_FILTER_BUILTIN, Option.ID_C_COMMENT_FILTER_ALLOW);

    /** Independent updates cannot overwrite an unrelated, stale preferences form. */
    public synchronized void updateSecurity(final JSONObject values) throws Exception {
        for (final String key : values.keySet()) {
            if (!SECURITY_KEYS.contains(key) || !(values.opt(key) instanceof String)) throw new IllegalArgumentException("安全配置字段不正确。");
        }
        for (final String key : new String[]{"riskControlEnabled", "commentCaptchaEnabled", Option.ID_C_WAF_POWER, Option.ID_C_COMMENT_FILTER_BUILTIN}) {
            if (values.has(key) && !java.util.Set.of("on", "off").contains(values.getString(key))) throw new IllegalArgumentException("安全开关配置不正确。");
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
        for (final String key : values.keySet()) merged.put(key, values.get(key));
        CommentFilterService.validate(merged);
        final var filter = commentFilterService.prepare(merged);
        final var transaction = optionRepository.beginTransaction();
        try {
            for (final String key : values.keySet()) emptyPreferenceOptSave(key, values.getString(key));
            transaction.commit();
        } catch (Exception failure) { if (transaction.isActive()) transaction.rollback(); throw failure; }
        commentFilterService.activate(filter);
        if (values.has("riskControlEnabled")) org.b3log.latke.ioc.BeanManager.getInstance().getReference(RiskControlService.class).configure("on".equals(values.getString("riskControlEnabled")));
        if (values.has("commentCaptchaEnabled")) org.b3log.latke.ioc.BeanManager.getInstance().getReference(CaptchaService.class).configureComments("on".equals(values.getString("commentCaptchaEnabled")));
        if (values.has("adminLoginPath")) org.b3log.solo.util.AdminPaths.configureLogin(values.getString("adminLoginPath"));
        WAF.set();
    }
}
