/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.prop.CommentMailService;
import org.b3log.solo.bolo.prop.MailProcessor;
import org.b3log.solo.bolo.prop.MailService;
import org.testng.Assert;
import org.testng.annotations.Test;

public class VersionAndMailTest {
    @Test public void retiredTelemetryRemainsDisabledWithoutReadingLegacyOptIns() {
        Assert.assertEquals(org.b3log.solo.improve.ImproveOptions.doJoinHelpImprovePlan(), "false");
        new org.b3log.solo.improve.ImproveHelper(null).run();
        new org.b3log.solo.improve.LogHelper(null).run();
        org.b3log.solo.improve.ImproveHelperExecutor.submit(null);
        org.b3log.solo.improve.LogHelperExecutor.submit(null);
    }
    @Test public void productAndDatabaseVersionsAreIndependent() {
        Assert.assertEquals(SoloApplication.VERSION, "3.0.0");
        Assert.assertEquals(SoloApplication.BOLO_VERSION_SOURCE, SoloApplication.VERSION);
        Assert.assertEquals(SoloApplication.DATABASE_VERSION, "2026.11.01");
        Assert.assertNotEquals(SoloApplication.DATABASE_VERSION, SoloApplication.VERSION);
    }

    @Test public void disabledSmtpDoesNotQueueNetworkTasksOrReadNotificationBindings() throws Exception {
        final var enabled = MailService.class.getDeclaredField("configured");
        enabled.setAccessible(true);
        final boolean previous = enabled.getBoolean(null);
        final var queue = MailProcessor.class.getDeclaredField("MAILS");
        queue.setAccessible(true);
        final var executor = (java.util.concurrent.ThreadPoolExecutor) queue.get(null);
        final long tasks = executor.getTaskCount();
        try {
            enabled.setBoolean(null, false);
            // No repositories or SMTP server exist in this test. Unconfigured mail must be a no-op.
            CommentMailService.remindCommentedGuy("missing-comment", "https://example.test/", "visitor", "Blog");
            CommentMailService.remindAdmin("owner@example.test", "https://example.test/", "visitor", "Text", "Blog");
            MailProcessor.localSendMailMethod("Subject", "Blog", "owner@example.test", "Text");
            Assert.assertEquals(executor.getTaskCount(), tasks);
        } finally { enabled.setBoolean(null, previous); }
    }
}
