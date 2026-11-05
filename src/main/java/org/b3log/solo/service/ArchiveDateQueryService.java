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
package org.b3log.solo.service;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.ArchiveDate;
import org.b3log.solo.repository.ArchiveDateArticleRepository;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.json.JSONObject;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Archive date query service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.0
 */
@Service
public class ArchiveDateQueryService {

    public static final String MONTH_ARCHIVE_IDS = "monthArchiveIds";

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArchiveDateQueryService.class);

    /**
     * Archive date repository.
     */
    @Inject
    private ArchiveDateRepository archiveDateRepository;

    /**
     * Archive date-Article repository.
     */
    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    /**
     * Gets published article count of an archive date specified by the given archive date id.
     *
     * @param archiveDateId the given archive date id
     * @return published article count, returns {@code -1} if occurred an exception
     */
    public int getArchiveDatePublishedArticleCount(final String archiveDateId) {
        try {
            return archiveDateArticleRepository.getPublishedArticleCount(archiveDateRepository.getMonthArchiveIds(archiveDateId));
        } catch (final RepositoryException error) {
            LOGGER.log(Level.ERROR, "Gets archive month count failed", error);
            return -1;
        }
    }

    /**
     * Gets all archive dates.
     *
     * @return a list of archive dates, returns an empty list if not found
     * @throws ServiceException service exception
     */
    public List<JSONObject> getArchiveDates() throws ServiceException {
        try {
            return archiveDateRepository.getPublishedArchiveDates();
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets archive dates failed", e);
            throw new ServiceException("Gets archive dates failed");
        }
    }

    /**
     * Gets an archive date by the specified archive date string, resolving all month {@code oId}s
     * and the published article count without repeating month-range lookups.
     *
     * @param archiveDateString the specified archive date string (yyyy/MM)
     * @return wrapper containing {@code archiveDate} and {@code monthArchiveIds}, or {@code null} if not found
     * @throws ServiceException service exception
     */
    public JSONObject getByArchiveDateString(final String archiveDateString) throws ServiceException {
        if (null == archiveDateString || !archiveDateString.matches("\\d{4}/(0[1-9]|1[0-2])")) {
            return null;
        }

        try {
            final YearMonth month = YearMonth.parse(archiveDateString.replace('/', '-'));
            final ZoneId zone = ZoneId.systemDefault();
            final long start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
            final long end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
            final List<JSONObject> dates = archiveDateRepository.select(
                    "SELECT oId, archiveTime FROM " + archiveDateRepository.getName()
                            + " WHERE archiveTime >= ? AND archiveTime < ? ORDER BY archiveTime, oId",
                    start, end);
            if (null == dates || dates.isEmpty()) {
                return null;
            }

            final JSONObject archiveDate = dates.get(0);
            final List<String> monthIds = new ArrayList<>(dates.size());
            for (final JSONObject row : dates) {
                final String id = row.optString(Keys.OBJECT_ID);
                if (!id.isEmpty()) {
                    monthIds.add(id);
                }
            }

            final int articleCount = archiveDateArticleRepository.getPublishedArticleCount(monthIds);
            archiveDate.put(ArchiveDate.ARCHIVE_DATE_T_PUBLISHED_ARTICLE_COUNT, articleCount);

            final JSONObject ret = new JSONObject();
            ret.put(ArchiveDate.ARCHIVE_DATE, archiveDate);
            ret.put(MONTH_ARCHIVE_IDS, (Object) monthIds);
            return ret;
        } catch (final DateTimeParseException e) {
            return null;
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets archive date[string=" + archiveDateString + "] failed", e);
            throw new ServiceException("Gets archive date[string=" + archiveDateString + "] failed");
        }
    }
}
