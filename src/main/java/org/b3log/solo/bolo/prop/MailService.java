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
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.BeanManager;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.bolo.prop.bind.MailBind;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import org.b3log.latke.http.Response;
import java.util.ArrayList;
import java.util.List;

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
    public static boolean isConfigured() { return configured; }

    /**
     * 添加指定用户评论的邮件提醒服务
     *
     * @param commentId
     * @param commentUser
     * @param commentEmail
     */
    public static void addCommentMailContext(String commentId, String commentUser, String commentEmail) {
        final BeanManager beanManager = BeanManager.getInstance();
        final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);
        String mailUserContext = "";
        try {
            mailUserContext = optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT).optString(Option.OPTION_VALUE);
        } catch (Exception e) {
        }

        // Bind
        StringBuilder bind = new StringBuilder();
        bind.append(mailUserContext);
        if (!mailUserContext.isEmpty()) {
            bind.append(";");
        }
        bind.append(commentId)
                .append(":")
                .append(commentUser)
                .append(":")
                .append(commentEmail);
        // Write
        try {
            final Transaction transaction = optionRepository.beginTransaction();

            final JSONObject mailUserContextOpt = optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT);
            mailUserContextOpt.put(Option.OPTION_VALUE, bind.toString());
            optionRepository.update(Option.ID_C_MAIL_USER_CONTEXT, mailUserContextOpt);

            transaction.commit();
        } catch (RepositoryException RE) {
        }
        LOGGER.log(Level.DEBUG, "Saved comment notification binding [commentId=" + commentId + "]");
    }

    /**
     * 通过评论 Id 获取指定用户邮箱地址
     *
     * @param commentId
     * @return
     */
    public static String getEmailAddressByCommentId(String commentId) {
        try {
            final JSONObject comment = BeanManager.getInstance().getReference(org.b3log.solo.repository.CommentRepository.class).get(commentId);
            if (comment != null && !comment.optString("commentNotifyEmail").isBlank()) return comment.getString("commentNotifyEmail");
        } catch (Exception ignored) { }
        List<MailBind> mailBindList = getUserMailContext();
        for (int i = (mailBindList.size() - 1); i >= 0; i--) {
            MailBind mailBind = mailBindList.get(i);
            if (mailBind.getCommentId().equals(commentId)) {
                return mailBind.getCommentEmail();
            }
        }
        return "";
    }

    public static String getUsernameByCommentId(String commentId) {
        try {
            final JSONObject comment = BeanManager.getInstance().getReference(org.b3log.solo.repository.CommentRepository.class).get(commentId);
            if (comment != null) return comment.optString(org.b3log.solo.model.Comment.COMMENT_NAME);
        } catch (Exception ignored) { }
        List<MailBind> mailBindList = getUserMailContext();
        for (int i = (mailBindList.size() - 1); i >= 0; i--) {
            MailBind mailBind = mailBindList.get(i);
            if (mailBind.getCommentId().equals(commentId)) {
                return mailBind.getCommentUser();
            }
        }
        return "";
    }

    /**
     * 将 Mail Context 转换为可视列表
     *
     * @return
     */
    public static List<MailBind> getUserMailContext() {
        List<MailBind> mailBindList = new ArrayList<>();

        try {
            final BeanManager beanManager = BeanManager.getInstance();
            final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);
            String mailUserContext = "";
            try {
                mailUserContext = optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT).optString(Option.OPTION_VALUE);
            } catch (Exception e) {
            }
            String[] perUser = mailUserContext.split(";");
            for (int i = 0; i < perUser.length; i++) {
                try {
                    String[] perSet = perUser[i].split(":");
                    String commentId = perSet[0];
                    String commentUser = perSet[1];
                    String commentEmail = perSet[2];
                    MailBind mailBind = new MailBind();
                    mailBind.setCommentId(commentId);
                    mailBind.setCommentUser(commentUser);
                    mailBind.setCommentEmail(commentEmail);
                    mailBindList.add(mailBind);
                } catch (Exception e) {
                    // 分割出问题，一定要抓取然后继续
                    continue;
                }
            }
        } catch (Exception e) {
        }

        return mailBindList;
    }

    /**
     * 重载邮件设置至内存
     */
    public static void loadMailSettings() {
        configured = false;
        try {
            final BeanManager beanManager = BeanManager.getInstance();
            final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);

            try {
                optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT).optString(Option.OPTION_VALUE);
            } catch (RepositoryException | NullPointerException e) {
                final Transaction transaction = optionRepository.beginTransaction();
                JSONObject mailUserContextOpt = new JSONObject();
                mailUserContextOpt.put(Keys.OBJECT_ID, Option.ID_C_MAIL_USER_CONTEXT);
                mailUserContextOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
                mailUserContextOpt.put(Option.OPTION_VALUE, "");
                optionRepository.add(mailUserContextOpt);
                transaction.commit();
            }

            String mailBox = optionRepository.get(Option.ID_C_MAIL_BOX).optString(Option.OPTION_VALUE);
            String mailUsername = optionRepository.get(Option.ID_C_MAIL_USERNAME).optString(Option.OPTION_VALUE);
            String mailPassword = optionRepository.get(Option.ID_C_MAIL_PASSWORD).optString(Option.OPTION_VALUE);
            if (!mailBox.isEmpty()) {
                if (!mailUsername.isEmpty() && !mailPassword.isEmpty()) {
                    final java.util.Properties smtp = switch (mailBox) {
                        case "QQ" -> OhMyEmail.SMTP_QQ(false);
                        case "QQ_ENT" -> OhMyEmail.SMTP_ENT_QQ(false);
                        case "163" -> OhMyEmail.SMTP_163(false);
                        default -> null;
                    };
                    if (smtp != null) {
                        smtp.setProperty("mail.smtp.connectiontimeout", "10000");
                        smtp.setProperty("mail.smtp.timeout", "10000");
                        smtp.setProperty("mail.smtp.writetimeout", "10000");
                        OhMyEmail.config(smtp, mailUsername, mailPassword);
                        configured = true;
                    }
                    return;
                }
            }
        } catch (RepositoryException RE) {
            LOGGER.log(Level.WARN, "Cannot load Mailbox Settings, please check the configuration.");
        } catch (NullPointerException NPE) {
            LOGGER.log(Level.WARN, "Cannot load Mailbox Settings, please check.");
        }
    }

    /**
     * 获取可视化邮件服务器设定
     *
     * @return
     */
    public static String getMailSet() {
        try {
            final BeanManager beanManager = BeanManager.getInstance();
            final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);

            String mailBox = optionRepository.get(Option.ID_C_MAIL_BOX).optString(Option.OPTION_VALUE);
            return "[mailBox=" + mailBox + ", configured=" + configured + "]";
        } catch (RepositoryException RE) {
            LOGGER.log(Level.WARN, "Cannot load Mailbox Settings, please check.");

            return "";
        }
    }

    /**
     * 清空指定用户评论的邮件提醒服务列表
     */
    @RequestProcessing(value = "/prop/mail/clear", method = {HttpMethod.POST})
    public void clearCommentMailContext(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;

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
            for (final JSONObject comment : comments.getList(new org.b3log.latke.repository.Query().setPage(1, Integer.MAX_VALUE))) {
                if (!comment.optString("commentNotifyEmail").isBlank()) {
                    comment.put("commentNotifyEmail", "");
                    comments.update(comment.getString(Keys.OBJECT_ID), comment, "commentNotifyEmail");
                }
            }
            transaction.commit();
            context.renderJSON(new JSONObject().put("sc", true).put("msg", "已清除评论通知邮箱绑定。"));
        } catch (Exception failure) {
            if (transaction.isActive()) transaction.rollback();
            org.b3log.solo.util.AdminRequests.reject(context, 503, "清除失败，请重试。");
        }
    }
}
