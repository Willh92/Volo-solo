/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import org.b3log.solo.bolo.tool.MediaFileUtil;
import org.b3log.solo.util.KanbanModels;
import org.b3log.solo.util.S3CompatibleUploader;
import org.b3log.solo.util.Uploads;
import org.b3log.solo.web.WebResources;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class S3AndMediaValidationTest {

    @Test
    public void sigV4DeterministicSigningAndSsrfEndpointGuards() throws Exception {
        // Known AWS Signature Version 4 GET reference vector (AWS S3 SigV4 documentation example)
        final URI vectorUri = URI.create("https://examplebucket.s3.amazonaws.com/test.txt");
        final S3CompatibleUploader.SignedRequest signed = S3CompatibleUploader.signRequest(
                "GET",
                vectorUri,
                "us-east-1",
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                S3CompatibleUploader.EMPTY_PAYLOAD_SHA256,
                "20130524T000000Z"
        );
        Assert.assertTrue(signed.canonicalRequest().contains("host:examplebucket.s3.amazonaws.com\n"));
        Assert.assertTrue(signed.authorization().startsWith(
                "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature="));
        // Exact AWS S3 SigV4 GET vector signature when Range header is omitted:
        final S3CompatibleUploader.SignedRequest signedWithRange = S3CompatibleUploader.signRequest(
                "GET",
                vectorUri,
                "us-east-1",
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                S3CompatibleUploader.EMPTY_PAYLOAD_SHA256,
                "20130524T000000Z",
                java.util.Map.of("Range", "bytes=0-9")
        );
        Assert.assertTrue(signedWithRange.authorization().endsWith(
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"),
                "AWS S3 official SigV4 GET test vector must match byte-for-byte");

        // Config tuple parser & SSRF guards
        final S3CompatibleUploader.S3Config cfg = S3CompatibleUploader.parseTuChuangConfig(
                "s3<<>>https://s3.example.com<<>>AK123<<>>SK456<<>>media-bucket<<>>auto<<>>https://img.example.com<<>>blog<<>>true");
        Assert.assertEquals(cfg.bucket(), "media-bucket");
        Assert.assertEquals(cfg.region(), "auto");
        Assert.assertEquals(cfg.subDir(), "blog");
        Assert.assertTrue(cfg.pathStyle());

        Assert.assertFalse(S3CompatibleUploader.isAllowedS3EndpointAddress(InetAddress.getByName("127.0.0.1")));
        Assert.assertFalse(S3CompatibleUploader.isAllowedS3EndpointAddress(InetAddress.getByName("::1")));
        Assert.assertFalse(S3CompatibleUploader.isAllowedS3EndpointAddress(InetAddress.getByName("169.254.169.254")));
        Assert.assertFalse(S3CompatibleUploader.isAllowedS3EndpointAddress(InetAddress.getByName("100.100.100.200")));
        Assert.assertFalse(S3CompatibleUploader.isAllowedS3EndpointAddress(InetAddress.getByName("0.0.0.0")));
        // Self-hosted LAN MinIO is allowed while loopback/metadata are blocked
        Assert.assertTrue(S3CompatibleUploader.isAllowedS3EndpointAddress(InetAddress.getByName("192.168.1.50")));
    }

    @Test
    public void pureJavaWebpAndAvifValidationEnforcesStructureAndPixelBounds() throws Exception {
        final Path tempDir = Files.createTempDirectory("volo-media-val-");
        try {
            // 1. Valid VP8X WebP (640x480 -> width-1 = 639, height-1 = 479)
            final Path validWebp = tempDir.resolve("sample.webp");
            Files.write(validWebp, buildVp8xWebpBytes(640, 480));
            Uploads.validateImage(validWebp.toFile());

            // 2. Decompression bomb VP8X WebP (8000x6000 = 48,000,000 > 40,000,000)
            final Path bombWebp = tempDir.resolve("bomb.webp");
            Files.write(bombWebp, buildVp8xWebpBytes(8000, 6000));
            Assert.expectThrows(IOException.class, () -> Uploads.validateImage(bombWebp.toFile()));

            // 3. Valid ISOBMFF AVIF (1920x1080)
            final Path validAvif = tempDir.resolve("photo.avif");
            Files.write(validAvif, buildAvifBytes(1920, 1080));
            Uploads.validateImage(validAvif.toFile());

            // 4. Decompression bomb AVIF (9000x5000 = 45,000,000 > 40,000,000)
            final Path bombAvif = tempDir.resolve("bomb.avif");
            Files.write(bombAvif, buildAvifBytes(9000, 5000));
            Assert.expectThrows(IOException.class, () -> Uploads.validateImage(bombAvif.toFile()));

            // 5. Corrupt / polyglot HTML masquerading as .webp or .avif
            final Path fakeImage = tempDir.resolve("evil.webp");
            Files.writeString(fakeImage, "<script>alert(1)</script>", StandardCharsets.UTF_8);
            Assert.expectThrows(IOException.class, () -> Uploads.validateImage(fakeImage.toFile()));

            // 6. MediaFileUtil type recognition for WebP and AVIF
            final MediaFileUtil.MediaFileType webpType = MediaFileUtil.getFileType(validWebp.toString());
            Assert.assertNotNull(webpType);
            Assert.assertTrue(MediaFileUtil.isImageFileType(webpType.fileType));
            Assert.assertEquals(webpType.mimeType, "image/webp");

            final MediaFileUtil.MediaFileType avifType = MediaFileUtil.getFileType(validAvif.toString());
            Assert.assertNotNull(avifType);
            Assert.assertTrue(MediaFileUtil.isImageFileType(avifType.fileType));
            Assert.assertEquals(avifType.mimeType, "image/avif");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void kanbanModelsGracefullySkipsExcludedSlimDistributionPacks() throws Exception {
        final Path root = Files.createTempDirectory("volo-slim-kanban-");
        try {
            final Path assets = root.resolve("plugins/kanbanniang/assets");
            Files.createDirectories(assets.resolve("model/ShizukuTalk/shizuku-48"));
            // Manifest lists both an installed model and an excluded slim-edition pack (Potion-Maker/Pio)
            Files.writeString(assets.resolve("model-list.json"),
                    new JSONArray().put("Potion-Maker/Pio").put("ShizukuTalk/shizuku-48").toString(),
                    StandardCharsets.UTF_8);
            Files.writeString(assets.resolve("model/ShizukuTalk/shizuku-48/model.moc"), "moc", StandardCharsets.UTF_8);
            Files.writeString(assets.resolve("model/ShizukuTalk/shizuku-48/tex.png"), "png", StandardCharsets.UTF_8);
            Files.writeString(assets.resolve("model/ShizukuTalk/shizuku-48/index.json"),
                    new JSONObject()
                            .put("model", "model/ShizukuTalk/shizuku-48/model.moc")
                            .put("textures", new JSONArray().put("model/ShizukuTalk/shizuku-48/tex.png"))
                            .toString(),
                    StandardCharsets.UTF_8);

            final KanbanModels models = new KanbanModels(new WebResources(root));
            Assert.assertEquals(models.names(), java.util.List.of("ShizukuTalk/shizuku-48"));
            final JSONObject fallback = models.select("Potion-Maker/Pio");
            Assert.assertEquals(fallback.getString("name"), "ShizukuTalk/shizuku-48");
        } finally {
            deleteRecursively(root);
        }
    }

    private static byte[] buildVp8xWebpBytes(final int width, final int height) {
        final ByteBuffer buf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(22); // file size - 8
        buf.put("WEBP".getBytes(StandardCharsets.US_ASCII));
        buf.put("VP8X".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(10); // VP8X payload size
        buf.putInt(0);  // flags + reserved
        final int wMinus1 = width - 1;
        final int hMinus1 = height - 1;
        buf.put((byte) (wMinus1 & 0xFF));
        buf.put((byte) ((wMinus1 >>> 8) & 0xFF));
        buf.put((byte) ((wMinus1 >>> 16) & 0xFF));
        buf.put((byte) (hMinus1 & 0xFF));
        buf.put((byte) ((hMinus1 >>> 8) & 0xFF));
        buf.put((byte) ((hMinus1 >>> 16) & 0xFF));
        return buf.array();
    }

    private static byte[] buildAvifBytes(final int width, final int height) throws IOException {
        // ftyp box (16 bytes): [size=16]['ftyp']['avif'][minor=0]
        final ByteBuffer ftyp = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        ftyp.putInt(16);
        ftyp.put("ftyp".getBytes(StandardCharsets.US_ASCII));
        ftyp.put("avif".getBytes(StandardCharsets.US_ASCII));
        ftyp.putInt(0);

        // ispe FullBox (20 bytes): [size=20]['ispe'][version/flags=0][width][height]
        final ByteBuffer ispe = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        ispe.putInt(20);
        ispe.put("ispe".getBytes(StandardCharsets.US_ASCII));
        ispe.putInt(0);
        ispe.putInt(width);
        ispe.putInt(height);

        final byte[] ipco = box("ipco", ispe.array());
        final byte[] iprp = box("iprp", ipco);
        // meta is a FullBox: 4-byte version/flags header before child boxes
        final ByteArrayOutputStream metaPayload = new ByteArrayOutputStream();
        metaPayload.write(new byte[]{0, 0, 0, 0});
        metaPayload.write(iprp);
        final byte[] meta = box("meta", metaPayload.toByteArray());

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ftyp.array());
        out.write(meta);
        return out.toByteArray();
    }

    private static byte[] box(final String fourCc, final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(8 + payload.length);
        buf.put(fourCc.getBytes(StandardCharsets.US_ASCII));
        buf.put(payload);
        return buf.array();
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (final Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
