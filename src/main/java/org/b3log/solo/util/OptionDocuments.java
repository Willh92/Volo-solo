/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import org.b3log.solo.model.Option;
import org.b3log.solo.service.OptionQueryService;
import org.json.JSONObject;

/** Reads JSON documents stored within the existing 10240-character option column. */
public final class OptionDocuments {
    private OptionDocuments() { }
    public static String read(final OptionQueryService service, final String id, final String fallback) {
        final JSONObject root = service.getOptionById(id);
        if (root == null) return fallback;
        final String value = root.optString(Option.OPTION_VALUE, fallback);
        if (!value.startsWith("{\"storage\":\"chunks\"")) return value;
        final JSONObject manifest = new JSONObject(value);
        final String generation = manifest.getString("generation");
        final int count = manifest.getInt("count");
        if (!generation.matches("[a-f0-9]{32}") || count < 1 || count > 128) throw new IllegalStateException("Invalid option document manifest");
        final StringBuilder data = new StringBuilder();
        for (int i = 0; i < count; i++) {
            final JSONObject chunk = service.getOptionById(id + "." + generation + "." + i);
            if (chunk == null) throw new IllegalStateException("Missing option document chunk");
            data.append(chunk.getString(Option.OPTION_VALUE));
        }
        return data.toString();
    }
}
