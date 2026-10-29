/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.b3log.latke.Latkes;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;

/** Local-only city lookup, with shared vector indexes and a bounded result cache. */
public final class IpGeo {
    private static final Logger LOGGER = Logger.getLogger(IpGeo.class);
    private static final Location UNKNOWN = new Location("", "");
    public record Location(String region, String countryCode, String province, String city) {
        public Location(final String region, final String countryCode) { this(region, countryCode, "", ""); }
    }
    private static final Map<String, Location> CACHE = new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(final Map.Entry<String, Location> entry) { return size() > 4096; }
    };
    private static volatile Databases databases;
    private record Database(Path path, byte[] index) { }
    private IpGeo() { }

    public static String region(final String ip) {
        return lookup(ip).region();
    }

    public static String countryCode(final String ip) { return lookup(ip).countryCode(); }

    public static Location details(final String ip) { return lookup(ip); }

    private static Location lookup(final String ip) {
        final String address = IpAddresses.canonical(ip);
        if (address.isEmpty()) return UNKNOWN;
        synchronized (CACHE) { if (CACHE.containsKey(address)) return CACHE.get(address); }
        Location result = UNKNOWN;
        try {
            result = databases().lookup(IpAddresses.bytes(address));
        } catch (Exception unavailable) {
            // Missing databases and unknown ranges do not stop comments or trigger a remote lookup.
        }
        synchronized (CACHE) { CACHE.put(address, result); }
        return result;
    }

    private static synchronized Databases databases() {
        if (databases != null) return databases;
        final String configured = Latkes.getLocalProperty("geoipDatabaseDir");
        final Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("base.dir", "."), "geoip") : Path.of(configured);
        databases = new Databases(directory.toAbsolutePath());
        return databases;
    }

    public static synchronized void close() {
        if (databases != null) databases.close();
        databases = null;
        synchronized (CACHE) { CACHE.clear(); }
    }

    /** Readers are shared safely across lookups; xdb searchers remain per request. */
    static final class Databases implements AutoCloseable {
        private final Path directory;
        private final Reader city;
        private final Reader country;
        private Database v4;
        private Database v6;

        Databases(final Path directory) {
            this.directory = directory;
            city = open("City");
            country = open("Country");
        }

        private Reader open(final String type) {
            for (final String prefix : List.of("GeoLite2-", "GeoIP2-")) {
                final Path path = directory.resolve(prefix + type + ".mmdb");
                if (!Files.isRegularFile(path)) continue;
                // Keep one bounded snapshot so database updaters can replace the file on Windows too.
                try { return new Reader(path.toFile(), Reader.FileMode.MEMORY, new CHMCache(4096)); }
                catch (IOException unavailable) {
                    LOGGER.log(Level.WARN, "Unable to open offline IP database [{0}]", path);
                }
            }
            return null;
        }

        String region(final byte[] bytes) {
            return lookup(bytes).region();
        }

        Location lookup(final byte[] bytes) {
            // Construct only from parsed address bytes: never resolve hostnames over DNS.
            final InetAddress address;
            try { address = InetAddress.getByAddress(bytes); }
            catch (IOException invalid) { return UNKNOWN; }
            Location result = mmdb(city, address);
            if (!result.countryCode().isEmpty() && !result.province().isEmpty() && !result.city().isEmpty()) return result;
            Searcher searcher = null;
            try {
                final boolean ipv4 = bytes.length == 4;
                final Database database = xdb(ipv4);
                if (database != null) {
                    searcher = Searcher.newWithVectorIndex(ipv4 ? Version.IPv4 : Version.IPv6,
                            database.path().toString(), database.index());
                    final String raw = searcher.search(address.getHostAddress());
                    result = merge(result, xdbLocation(raw));
                }
            } catch (Exception unavailable) {
                // A missing or incompatible xdb can still fall back to a country mmdb.
            } finally {
                if (searcher != null) try { searcher.close(); } catch (IOException ignored) { }
            }
            return merge(result, mmdb(country, address));
        }

        private synchronized Database xdb(final boolean ipv4) throws IOException {
            Database database = ipv4 ? v4 : v6;
            if (database != null) return database;
            final Path path = directory.resolve(ipv4 ? "ip2region_v4.xdb" : "ip2region_v6.xdb");
            if (!Files.isRegularFile(path)) return null;
            database = new Database(path, Searcher.loadVectorIndexFromFile(path.toString()));
            if (ipv4) v4 = database; else v6 = database;
            return database;
        }

        private static Location mmdb(final Reader reader, final InetAddress address) {
            if (reader == null) return UNKNOWN;
            try {
                final Map<?, ?> record = reader.get(address, Map.class);
                return mmdbLocation(record);
            } catch (IOException unavailable) { return UNKNOWN; }
        }

        @Override public void close() {
            for (final Reader reader : new Reader[]{city, country}) {
                if (reader != null) try { reader.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static String countryCodeValue(final String value) {
        final String code = value.strip().toUpperCase(java.util.Locale.ROOT);
        return code.matches("[A-Z]{2}") ? code : "";
    }

    private static Location merge(final Location primary, final Location fallback) {
        // Never combine administrative areas belonging to different countries.
        if (!primary.countryCode().isEmpty() && !fallback.countryCode().isEmpty()
                && !primary.countryCode().equals(fallback.countryCode())) return primary;
        return new Location(primary.region().isEmpty() ? fallback.region() : primary.region(),
                primary.countryCode().isEmpty() ? fallback.countryCode() : primary.countryCode(),
                primary.province().isEmpty() ? fallback.province() : primary.province(),
                primary.city().isEmpty() ? fallback.city() : primary.city());
    }

    static Location mmdbLocation(final Map<?, ?> record) {
        if (record == null) return UNKNOWN;
        final Object value = record.get("country");
        final String code = value instanceof Map<?, ?> country ? String.valueOf(country.get("iso_code")) : "";
        final Object first = record.get("subdivisions") instanceof List<?> parts && !parts.isEmpty() ? parts.get(0) : null;
        return new Location(location(record), countryCodeValue(code), placeName(first), placeName(record.get("city")));
    }

    static Location xdbLocation(final String raw) {
        final String[] fields = raw == null ? new String[0] : raw.split("\\|", -1);
        return new Location(location(raw), countryCodeValue(fields.length > 4 ? fields[4] : ""),
                field(fields, 1), field(fields, 2));
    }

    private static String field(final String[] fields, final int index) {
        if (index >= fields.length) return "";
        final String value = fields[index].trim();
        return value.equals("0") ? "" : value;
    }

    private static String placeName(final Object value) {
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        addName(names, value);
        return names.isEmpty() ? "" : names.iterator().next();
    }

    /** GeoLite2 and GeoIP2 share the country/subdivisions/city schema. */
    static String location(final Map<?, ?> record) {
        if (record == null) return "";
        final LinkedHashSet<String> parts = new LinkedHashSet<>();
        addName(parts, record.get("country"));
        if (record.get("subdivisions") instanceof List<?> subdivisions) {
            for (final Object subdivision : subdivisions) addName(parts, subdivision);
        }
        addName(parts, record.get("city"));
        return String.join(" · ", parts);
    }

    private static void addName(final LinkedHashSet<String> parts, final Object value) {
        if (!(value instanceof Map<?, ?> place)) return;
        if (place.get("names") instanceof Map<?, ?> names) {
            for (final String locale : List.of("zh-CN", "en")) {
                if (names.get(locale) instanceof String name && !name.isBlank()) { parts.add(name.trim()); return; }
            }
        }
        if (place.get("iso_code") instanceof String code && !code.isBlank()) parts.add(code.trim());
    }

    /** ip2region v3 fields: country, province, city, ISP, country code. */
    public static String location(final String raw) {
        if (raw == null || raw.isBlank()) return "";
        final String[] fields = raw.split("\\|", -1);
        final LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(3, fields.length); i++) {
            final String part = fields[i].trim();
            if (!part.isEmpty() && !part.equals("0")) parts.add(part);
        }
        return String.join(" · ", parts);
    }
}
