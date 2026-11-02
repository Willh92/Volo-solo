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
<header class="header header--${type}">
    <div class="wrapper header__title">
        <h1 class="header__h1 fn__flex-inline">
            <img src="${faviconURL}">
            <a href="${servePath}" rel="start" class="header__title">${blogTitle}</a>
        </h1>
        <h2 class="header__h2">${blogSubtitle}</h2>
    </div>
    <nav class="wrapper header__nav fn__clear">
        <a href="${servePath}" rel="start">
            <#if type == 'article'>
                ${blogTitle}
            <#else>
                ${indexLabel}
            </#if>
        </a>

        <#list pageNavigations as page>
            <a class="fn__flex-inline" href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="section">
                <#if page.pageIcon != ''><img src="${page.pageIcon?html}"></#if> ${page.pageTitle?html}
            </a>
        </#list>


        <a href="${servePath}/search">
            ${searchLabel}
        </a>


        <div class="fn__right">
            <#include "../../common-template/macro-user_site.ftl">
            <@userSite dir=""></@userSite>
            <a rel="alternate" href="${servePath}/rss.xml">
                RSS
            </a>
            <#include "../../common-template/macro-management.ftl"><@managementLinks/>
            <div class="skin-theme-slot" data-bolo-theme-slot></div>
        </div>
    </nav>
</header>
</#macro>
