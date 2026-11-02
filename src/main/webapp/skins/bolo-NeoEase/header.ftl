<#--

    Bolo - A stable and beautiful blogging system based in Solo.
    Copyright (c) 2020-present, https://github.com/bolo-blog

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

-->
<div class="header">
    <div class="wrapper">
        <div class="left">
            <h1>
                <a class="title" href="${servePath}">
                    ${blogTitle?html}
                </a>
            </h1>
            <span class="sub-title">${blogSubtitle}</span>
        </div>
        <div class="skin-theme-slot" data-bolo-theme-slot></div>

        <form class="right" action="${servePath}/search">
            <input id="search" type="search" name="keyword" placeholder="${searchLabel}" aria-label="${searchLabel}" />
            <button class="neoease-search-submit" type="submit" aria-label="${searchLabel}"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5"/><path d="m15 15 5 5"/></svg></button>
        </form>

        <div class="clear"></div>
    </div>
</div>
<div class="nav">
    <div class="wrapper">
        <ul>
            <li>
                <a rel="nofollow" href="${servePath}/">${indexLabel}</a>
            </li>
            <#list pageNavigations as page>
            <li>
                <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}"><#if page.pageIcon != ''><img class="page-icon" src="${page.pageIcon?html}"></#if>${page.pageTitle?html}</a>
            </li>
            </#list>
            <li>
                <a href="${servePath}/tags.html">${allTagsLabel}</a>
            </li>
            <li>
                <a rel="alternate" href="${servePath}/rss.xml">RSS<img src="${staticServePath}/images/feed.png" alt="RSS"/></a>
            </li>
            <#include "../../common-template/macro-management.ftl"><@managementLinks itemTag="li"/>
        </ul>
        <div class="right nav-stats">
            <button type="button" class="translate-ico" onclick="goTranslate()" title="Translate" aria-label="Translate">译</button>
            <div class="right">
                ${viewCount1Label}
                <span class="tip">
                    ${statistic.statisticBlogViewCount}
                </span>
                &nbsp;&nbsp;
                ${articleCount1Label}
                <span class="tip">
                    ${statistic.statisticPublishedBlogArticleCount}
                </span>
                <#if interactive == "on">
                &nbsp;&nbsp;
                ${commentCount1Label}
                <span class="tip">
                    ${statistic.statisticPublishedBlogCommentCount}
                </span>
                </#if>
            </div>
        </div>
        <div class="clear"></div>
    </div>
</div>
