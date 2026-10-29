/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.b3log.solo.util.KanbanModels;
import org.b3log.solo.web.WebResources;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class KanbanModelsTest {
    @Test public void everyBundledModelHasLocalResourcesAndTextures() throws Exception {
        final KanbanModels models = new KanbanModels(new WebResources(Path.of("src/main/webapp")));
        Assert.assertEquals(models.names().size(), 26);
        for (String name : models.names()) {
            final JSONObject descriptor = models.select(name);
            Assert.assertEquals(descriptor.getString("name"), name);
            Assert.assertFalse(descriptor.getJSONArray("textures").isEmpty(), name);
            Assert.assertFalse(descriptor.toString().contains("https://"), name);
            Assert.assertFalse(descriptor.toString().contains("http://"), name);
        }
    }

    @Test public void missingAndRetiredSelectionsFallBackToABundledModel() throws Exception {
        final KanbanModels models = new KanbanModels(new WebResources(Path.of("src/main/webapp")));
        for (String selected : new String[]{"", "old/custom-model", "../../WEB-INF/classes", "https://untrusted.example/model"}) {
            Assert.assertTrue(models.names().contains(models.select(selected).getString("name")));
        }
        Assert.assertTrue(models.names().contains(models.select(null).getString("name")));
    }

    @Test public void invalidManifestNamesNeverBecomeFilesystemPaths() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String name : List.of("../secret", "/etc/passwd", "https://untrusted.example/a", "model\\secret")) {
                Files.writeString(fixture.assets.resolve("model-list.json"), new JSONArray().put(name).toString());
                Assert.expectThrows(IOException.class, fixture.models::names);
            }
        }
    }

    @Test public void descriptorsRejectRemoteTraversalAndMissingResourcePaths() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String resource : List.of("https://untrusted.example/x.png", "//untrusted.example/x.png", "../secret",
                    "model\\unsafe.png", "model/%2e%2e/x.png", "model/x.png?target=secret", "model/missing.png")) {
                fixture.descriptor(resource);
                Assert.expectThrows(IOException.class, () -> fixture.models.select("Test/Model"));
            }
        }
    }

    @Test public void missingTextureVariantsFailWithoutReturningAnEmptyModel() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.descriptor("model/Test/Model/texture.png");
            final Path descriptor = fixture.assets.resolve("model/Test/Model/index.json");
            final JSONObject json = new JSONObject(Files.readString(descriptor));
            json.put("textures", new JSONArray());
            Files.writeString(descriptor, json.toString());
            Files.writeString(fixture.assets.resolve("model/Test/Model/textures.json"), "[]");
            Assert.expectThrows(IOException.class, () -> fixture.models.select("Test/Model"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory(Path.of("target"), "kanban-models-");
        final Path assets = root.resolve("plugins/kanbanniang/assets");
        final KanbanModels models = new KanbanModels(new WebResources(root));
        Fixture() throws IOException {
            Files.createDirectories(assets.resolve("model/Test/Model"));
            Files.writeString(assets.resolve("model-list.json"), "[\"Test/Model\"]");
            Files.writeString(assets.resolve("model/Test/Model/model.moc"), "fixture");
            Files.writeString(assets.resolve("model/Test/Model/texture.png"), "fixture");
        }
        void descriptor(String texture) throws IOException {
            Files.writeString(assets.resolve("model/Test/Model/index.json"), new JSONObject()
                    .put("model", "model/Test/Model/model.moc").put("textures", new JSONArray().put(texture)).toString());
        }
        @Override public void close() throws IOException {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
