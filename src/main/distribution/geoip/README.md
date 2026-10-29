Offline IP database: ip2region v3.18.0 (https://github.com/lionsoul2014/ip2region).

The distribution includes IPv4 and IPv6 xdb files. Province/city results are approximate and depend on the database snapshot; private or unknown addresses may have no location. No address is sent to an external lookup service.

Source builds: run `node scripts/fetch-geoip.mjs` before Maven packaging. The script verifies pinned SHA-256 digests. Database binaries are excluded from Git. The upstream license is reproduced in LICENSE.ip2region.

To use another local database directory, set `geoipDatabaseDir` in WEB-INF/classes/local.properties and restart. Supported filenames are `ip2region_v4.xdb`, `ip2region_v6.xdb`, `GeoLite2-City.mmdb`, `GeoIP2-City.mmdb`, `GeoLite2-Country.mmdb` and `GeoIP2-Country.mmdb`. City MMDB takes precedence, followed by xdb and Country MMDB. Names prefer Simplified Chinese and fall back to English. ASN databases describe networks, not province/city locations.

An existing Nginx GeoIP directory can be shared, for example `geoipDatabaseDir=/usr/local/nginx-module/geoip`. The Java service user needs directory traversal and file read permissions only. GeoLite2/GeoIP2 MMDB files are supplied and updated by the administrator; they are not redistributed in this package. Restart Volo after replacing database files.
