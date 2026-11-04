/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo.repository;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.model.ArchiveDate;
import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Archive date repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class ArchiveDateRepository extends AbstractRepository {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArchiveDateRepository.class);

    /**
     * Archive date-Article repository.
     */
    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    /**
     * Public constructor.
     */
    public ArchiveDateRepository() {
        super(ArchiveDate.ARCHIVE_DATE.toLowerCase());
    }

    /**
     * Gets an archive date by the specified archive date string.
     *
     * @param archiveDate the specified archive date stirng (yyyy/MM)
     * @return an archive date, {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByArchiveDate(final String archiveDate) throws RepositoryException {
        if (archiveDate == null || !archiveDate.matches("\\d{4}/(0[1-9]|1[0-2])")) return null;
        try {
            final YearMonth month = YearMonth.parse(archiveDate.replace('/', '-'));
            final List<JSONObject> dates = datesInMonth(month);
            return dates.isEmpty() ? null : dates.get(0);
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    private List<JSONObject> datesInMonth(final YearMonth month) throws RepositoryException {
        final ZoneId zone = ZoneId.systemDefault();
        final long start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        final long end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return select("SELECT * FROM " + getName() + " WHERE archiveTime >= ? AND archiveTime < ? ORDER BY archiveTime, oId", start, end);
    }

    /** Resolves all records represented by the same public yyyy/MM link. */
    public List<String> getMonthArchiveIds(final String archiveDateId) throws RepositoryException {
        final List<JSONObject> rows = select("SELECT * FROM " + getName() + " WHERE oId = ?", archiveDateId);
        final List<String> ids = new ArrayList<>();
        if (rows.isEmpty()) return ids;
        for (final JSONObject row : datesInMonth(monthOf(rows.get(0)))) ids.add(row.getString(Keys.OBJECT_ID));
        return ids;
    }

    private static YearMonth monthOf(final JSONObject row) {
        return YearMonth.from(Instant.ofEpochMilli(row.getLong(ArchiveDate.ARCHIVE_TIME)).atZone(ZoneId.systemDefault()));
    }

    /** Public month counts and detail queries use the same union of published articles. */
    public List<JSONObject> getPublishedArchiveDates() throws RepositoryException {
        final List<JSONObject> dates = select("SELECT * FROM " + getName() + " ORDER BY archiveTime DESC, oId");
        if (dates.isEmpty()) return new ArrayList<>();
        final java.util.Map<String, Integer> countsById = archiveDateArticleRepository.getPublishedArticleCountsByArchiveDate();
        final LinkedHashMap<YearMonth, List<JSONObject>> months = new LinkedHashMap<>();
        for (final JSONObject date : dates) months.computeIfAbsent(monthOf(date), key -> new ArrayList<>()).add(date);
        final List<JSONObject> result = new ArrayList<>();
        for (final List<JSONObject> month : months.values()) {
            final int count;
            if (month.size() == 1) {
                count = countsById.getOrDefault(month.get(0).getString(Keys.OBJECT_ID), 0);
            } else {
                int nonZeroRecords = 0;
                int singleCount = 0;
                final List<String> ids = new ArrayList<>(month.size());
                for (final JSONObject date : month) {
                    final String id = date.getString(Keys.OBJECT_ID);
                    ids.add(id);
                    final int c = countsById.getOrDefault(id, 0);
                    if (c > 0) {
                        nonZeroRecords++;
                        singleCount = c;
                    }
                }
                count = nonZeroRecords == 0 ? 0
                        : nonZeroRecords == 1 ? singleCount
                        : archiveDateArticleRepository.getPublishedArticleCount(ids);
            }
            if (count < 0) throw new RepositoryException("Could not count published archive articles");
            if (count == 0) continue;
            result.add(month.get(0).put(ArchiveDate.ARCHIVE_DATE_T_PUBLISHED_ARTICLE_COUNT, count));
        }
        return result;
    }

    /**
     * Get archive dates.
     *
     * @return a list of archive date, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getArchiveDates() throws RepositoryException {
        final Query query = new Query().addSort(ArchiveDate.ARCHIVE_TIME, SortDirection.DESCENDING).setPageCount(1);
        final List<JSONObject> ret = getList(query);
        if (ret.isEmpty()) {
            return ret;
        }
        final java.util.Map<String, Integer> countsById = archiveDateArticleRepository.getPublishedArticleCountsByArchiveDate();
        for (final JSONObject archiveDate : ret) {
            final String archiveDateId = archiveDate.optString(Keys.OBJECT_ID);
            archiveDate.put(ArchiveDate.ARCHIVE_DATE_T_PUBLISHED_ARTICLE_COUNT, countsById.getOrDefault(archiveDateId, 0));
        }

        return ret;
    }
}
