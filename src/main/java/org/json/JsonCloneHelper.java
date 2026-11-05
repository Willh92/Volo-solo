/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.json;

import java.util.Date;
import java.util.Map;

/**
 * Zero-resize, zero-serialization deep-clone helper for {@link JSONObject} and {@link JSONArray}.
 * Located in {@code org.json} to access {@link JSONObject#JSONObject(int)} and {@link JSONObject#entrySet()}.
 */
public final class JsonCloneHelper {

    private JsonCloneHelper() {
    }

    /**
     * Deep-clones a {@link JSONObject} with pre-sized backing capacity so {@code HashMap} never resizes.
     *
     * @param src source object, may be {@code null}
     * @return deep-cloned {@link JSONObject}, or {@code null} if {@code src} is {@code null}
     */
    public static JSONObject cloneObject(final JSONObject src) {
        if (null == src) {
            return null;
        }
        final int len = src.length();
        if (len == 0) {
            return new JSONObject();
        }
        final JSONObject copy = new JSONObject((int) (len / 0.75f) + 2);
        for (final Map.Entry<String, Object> entry : src.entrySet()) {
            copy.put(entry.getKey(), cloneValue(entry.getValue()));
        }
        return copy;
    }

    /**
     * Deep-clones a {@link JSONArray} with pre-sized backing capacity.
     *
     * @param src source array, may be {@code null}
     * @return deep-cloned {@link JSONArray}, or {@code null} if {@code src} is {@code null}
     */
    public static JSONArray cloneArray(final JSONArray src) {
        if (null == src) {
            return null;
        }
        final int len = src.length();
        if (len == 0) {
            return new JSONArray();
        }
        final JSONArray copy = new JSONArray();
        for (int i = 0; i < len; i++) {
            copy.put(cloneValue(src.opt(i)));
        }
        return copy;
    }

    private static Object cloneValue(final Object val) {
        if (val == null || val == JSONObject.NULL) {
            return val;
        }
        if (val instanceof JSONObject obj) {
            return cloneObject(obj);
        }
        if (val instanceof JSONArray arr) {
            return cloneArray(arr);
        }
        if (val instanceof Date date) {
            return new Date(date.getTime());
        }
        return val;
    }
}
