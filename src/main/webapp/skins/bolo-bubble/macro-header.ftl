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
<#macro header type>
    <progress class="fn__progress"></progress>
    <header class="header header--${type}">
        <div class="wrapper header__title">
            <h1 class="header__h1 fn__flex-inline">
                <a href="${servePath}" rel="start">${blogTitle}</a>
            </h1>
            <h2 class="header__desc header__desc--title">${blogSubtitle}</h2>
            <div class="header__desc">
                ${noticeBoard}
            </div>
            <svg class="header__down" id="headerDown" viewBox="0 0 32 32" width="100%" height="100%">
                <path d="M15.992 25.304c-0 0-0 0-0.001 0-0.516 0-0.981-0.216-1.31-0.563l-0.001-0.001-14.187-14.996c-0.306-0.323-0.494-0.76-0.494-1.241 0-0.998 0.809-1.807 1.807-1.807 0.517 0 0.983 0.217 1.313 0.565l0.001 0.001 12.875 13.612 12.886-13.612c0.331-0.348 0.797-0.565 1.314-0.565 0.481 0 0.918 0.187 1.242 0.493l-0.001-0.001c0.723 0.687 0.755 1.832 0.072 2.555l-14.201 14.996c-0.33 0.348-0.795 0.564-1.311 0.564-0.001 0-0.003 0-0.004 0h0z"></path>
            </svg>
        </div>
    </header>
    <button class="side__menu" type="button" aria-label="${(navigationLabel!'导航菜单')?html}" aria-controls="bubbleSideMenu" aria-expanded="false"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M5 12h14M5 17h14"/></svg></button>
    <div class="side__main" id="bubbleSideMenu">
        <div class="side__bg"></div>
        <div class="side__panel">
            <button class="side__close" type="button" aria-label="${(closeLabel!'关闭菜单')?html}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button>

            <div class="side__header">
                <a href="${servePath}" rel="start"><img class="side__logo" src="${adminUser.userAvatar}"></a>

                <div class="side__meta">
                    ${statistic.statisticPublishedBlogArticleCount} ${articleLabel} <br>
                    ${statistic.statisticPublishedBlogCommentCount} ${commentLabel} <br>
                    ${statistic.statisticBlogViewCount} ${viewLabel} <br>
                    ${onlineVisitorCnt} ${onlineVisitorLabel}
                </div>
            </div>

            <div class="side__title">
                <span>ღゝ◡╹)ノ❤️</span>
            </div>
            <div class="side__utilities">
                <a href="${servePath}/search?keyword=">${searchLabel}</a>
                <a rel="alternate" href="${servePath}/rss.xml">RSS</a>
                <div class="skin-theme-slot" data-bolo-theme-slot data-bolo-theme-persistent></div>
            </div>
            <ul class="side__nav">
                <li>
                    <a href="${servePath}" rel="start">
                        <#if type == 'article'>
                            ${blogTitle}
                        <#else>
                            ${indexLabel}
                        </#if>
                    </a>
                </li>
                <#list pageNavigations as page>
                    <li>
                        <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}"><#if page.pageIcon != ''><img
                                class="page-icon" src="${page.pageIcon?html}"></#if>${page.pageTitle?html}</a>
                    </li>
                </#list>
                <li>
                    <a href="${servePath}/category.html">${categoryLabel}</a>
                </li>
                <li>
                    <a href="${servePath}/tags.html">${allTagsLabel}</a>
                </li>
                <li>
                    <a href="${servePath}/archives.html">${archiveLabel}</a>
                </li>
                <li>
                    <a href="${servePath}/links.html">${linkLabel}</a>
                </li>
                <li>
                    <a href="${servePath}/follows.html">${followLabel}</a>
                </li>
                <#include "../../common-template/macro-management.ftl"><@managementLinks itemTag="li"/>
            </ul>
        </div>
    </div>
</#macro>
