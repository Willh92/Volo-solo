package org.b3log.solo.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.b3log.solo.util.CommentThrottle;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CommentThrottleTest {
    private static final CommentThrottle.Limits DEFAULTS = new CommentThrottle.Limits(15, 600, 5, 30, 100);
    private static void advance(final AtomicLong clock, final int seconds) { clock.addAndGet(Duration.ofSeconds(seconds).toNanos()); }

    @Test public void concurrentDuplicatesAdmitExactlyOneRequest() throws Exception {
        final CommentThrottle limiter = new CommentThrottle(new AtomicLong(1)::get, DEFAULTS);
        final var pool = Executors.newFixedThreadPool(8);
        try {
            final var requests = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 64; i++) requests.add(pool.submit(() -> limiter.acquire("192.0.2.1", "post", "同一条留言").allowed()));
            int admitted = 0;
            for (var request : requests) if (request.get()) admitted++;
            Assert.assertEquals(admitted, 1);
        } finally { pool.shutdownNow(); }
    }

    @Test public void sourceIntervalAndWindowApplyAcrossArticles() {
        final AtomicLong clock = new AtomicLong(1);
        final CommentThrottle limiter = new CommentThrottle(clock::get, DEFAULTS);
        Assert.assertTrue(limiter.acquire("192.0.2.2", "post1", "first").allowed());
        Assert.assertEquals(limiter.acquire("192.0.2.2", "post2", "second").retryAfter(), 15);
        for (int i = 1; i < 5; i++) { advance(clock, 15); Assert.assertTrue(limiter.acquire("192.0.2.2", "post" + i, "message" + i).allowed()); }
        advance(clock, 15);
        Assert.assertEquals(limiter.acquire("192.0.2.2", "post6", "message6").retryAfter(), 525);
        advance(clock, 525);
        Assert.assertTrue(limiter.acquire("192.0.2.2", "post6", "message6").allowed());
    }

    @Test public void whitespaceUnicodeAndArticleChangesDoNotBypassDuplicateChecks() {
        final AtomicLong clock = new AtomicLong(1);
        final CommentThrottle limiter = new CommentThrottle(clock::get, DEFAULTS);
        Assert.assertTrue(limiter.acquire("192.0.2.3", "post1", "hello world").allowed());
        advance(clock, 15);
        Assert.assertFalse(limiter.acquire("192.0.2.3", "post2", "ｈｅｌｌｏ\u200bworld").allowed());
        Assert.assertTrue(limiter.acquire("192.0.2.4", "post2", "hello world").allowed(), "Unrelated readers may write the same short reply");
    }

    @Test public void ipv6PrivacyAddressesShareOneBudget() {
        final CommentThrottle limiter = new CommentThrottle(new AtomicLong(1)::get, DEFAULTS);
        Assert.assertTrue(limiter.acquire("2001:db8:1234:1::1", "post", "hello").allowed());
        Assert.assertFalse(limiter.acquire("2001:db8:1234:1::2", "post", "different").allowed());
        Assert.assertTrue(limiter.acquire("2001:db8:1234:2::1", "post", "different").allowed());
    }

    @Test public void rotatingSourcesCannotBypassArticleAndSiteBudgets() {
        final AtomicLong clock = new AtomicLong(1);
        final CommentThrottle limiter = new CommentThrottle(clock::get, new CommentThrottle.Limits(1, 60, 100, 2, 3));
        Assert.assertTrue(limiter.acquire("192.0.2.1", "post", "one").allowed());
        Assert.assertTrue(limiter.acquire("192.0.2.2", "post", "two").allowed());
        Assert.assertFalse(limiter.acquire("192.0.2.3", "post", "three").allowed());
        Assert.assertTrue(limiter.acquire("192.0.2.3", "other", "three").allowed());
        Assert.assertFalse(limiter.acquire("192.0.2.4", "another", "four").allowed());
        advance(clock, 60);
        Assert.assertTrue(limiter.acquire("192.0.2.4", "another", "four").allowed());
    }

    @Test public void failedPersistenceReleasesOnlyTheDuplicateReservation() {
        final AtomicLong clock = new AtomicLong(1);
        final CommentThrottle limiter = new CommentThrottle(clock::get, DEFAULTS);
        final var attempt = limiter.acquire("192.0.2.1", "post", "retry me");
        limiter.failed(attempt);
        Assert.assertFalse(limiter.acquire("192.0.2.1", "post", "retry me").allowed(), "Failed writes still consume the short admission budget");
        advance(clock, 15);
        Assert.assertTrue(limiter.acquire("192.0.2.1", "post", "retry me").allowed());
        limiter.failed(attempt);
        advance(clock, 15);
        Assert.assertFalse(limiter.acquire("192.0.2.1", "post", "retry me").allowed(), "An old failure cannot remove a newer reservation");
    }
}
