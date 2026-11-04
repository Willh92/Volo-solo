/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.b3log.solo.web.WebResources;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads only the bundled, allowlisted Live2D models. No remote bootstrap is needed. */
public final class KanbanModels {
    private static final java.util.regex.Pattern MODEL_NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-]+");
    private static final java.util.Set<String> RESOURCE_KEYS =
            java.util.Set.of("model", "textures", "physics", "pose", "file", "sound");
    private final Path root;
    private volatile Path realRoot;

    public KanbanModels(final WebResources resources) {
        root = resources.resolve("/plugins/kanbanniang/assets");
    }

    private Path realRoot() throws IOException {
        Path cached = realRoot;
        if (cached == null) {
            cached = root.toRealPath();
            realRoot = cached;
        }
        return cached;
    }

    public List<String> names() throws IOException {
        final JSONArray manifest = new JSONArray(read("model-list.json"));
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < manifest.length(); i++) {
            final String name = manifest.getString(i);
            if (!MODEL_NAME_PATTERN.matcher(name).matches()) throw new IOException("Invalid model name");
            if (!names.contains(name)) names.add(name);
        }
        if (names.isEmpty()) throw new IOException("No bundled models");
        return List.copyOf(names);
    }

    public JSONObject select(final String selected) throws IOException {
        final List<String> names = names();
        final String name = selected != null && names.contains(selected) ? selected : names.get(ThreadLocalRandom.current().nextInt(names.size()));
        final String directory = "model/" + name + "/";
        final JSONObject model = new JSONObject(read(directory + "index.json"));
        JSONArray textures = model.optJSONArray("textures");
        if (textures == null || textures.isEmpty()) {
            final JSONArray variants = new JSONArray(read(directory + "textures.json"));
            if (variants.isEmpty()) throw new IOException("Model has no textures");
            final Object variant = variants.get(ThreadLocalRandom.current().nextInt(variants.length()));
            textures = variant instanceof JSONArray ? (JSONArray) variant : new JSONArray().put(variant);
            model.put("textures", textures);
        }
        validate(model, "");
        model.put("name", name);
        return model;
    }

    private void validate(final Object value, final String key) throws IOException {
        if (value instanceof JSONObject object) {
            for (String child : object.keySet()) validate(object.get(child), child);
        } else if (value instanceof JSONArray array) {
            for (Object child : array) validate(child, key);
        } else if (RESOURCE_KEYS.contains(key)) {
            if (!(value instanceof String resource) || resource.isBlank()) throw new IOException("Invalid model resource");
            resolveResource(resource);
        }
    }

    private String read(final String name) throws IOException {
        return Files.readString(resolveResource(name), StandardCharsets.UTF_8);
    }

    private Path resolveResource(final String name) throws IOException {
        if (name.startsWith("/") || name.contains(":") || name.contains("\\")
                || name.contains("?") || name.contains("#") || name.contains("%")
                || List.of(name.split("/")).contains("..")) throw new IOException("Non-local model resource");
        final Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(realRoot())) throw new IOException("Missing bundled model resource");
        return file;
    }
}
