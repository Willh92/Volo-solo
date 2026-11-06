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
package pers.adlered.blog_platform_export_tool.module.cnblogs;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import pers.adlered.blog_platform_export_tool.module.ModuleService;
import pers.adlered.blog_platform_export_tool.module.TranslateResult;
import pers.adlered.blog_platform_export_tool.util.Decompress;
import pers.adlered.blog_platform_export_tool.util.XML;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * <h3>BlogPlatformExportTool</h3>
 * <p>博客园导入模块</p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-05-23
 **/
public class CNBLOGSModuleServiceImpl implements ModuleService {

    private static final Logger LOGGER = Logger.getLogger(CNBLOGSModuleServiceImpl.class);

    @Override
    public List<TranslateResult> analyze() {
        try {
            return analyze(Decompress.currentPath());
        } finally {
            Decompress.clearCurrentPath();
        }
    }

    public List<TranslateResult> analyze(final String xmlFolder) {
        final List<TranslateResult> list = new ArrayList<>();
        final List<String> fileList = XML.getAvailableFiles(xmlFolder);
        for (final String path : fileList) {
            final File file = new File(path);
            try (final XmlReader xmlReader = new XmlReader(file)) {
                final SyndFeedInput syndFeedInput = new SyndFeedInput();
                syndFeedInput.setAllowDoctypes(false);
                final SyndFeed syndFeed = syndFeedInput.build(xmlReader);
                final List<SyndEntry> entries = syndFeed.getEntries();
                if (entries == null) {
                    continue;
                }
                for (final SyndEntry j : entries) {
                    if (j == null) {
                        continue;
                    }
                    final TranslateResult translateResult = new TranslateResult();
                    translateResult.setAuthor(j.getAuthor() == null ? "" : j.getAuthor());
                    final SyndContent desc = j.getDescription();
                    final String body = (desc != null && desc.getValue() != null) ? desc.getValue() : "";
                    translateResult.setArticleContent(body);
                    translateResult.setLink(j.getLink() == null ? "" : j.getLink());
                    final String rawTitle = j.getTitle();
                    translateResult.setTitle(rawTitle != null && !rawTitle.isBlank() ? rawTitle.trim() : "Untitled");
                    final Date pubDate = j.getPublishedDate() != null
                            ? j.getPublishedDate()
                            : (j.getUpdatedDate() != null ? j.getUpdatedDate() : new Date());
                    translateResult.setDate(pubDate);
                    list.add(translateResult);
                }
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Failed to parse CNBlogs XML file [" + file.getName() + "]", e);
            }
        }
        return list;
    }
}
