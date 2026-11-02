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

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.json.JSONObject;

/**
 * Option management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.6.0
 */
@Service
public class OptionMgmtService {

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Adds or updates the specified option.
     *
     * @param option the specified option
     * @return option id
     * @throws ServiceException
     */
    public String addOrUpdateOption(final JSONObject option) throws ServiceException {
        final Transaction transaction = optionRepository.beginTransaction();

        try {
            String id = option.optString(Keys.OBJECT_ID);

            if (StringUtils.isBlank(id)) {
                id = optionRepository.add(option);
            } else {
                final JSONObject old = optionRepository.get(id);

                if (null == old) { // The id is specified by caller
                    id = optionRepository.add(option);
                } else {
                    old.put(Option.OPTION_CATEGORY, option.optString(Option.OPTION_CATEGORY));
                    old.put(Option.OPTION_VALUE, option.optString(Option.OPTION_VALUE));

                    optionRepository.update(id, old);
                }
            }

            transaction.commit();

            return id;
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            throw new ServiceException(e);
        }
    }

    /**
     * Removes the option specified by the given option id.
     *
     * @param optionId the given option id
     * @throws ServiceException service exception
     */
    public void removeOption(final String optionId) throws ServiceException {
        final Transaction transaction = optionRepository.beginTransaction();

        try {
            optionRepository.remove(optionId);

            transaction.commit();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            throw new ServiceException(e);
        }
    }

    /** Write the new document, its manifest and old-chunk cleanup in one transaction. */
    public synchronized void storeDocument(final String id, final String category, final String value) throws ServiceException {
        if (value.length() > 1_000_000) throw new IllegalArgumentException("配置内容过多。");
        final Transaction transaction = optionRepository.beginTransaction();
        try {
            final JSONObject previous = optionRepository.get(id);
            final String generation = java.util.UUID.randomUUID().toString().replace("-", "");
            String rootValue = value;
            if (value.length() > 9000) {
                int offset = 0, count = 0;
                while (offset < value.length()) {
                    int end = Math.min(offset + 9000, value.length());
                    if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                    optionRepository.add(new JSONObject().put(Keys.OBJECT_ID, id + "." + generation + "." + count++)
                            .put(Option.OPTION_CATEGORY, category).put(Option.OPTION_VALUE, value.substring(offset, end)));
                    offset = end;
                }
                // Stable prefix distinguishes the manifest from ordinary JSON objects.
                rootValue = "{\"storage\":\"chunks\",\"generation\":\"" + generation + "\",\"count\":" + count + "}";
            }
            final JSONObject root = new JSONObject().put(Keys.OBJECT_ID, id).put(Option.OPTION_CATEGORY, category).put(Option.OPTION_VALUE, rootValue);
            if (previous == null) optionRepository.add(root); else optionRepository.update(id, root);
            if (previous != null && previous.optString(Option.OPTION_VALUE).startsWith("{\"storage\":\"chunks\"")) {
                final JSONObject old = new JSONObject(previous.getString(Option.OPTION_VALUE));
                for (int i = 0; i < old.getInt("count"); i++) optionRepository.remove(id + "." + old.getString("generation") + "." + i);
            }
            transaction.commit();
        } catch (Exception failure) {
            if (transaction.isActive()) transaction.rollback();
            throw new ServiceException(failure);
        } finally { optionRepository.evictOption(id); }
    }
}
