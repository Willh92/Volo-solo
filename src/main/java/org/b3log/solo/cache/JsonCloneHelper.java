/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.cache;

import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;
import java.util.List;

/**
 * Allocation-conscious deep-clone and character-weight helper for in-memory JSON caches.
 *
 * @since 3.0.0
 */
public final class JsonCloneHelper {

    private JsonCloneHelper() {
    }

    /**
     * Deep-clones a {@link JSONObject}, isolating nested objects, arrays, and {@link Date} instances.
     *
     * @param object source object (nullable)
     * @return isolated deep copy, or {@code null} when {@code object} is {@code null}
     */
    public static JSONObject deepCloneObject(final JSONObject object) {
        return org.json.JsonCloneHelper.cloneObject(object);
    }

    /**
     * Deep-clones a {@link JSONArray}.
     *
     * @param array source array (nullable)
     * @return isolated deep copy, or {@code null} when {@code array} is {@code null}
     */
    public static JSONArray deepCloneArray(final JSONArray array) {
        return org.json.JsonCloneHelper.cloneArray(array);
    }

    /**
     * Estimates UTF-16 character footprint of a cached {@link JSONObject} without temporary allocations.
     *
     * @param object JSON object
     * @return non-negative estimated character count
     */
    public static int estimateChars(final JSONObject object) {
        if (null == object) {
            return 0;
        }
        long chars = 32L;
        for (final String key : object.keySet()) {
            if (null != key) {
                chars += key.length() + 12L;
            }
            chars += estimateValueChars(object.opt(key));
            if (chars >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) chars;
    }

    /**
     * Estimates UTF-16 character footprint of a list of {@link JSONObject}s.
     *
     * @param list list of JSON objects
     * @return non-negative estimated character count
     */
    public static int estimateListChars(final List<JSONObject> list) {
        if (null == list || list.isEmpty()) {
            return 0;
        }
        long chars = 24L;
        for (final JSONObject item : list) {
            chars += estimateChars(item) + 8L;
            if (chars >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) chars;
    }

    private static long estimateValueChars(final Object value) {
        if (null == value || JSONObject.NULL.equals(value)) {
            return 4L;
        }
        if (value instanceof CharSequence seq) {
            return seq.length() + 12L;
        }
        if (value instanceof JSONObject nestedObj) {
            return estimateChars(nestedObj);
        }
        if (value instanceof JSONArray nestedArr) {
            long chars = 16L;
            final int len = nestedArr.length();
            for (int i = 0; i < len; i++) {
                chars += estimateValueChars(nestedArr.opt(i));
                if (chars >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
            return chars;
        }
        return 8L;
    }
}
