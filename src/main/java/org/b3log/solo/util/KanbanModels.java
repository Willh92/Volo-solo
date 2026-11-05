/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.b3log.latke.Latkes;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.web.WebResources;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads only the bundled, allowlisted Live2D models. No remote bootstrap is needed. */
public final class KanbanModels {
    private static final Pattern MODEL_NAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-]+");
    private static final Set<String> RESOURCE_KEYS =
            Set.of("model", "textures", "physics", "pose", "file", "sound");
    private static final int MAX_DESCRIPTOR_CACHE_ENTRIES = 64;
    private static final long PROD_VERIFY_MS = 5_000L;

    private final WebResources resources;
    private final boolean defaultAppResources;
    private final Path root;
    private volatile Path realRoot;

    private volatile String cachedManifestJson;
    private volatile List<String> cachedNames;
    private volatile List<Path> cachedNameIndexFiles;
    private volatile long manifestVerifiedAtMillis;

    private final Map<String, CachedModelDescriptor> descriptorCache =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, CachedModelDescriptor> eldest) {
                    return size() > MAX_DESCRIPTOR_CACHE_ENTRIES;
                }
            };

    private static final class CachedModelDescriptor {
        final String rawIndexJson;
        final String rawTexturesJson;
        final JSONObject baseModel;
        final List<Path> baseValidatedFiles;
        final List<JSONArray> textureVariants;
        final List<Path>[] variantFiles;
        volatile long verifiedAtMillis;

        @SuppressWarnings("unchecked")
        CachedModelDescriptor(final String rawIndexJson,
                              final String rawTexturesJson,
                              final JSONObject baseModel,
                              final List<Path> baseValidatedFiles,
                              final List<JSONArray> textureVariants,
                              final long verifiedAtMillis) {
            this.rawIndexJson = rawIndexJson;
            this.rawTexturesJson = rawTexturesJson;
            this.baseModel = baseModel;
            this.baseValidatedFiles = baseValidatedFiles;
            this.textureVariants = textureVariants;
            this.variantFiles = textureVariants != null ? new List[textureVariants.size()] : null;
            this.verifiedAtMillis = verifiedAtMillis;
        }
    }

    public KanbanModels(final WebResources resources) {
        this.resources = resources;
        this.defaultAppResources = isDefaultWebResources(resources);
        this.root = resources.resolve("/plugins/kanbanniang/assets");
    }

    public WebResources resources() {
        return resources;
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
        final long now = System.currentTimeMillis();
        final List<String> fastNames = cachedNames;
        if (fastNames != null && defaultAppResources && isProductionRuntime()
                && now - manifestVerifiedAtMillis < PROD_VERIFY_MS) {
            return fastNames;
        }

        final String rawManifest = read("model-list.json");
        final List<String> prevNames = cachedNames;
        final List<Path> prevFiles = cachedNameIndexFiles;
        if (prevNames != null && prevFiles != null
                && rawManifest.equals(cachedManifestJson)
                && allRegularFiles(prevFiles)) {
            manifestVerifiedAtMillis = now;
            return prevNames;
        }

        final JSONArray manifest = new JSONArray(rawManifest);
        final List<String> names = new ArrayList<>(manifest.length());
        final List<Path> indexFiles = new ArrayList<>(manifest.length());
        final Path canonicalRoot = realRoot();
        for (int i = 0; i < manifest.length(); i++) {
            final String name = manifest.getString(i);
            if (!MODEL_NAME_PATTERN.matcher(name).matches()) {
                throw new IOException("Invalid model name");
            }
            final Path modelIndex = root.resolve("model/" + name + "/index.json").normalize();
            if (!modelIndex.startsWith(root) || !Files.isRegularFile(modelIndex)
                    || !modelIndex.toRealPath().startsWith(canonicalRoot)) {
                continue;
            }
            if (!names.contains(name)) {
                names.add(name);
                indexFiles.add(modelIndex);
            }
        }
        if (names.isEmpty()) {
            throw new IOException("No bundled models");
        }
        final List<String> immutableNames = List.copyOf(names);
        final List<Path> immutableFiles = List.copyOf(indexFiles);
        cachedNameIndexFiles = immutableFiles;
        cachedNames = immutableNames;
        cachedManifestJson = rawManifest;
        manifestVerifiedAtMillis = now;
        return immutableNames;
    }

    public JSONObject select(final String selected) throws IOException {
        final List<String> names = names();
        final String name = selected != null && names.contains(selected)
                ? selected
                : names.get(ThreadLocalRandom.current().nextInt(names.size()));

        final long now = System.currentTimeMillis();
        final CachedModelDescriptor existing;
        synchronized (descriptorCache) {
            existing = descriptorCache.get(name);
        }

        if (existing != null && defaultAppResources && isProductionRuntime()
                && now - existing.verifiedAtMillis < PROD_VERIFY_MS) {
            return materializeModel(name, existing, true);
        }

        final String directory = "model/" + name + "/";
        final String rawIndexJson = read(directory + "index.json");

        if (existing != null && rawIndexJson.equals(existing.rawIndexJson)) {
            if (existing.textureVariants == null) {
                if (allRegularFiles(existing.baseValidatedFiles)) {
                    existing.verifiedAtMillis = now;
                    return materializeModel(name, existing, false);
                }
            } else {
                final String rawTexturesJson = read(directory + "textures.json");
                if (rawTexturesJson.equals(existing.rawTexturesJson)
                        && allRegularFiles(existing.baseValidatedFiles)) {
                    existing.verifiedAtMillis = now;
                    return materializeModel(name, existing, false);
                }
            }
        }

        final JSONObject baseModel = new JSONObject(rawIndexJson);
        final JSONArray inlineTextures = baseModel.optJSONArray("textures");
        final List<Path> baseFiles = new ArrayList<>();

        if (inlineTextures != null && !inlineTextures.isEmpty()) {
            validate(baseModel, "", baseFiles);
            final CachedModelDescriptor fresh = new CachedModelDescriptor(
                    rawIndexJson, null, baseModel, List.copyOf(baseFiles), null, now);
            synchronized (descriptorCache) {
                descriptorCache.put(name, fresh);
            }
            return materializeModel(name, fresh, true);
        }

        final String rawTexturesJson = read(directory + "textures.json");
        final JSONArray variantsJson = new JSONArray(rawTexturesJson);
        if (variantsJson.isEmpty()) {
            throw new IOException("Model has no textures");
        }
        final List<JSONArray> normalizedVariants = new ArrayList<>(variantsJson.length());
        for (int i = 0; i < variantsJson.length(); i++) {
            final Object variant = variantsJson.get(i);
            final JSONArray arr = variant instanceof JSONArray ja
                    ? deepCloneArray(ja)
                    : new JSONArray().put(deepCloneValue(variant));
            normalizedVariants.add(arr);
        }

        baseModel.put("textures", new JSONArray());
        validate(baseModel, "", baseFiles);

        final CachedModelDescriptor fresh = new CachedModelDescriptor(
                rawIndexJson, rawTexturesJson, baseModel,
                List.copyOf(baseFiles), List.copyOf(normalizedVariants), now);
        final JSONObject rendered = materializeModel(name, fresh, true);
        synchronized (descriptorCache) {
            descriptorCache.put(name, fresh);
        }
        return rendered;
    }

    private JSONObject materializeModel(final String name,
                                        final CachedModelDescriptor descriptor,
                                        final boolean skipStatIfValidated) throws IOException {
        final JSONObject copy = deepCloneObject(descriptor.baseModel);
        if (descriptor.textureVariants != null) {
            final int idx = ThreadLocalRandom.current().nextInt(descriptor.textureVariants.size());
            ensureVariantValidated(descriptor, idx, skipStatIfValidated);
            copy.put("textures", deepCloneArray(descriptor.textureVariants.get(idx)));
        }
        copy.put("name", name);
        return copy;
    }

    private void ensureVariantValidated(final CachedModelDescriptor descriptor,
                                        final int variantIndex,
                                        final boolean skipStatIfValidated) throws IOException {
        List<Path> files;
        synchronized (descriptor) {
            files = descriptor.variantFiles[variantIndex];
        }
        if (files != null) {
            if (skipStatIfValidated || allRegularFiles(files)) {
                return;
            }
        }
        final List<Path> validated = new ArrayList<>(2);
        validate(descriptor.textureVariants.get(variantIndex), "textures", validated);
        final List<Path> immutable = List.copyOf(validated);
        synchronized (descriptor) {
            descriptor.variantFiles[variantIndex] = immutable;
        }
    }

    private void validate(final Object value, final String key, final List<Path> collector) throws IOException {
        if (value instanceof JSONObject object) {
            for (final String child : object.keySet()) {
                validate(object.get(child), child, collector);
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                validate(array.get(i), key, collector);
            }
        } else if (RESOURCE_KEYS.contains(key)) {
            if (!(value instanceof String resource) || resource.isBlank()) {
                throw new IOException("Invalid model resource");
            }
            collector.add(resolveResource(resource));
        }
    }

    private String read(final String name) throws IOException {
        return Files.readString(resolveResource(name), StandardCharsets.UTF_8);
    }

    private Path resolveResource(final String name) throws IOException {
        if (name.startsWith("/") || name.indexOf(':') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('?') >= 0 || name.indexOf('#') >= 0 || name.indexOf('%') >= 0
                || containsDotDotSegment(name)) {
            throw new IOException("Non-local model resource");
        }
        final Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(realRoot())) {
            throw new IOException("Missing bundled model resource");
        }
        return file;
    }

    private static boolean containsDotDotSegment(final String path) {
        final int len = path.length();
        int segStart = 0;
        for (int i = 0; i <= len; i++) {
            if (i == len || path.charAt(i) == '/') {
                if (i - segStart == 2
                        && path.charAt(segStart) == '.'
                        && path.charAt(segStart + 1) == '.') {
                    return true;
                }
                segStart = i + 1;
            }
        }
        return false;
    }

    private static boolean allRegularFiles(final List<Path> files) {
        for (int i = 0; i < files.size(); i++) {
            if (!Files.isRegularFile(files.get(i), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }

    private static JSONObject deepCloneObject(final JSONObject source) {
        final JSONObject copy = new JSONObject();
        for (final String key : source.keySet()) {
            copy.put(key, deepCloneValue(source.get(key)));
        }
        return copy;
    }

    private static JSONArray deepCloneArray(final JSONArray source) {
        final JSONArray copy = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            copy.put(deepCloneValue(source.get(i)));
        }
        return copy;
    }

    private static Object deepCloneValue(final Object value) {
        if (value instanceof JSONObject obj) {
            return deepCloneObject(obj);
        }
        if (value instanceof JSONArray arr) {
            return deepCloneArray(arr);
        }
        return value;
    }

    private static boolean isDefaultWebResources(final WebResources candidate) {
        try {
            return candidate != null && candidate == SoloApplication.getWebResources();
        } catch (final Throwable ignored) {
            return false;
        }
    }

    private static boolean isProductionRuntime() {
        try {
            return Latkes.RuntimeMode.PRODUCTION == Latkes.getRuntimeMode();
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
