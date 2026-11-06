/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free, non-sleeping monotonic millisecond ID generator.
 *
 * <p>Replaces Latke's {@code Ids.genTimeMillisId()} (which synchronizes globally and sleeps {@code 50ms} per ID)
 * with an {@link AtomicLong} CAS loop that guarantees strictly increasing numeric string IDs without blocking
 * threads or holding JDBC transactions open.</p>
 */
public final class MonotonicIds {

    private static final AtomicLong LAST_MILLIS = new AtomicLong(System.currentTimeMillis());

    private MonotonicIds() {
    }

    /**
     * Generates a unique, strictly increasing millisecond-based identifier string without sleeping.
     *
     * @return monotonic numeric string ID
     */
    public static String nextMillisId() {
        return Long.toString(LAST_MILLIS.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1L)));
    }
}
