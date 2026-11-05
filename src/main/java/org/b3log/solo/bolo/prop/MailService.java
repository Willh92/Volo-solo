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
package org.b3log.solo.bolo.prop;

import io.github.biezhi.ome.OhMyEmail;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.bolo.prop.bind.MailBind;
import org.b3log.solo.cache.OptionCache;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h3>bolo-solo</h3>
 * <p>邮箱设定</p>
 *
 * @author : https://github.com/adlered
 * @date : 2019-12-21 16:44
 **/
@Singleton
public class MailService {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(MailService.class);
    private static volatile boolean configured;

    private record MailContextSnapshot(
            String rawValue,
            List<MailBind> list,
            Map<String, MailBind> byCommentId,
            Map<String, String> emailByCommentId) {
    }

    private static final MailContextSnapshot EMPTY_SNAPSHOT = new MailContextSnapshot(
            "", Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());

    private static volatile MailContextSnapshot cachedSnapshot = EMPTY_SNAPSHOT;

    public static boolean isConfigured() {
        return configured;
    }

    private static JSONObject readOption(final String optionId) {
        try {
            final BeanManager beanManager = BeanManager.getInstance();
            final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
            if (null != optionQueryService) {
                return optionQueryService.getOptionById(optionId);
            }
            final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);
            if (null != optionRepository) {
                return optionRepository.get(optionId);
            }
        } catch (final Exception ignored) {
        }
        return null;
    }

    private static void saveMailUserContextValue(final String newValue) throws Exception {
        final BeanManager beanManager = BeanManager.getInstance();
        final OptionMgmtService optionMgmtService = beanManager.getReference(OptionMgmtService.class);
        if (null != optionMgmtService) {
            final JSONObject opt = new JSONObject();
            opt.put(Keys.OBJECT_ID, Option.ID_C_MAIL_USER_CONTEXT);
            opt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
            opt.put(Option.OPTION_VALUE, newValue);
            optionMgmtService.addOrUpdateOption(opt);
            cachedSnapshot = parseSnapshot(newValue);
            return;
        }

        final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);
        if (null == optionRepository) {
            return;
        }
        final Transaction transaction = optionRepository.beginTransaction();
        try {
            JSONObject mailUserContextOpt = optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT);
            if (null == mailUserContextOpt) {
                mailUserContextOpt = new JSONObject();
                mailUserContextOpt.put(Keys.OBJECT_ID, Option.ID_C_MAIL_USER_CONTEXT);
                mailUserContextOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
                mailUserContextOpt.put(Option.OPTION_VALUE, newValue);
                optionRepository.add(mailUserContextOpt);
            } else {
                mailUserContextOpt.put(Option.OPTION_VALUE, newValue);
                optionRepository.update(Option.ID_C_MAIL_USER_CONTEXT, mailUserContextOpt);
            }
            transaction.commit();
            final OptionCache optionCache = beanManager.getReference(OptionCache.class);
            if (null != optionCache) {
                optionCache.putOption(mailUserContextOpt);
            }
            cachedSnapshot = parseSnapshot(newValue);
        } catch (final Exception e) {
            if (null != transaction && transaction.isActive()) {
                transaction.rollback();
            }
            throw e;
        }
    }

    private static MailContextSnapshot parseSnapshot(final String rawValue) {
        if (null == rawValue || rawValue.isEmpty()) {
            return EMPTY_SNAPSHOT;
        }
        final String[] perUser = rawValue.split(";");
        final List<MailBind> list = new ArrayList<>(perUser.length);
        final Map<String, MailBind> byId = new HashMap<>(perUser.length * 2);
        final Map<String, String> emailById = new HashMap<>(perUser.length * 2);
        for (final String entry : perUser) {
            if (entry.isEmpty()) {
                continue;
            }
            final int firstColon = entry.indexOf(':');
            if (firstColon <= 0) {
                continue;
            }
            final int secondColon = entry.indexOf(':', firstColon + 1);
            if (secondColon < 0) {
                continue;
            }
            final String commentId = entry.substring(0, firstColon);
            final String commentUser = entry.substring(firstColon + 1, secondColon);
            final String commentEmail = entry.substring(secondColon + 1);
            final MailBind mailBind = new MailBind();
            mailBind.setCommentId(commentId);
            mailBind.setCommentUser(commentUser);
            mailBind.setCommentEmail(commentEmail);
            list.add(mailBind);
            byId.put(commentId, mailBind);
            emailById.put(commentId, commentEmail);
        }
        if (list.isEmpty()) {
            return new MailContextSnapshot(rawValue, Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        }
        return new MailContextSnapshot(
                rawValue,
                Collections.unmodifiableList(list),
                Collections.unmodifiableMap(byId),
                Collections.unmodifiableMap(emailById));
    }

    private static MailContextSnapshot resolveSnapshot() {
        String raw = "";
        try {
            final JSONObject opt = readOption(Option.ID_C_MAIL_USER_CONTEXT);
            if (null != opt) {
                raw = opt.optString(Option.OPTION_VALUE, "");
            }
        } catch (final Exception ignored) {
        }
        final MailContextSnapshot current = cachedSnapshot;
        if (raw.equals(current.rawValue)) {
            return current;
        }
        final MailContextSnapshot parsed = parseSnapshot(raw);
        cachedSnapshot = parsed;
        return parsed;
    }

    /**
     * 添加指定用户评论的邮件提醒服务
     */
    public static void addCommentMailContext(final String commentId, final String commentUser, final String commentEmail) {
        String mailUserContext = "";
        final JSONObject existing = readOption(Option.ID_C_MAIL_USER_CONTEXT);
        if (null != existing) {
            mailUserContext = existing.optString(Option.OPTION_VALUE, "");
        }

        final StringBuilder bind = new StringBuilder(mailUserContext.length() + 64);
        bind.append(mailUserContext);
        if (!mailUserContext.isEmpty()) {
            bind.append(';');
        }
        bind.append(commentId)
                .append(':')
                .append(commentUser)
                .append(':')
                .append(commentEmail);

        try {
            saveMailUserContextValue(bind.toString());
        } catch (final Exception ignored) {
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.DEBUG, "Saved comment notification binding [commentId=" + commentId + "]");
        }
    }

    /**
     * 通过评论 Id 获取指定用户邮箱地址
     */
    public static String getEmailAddressByCommentId(final String commentId) {
        if (null == commentId || commentId.isEmpty()) {
            return "";
        }
        try {
            final JSONObject comment = BeanManager.getInstance()
                    .getReference(org.b3log.solo.repository.CommentRepository.class)
                    .get(commentId);
            if (comment != null && !comment.optString("commentNotifyEmail").isBlank()) {
                return comment.getString("commentNotifyEmail");
            }
        } catch (final Exception ignored) {
        }
        final MailBind bind = resolveSnapshot().byCommentId.get(commentId);
        return null == bind || null == bind.getCommentEmail() ? "" : bind.getCommentEmail();
    }

    public static String getUsernameByCommentId(final String commentId) {
        if (null == commentId || commentId.isEmpty()) {
            return "";
        }
        try {
            final JSONObject comment = BeanManager.getInstance()
                    .getReference(org.b3log.solo.repository.CommentRepository.class)
                    .get(commentId);
            if (comment != null) {
                return comment.optString(org.b3log.solo.model.Comment.COMMENT_NAME);
            }
        } catch (final Exception ignored) {
        }
        final MailBind bind = resolveSnapshot().byCommentId.get(commentId);
        return null == bind || null == bind.getCommentUser() ? "" : bind.getCommentUser();
    }

    /**
     * 将 Mail Context 转换为可视列表（命中不可变缓存快照）
     */
    public static List<MailBind> getUserMailContext() {
        return resolveSnapshot().list;
    }

    /**
     * Returns an immutable {@code commentId -> commentEmail} map backed by the cached mail-context snapshot.
     */
    public static Map<String, String> getUserMailEmailsByCommentId() {
        return resolveSnapshot().emailByCommentId;
    }

    /**
     * 重载邮件设置至内存
     */
    private static volatile String senderAddress = "";

    public static String senderAddress() {
        return senderAddress;
    }

    public static void loadMailSettings() {
        configured = false;
        try {
            final BeanManager beanManager = BeanManager.getInstance();
            final JSONObject existing = readOption(Option.ID_C_MAIL_USER_CONTEXT);
            if (null == existing) {
                try {
                    saveMailUserContextValue("");
                } catch (final Exception e) {
                    if (e instanceof RepositoryException re) {
                        throw re;
                    }
                }
            }

            final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
            final var settings = SmtpSettings.read(null == optionQueryService ? null : optionQueryService.getPreference());
            if (settings.configured()) {
                OhMyEmail.config(settings.properties(), settings.username(), settings.password());
                senderAddress = settings.from();
                configured = true;
            }
        } catch (final RepositoryException re) {
            LOGGER.log(Level.WARN, "Cannot load Mailbox Settings, please check the configuration.");
        } catch (final IllegalArgumentException | NullPointerException npe) {
            LOGGER.log(Level.WARN, "Cannot load Mailbox Settings, please check.");
        }
    }

    /**
     * 获取可视化邮件服务器设定
     */
    public static String getMailSet() {
        final JSONObject opt = readOption(Option.ID_C_MAIL_BOX);
        if (null == opt) {
            LOGGER.log(Level.WARN, "Cannot load Mailbox Settings, please check.");
            return "";
        }
        final String mailBox = opt.optString(Option.OPTION_VALUE);
        return "[mailBox=" + mailBox + ", configured=" + configured + "]";
    }

    /**
     * 清空指定用户评论的邮件提醒服务列表
     */
    @RequestProcessing(value = "/prop/mail/clear", method = {HttpMethod.POST})
    public void clearCommentMailContext(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) {
            return;
        }

        final BeanManager beanManager = BeanManager.getInstance();
        final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);

        final Transaction transaction = optionRepository.beginTransaction();
        try {
            final JSONObject mailUserContextOpt = optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT);
            if (mailUserContextOpt != null) {
                mailUserContextOpt.put(Option.OPTION_VALUE, "");
                optionRepository.update(Option.ID_C_MAIL_USER_CONTEXT, mailUserContextOpt);
            }
            final var comments = beanManager.getReference(org.b3log.solo.repository.CommentRepository.class);
            final Query notifyQuery = new Query()
                    .setFilter(new PropertyFilter("commentNotifyEmail", FilterOperator.NOT_EQUAL, ""))
                    .setPageCount(1);
            for (final JSONObject comment : comments.getList(notifyQuery)) {
                if (!comment.optString("commentNotifyEmail").isBlank()) {
                    comment.put("commentNotifyEmail", "");
                    comments.update(comment.getString(Keys.OBJECT_ID), comment, "commentNotifyEmail");
                }
            }
            transaction.commit();
            final OptionCache optionCache = beanManager.getReference(OptionCache.class);
            if (null != optionCache && null != mailUserContextOpt) {
                optionCache.putOption(mailUserContextOpt);
            }
            cachedSnapshot = EMPTY_SNAPSHOT;
            context.renderJSON(new JSONObject().put("sc", true).put("msg", "已清除评论通知邮箱绑定。"));
        } catch (final Exception failure) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            org.b3log.solo.util.AdminRequests.reject(context, 503, "清除失败，请重试。");
        }
    }
}
