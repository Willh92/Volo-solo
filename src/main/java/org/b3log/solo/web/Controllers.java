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
package org.b3log.solo.web;

final class Controllers {
    static final Class<?>[] TYPES = {
        org.b3log.solo.bolo.pic.PicUploadProcessor.class,
        org.b3log.solo.bolo.prop.BackupService.class,
        org.b3log.solo.bolo.prop.CommentMailService.class,
        org.b3log.solo.bolo.prop.MailProcessor.class,
        org.b3log.solo.bolo.prop.MailService.class,
        org.b3log.solo.bolo.tool.PBConvert.class,
        org.b3log.solo.bolo.waf.WAFprocessor.class,
        org.b3log.solo.processor.ArticleProcessor.class,
        org.b3log.solo.processor.B3Receiver.class,
        org.b3log.solo.processor.BlogProcessor.class,
        org.b3log.solo.processor.CategoryProcessor.class,
        org.b3log.solo.processor.CommentProcessor.class,
        org.b3log.solo.processor.CommentEditProcessor.class,
        org.b3log.solo.processor.console.CategoryConsole.class,
        org.b3log.solo.processor.console.CommentConsole.class,
        org.b3log.solo.processor.console.FollowConsole.class,
        org.b3log.solo.processor.console.LinkConsole.class,
        org.b3log.solo.processor.console.IpBanConsole.class,
        org.b3log.solo.processor.console.OtherConsole.class,
        org.b3log.solo.processor.console.PageConsole.class,
        org.b3log.solo.processor.console.PluginConsole.class,
        org.b3log.solo.processor.console.PreferenceConsole.class,
        org.b3log.solo.processor.console.RepairConsole.class,
        org.b3log.solo.processor.console.SkinConsole.class,
        org.b3log.solo.processor.console.TagConsole.class,
        org.b3log.solo.processor.console.UserConsole.class,
        org.b3log.solo.processor.ErrorProcessor.class,
        org.b3log.solo.processor.FeedProcessor.class,
        org.b3log.solo.processor.IndexProcessor.class,
        org.b3log.solo.processor.KanBanNiangProcessor.class,
        org.b3log.solo.processor.OAuthProcessor.class,
        org.b3log.solo.processor.SearchProcessor.class,
        org.b3log.solo.processor.SitemapProcessor.class,
        org.b3log.solo.processor.TagProcessor.class,
        org.b3log.solo.processor.UserTemplateProcessor.class
    };
    private Controllers() {}
}
