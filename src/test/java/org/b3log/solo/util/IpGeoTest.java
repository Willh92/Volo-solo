/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.testng.Assert;
import org.testng.annotations.Test;

public class IpGeoTest {
    @Test public void chineseNamesTakePrecedenceAndEnglishFillsOnlyMissingFields() {
        final Map<String, Object> record = Map.of(
                "country", Map.of("names", Map.of("zh-CN", "中国", "en", "China")),
                "subdivisions", List.of(Map.of("names", Map.of("zh-CN", "广东省", "en", "Guangdong"))),
                "city", Map.of("names", Map.of("zh-CN", "深圳市", "en", "Shenzhen")));
        Assert.assertEquals(IpGeo.location(record), "中国 · 广东省 · 深圳市");
        Assert.assertEquals(IpGeo.location(Map.of("country", Map.of("names", Map.of("zh-CN", "英国")),
                "subdivisions", List.of(Map.of("names", Map.of("en", "England"))),
                "city", Map.of("names", Map.of("en", "London")))), "英国 · England · London");
        Assert.assertEquals(IpGeo.location(Map.of("country", Map.of("iso_code", "GB"))), "GB");
        Assert.assertEquals(IpGeo.location(Map.of("city", Map.of("names", Map.of("en", "上海")),
                "subdivisions", List.of(Map.of("names", Map.of("zh-CN", "上海"))))), "上海");
        Assert.assertEquals(IpGeo.location(Map.of("autonomous_system_number", 64500)), "");
        final var details = IpGeo.mmdbLocation(record);
        Assert.assertEquals(details.province(), "广东省"); Assert.assertEquals(details.city(), "深圳市");
        final var xdb = IpGeo.xdbLocation("中国|广东省|深圳市|电信|CN");
        Assert.assertEquals(xdb.countryCode(), "CN"); Assert.assertEquals(xdb.province(), "广东省"); Assert.assertEquals(xdb.city(), "深圳市");
        Assert.assertEquals(IpGeo.xdbLocation("美国|0|0|0|US").province(), "");
    }

    @Test public void localCityDatabaseSupportsBothAddressFamiliesAndSharedReaders() throws Exception {
        withDirectory(directory -> {
            Files.copy(Path.of("src/test/resources/mmdb/GeoIP2-City-Test.mmdb"), directory.resolve("GeoLite2-City.mmdb"));
            try (var lookup = new IpGeo.Databases(directory)) {
                final String city = lookup.region(IpAddresses.bytes("81.2.69.160"));
                Assert.assertTrue(city.contains("英国"), city);
                Assert.assertEquals(city, "英国 · 英格兰 · 伦敦");
                Assert.assertEquals(lookup.lookup(IpAddresses.bytes("81.2.69.160")).countryCode(), "GB");
                Assert.assertEquals(lookup.lookup(IpAddresses.bytes("81.2.69.160")).province(), "英格兰");
                Assert.assertEquals(lookup.lookup(IpAddresses.bytes("81.2.69.160")).city(), "伦敦");
                Assert.assertFalse(lookup.region(IpAddresses.bytes("2001:218::")).isEmpty());
                Assert.assertEquals(lookup.region(IpAddresses.bytes("127.0.0.1")), "");
                Assert.assertTrue(IntStream.range(0, 128).parallel()
                        .allMatch(index -> city.equals(lookup.region(IpAddresses.bytes("81.2.69.160")))));
            }
        });
    }

    @Test public void absentOrBrokenCityDatabaseFallsBackToCountry() throws Exception {
        withDirectory(directory -> {
            Files.copy(Path.of("src/test/resources/mmdb/GeoIP2-Country-Test.mmdb"), directory.resolve("GeoLite2-Country.mmdb"));
            Files.writeString(directory.resolve("GeoLite2-City.mmdb"), "invalid database");
            try (var lookup = new IpGeo.Databases(directory)) {
                Assert.assertEquals(lookup.region(IpAddresses.bytes("81.2.69.160")), "英国");
            }
        });
    }

    @Test public void anEmptyDirectoryDoesNotRequireOrContactAService() throws Exception {
        withDirectory(directory -> {
            try (var lookup = new IpGeo.Databases(directory)) {
                Assert.assertEquals(lookup.region(IpAddresses.bytes("81.2.69.160")), "");
            }
        });
        Assert.assertEquals(IpGeo.region("example.com"), "", "Only literal IP addresses are accepted");
    }

    @Test public void bundledIp2regionSuppliesCountryCodesForBothFamilies() throws Exception {
        final Path directory = Path.of("src/main/distribution/geoip");
        Assert.assertTrue(Files.isRegularFile(directory.resolve("ip2region_v4.xdb")), "Run node scripts/fetch-geoip.mjs before building");
        try (var lookup = new IpGeo.Databases(directory)) {
            Assert.assertEquals(lookup.lookup(IpAddresses.bytes("8.8.8.8")).countryCode(), "US");
            Assert.assertEquals(lookup.lookup(IpAddresses.bytes("223.5.5.5")).countryCode(), "CN");
            Assert.assertEquals(lookup.lookup(IpAddresses.bytes("2001:4860:4860::8888")).countryCode(), "US");
            Assert.assertEquals(lookup.lookup(IpAddresses.bytes("2400:3200::1")).countryCode(), "CN");
        }
    }

    private interface DirectoryTest { void run(Path directory) throws Exception; }
    private static void withDirectory(final DirectoryTest test) throws Exception {
        final Path directory = Files.createTempDirectory("volo-mmdb-test-");
        try { test.run(directory); }
        finally {
            try (var files = Files.walk(directory)) {
                for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
