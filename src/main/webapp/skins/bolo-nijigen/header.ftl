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
<div class="bg"></div>
<div class="bg bg--1"></div>
<div class="bg bg--2"></div>
<div class="bg bg--3"></div>
<div class="bg bg--4"></div>
<div class="bg bg--5"></div>
<header class="header">
    <div class="header__wrap">
        <a href="${servePath}" rel="start" class="header__logo">
            <i class="icon__home"></i>
            ${blogTitle}
        </a>
        <nav class="mobile__hidden header__nav">
            <#list pageNavigations as page>
                <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="section">
                    <#if page.pageIcon !=''><img class="page-icon" src="${page.pageIcon?html}"></#if>
                    ${page.pageTitle?html}
                </a>
            </#list>
            <#if interactive=="on">
                <a href="${servePath}/dynamic.html" rel="section">
                    <i class="icon__refresh"></i>
                    ${dynamicLabel}
                </a>
            </#if>
            <a href="${servePath}/tags.html" rel="section">
                <i class="icon__tags"></i>
                ${allTagsLabel}
            </a>
            <a href="${servePath}/archives.html">
                <i class="icon__inbox"></i>
                ${archiveLabel}
            </a>
            <a rel="archive" href="${servePath}/links.html">
                <i class="icon__link"></i>
                ${linkLabel}
            </a>
            <a rel="archive" href="${servePath}/follows.html">
                <i class="icon__link"></i>
                ${followLabel}
            </a>
            <a rel="alternate" href="${servePath}/rss.xml" rel="section">
                <i class="icon__rss"></i> RSS
            </a>
        </nav>
        <div class="header__login">
            <#include "../../common-template/macro-management.ftl"><@managementLinks panelIcon="icon__setting" exitIcon="icon__logout" loginIcon="icon__login"/>
            <div class="skin-theme-slot" data-bolo-theme-slot></div>
        </div>
    </div>
</header>
<div class="header__m fn__none">
    <div class="skin-theme-slot" data-bolo-theme-slot></div>
    <i class="icon__list fn__none" onclick="$(this).next().slideToggle()"></i>
    <main class="module__list fn__none"></main>
    <button type="button" class="icon__more" onclick="$(this).next().slideToggle()" aria-label="${(navigationLabel!'导航菜单')?html}"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="5" cy="12" r="1.7"/><circle cx="12" cy="12" r="1.7"/><circle cx="19" cy="12" r="1.7"/></svg></button>
    <main class="module__list">
        <ul>
            <#list pageNavigations as page>
                <li>
                    <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="section">
                        <#if page.pageIcon !=''><img class="page-icon" src="${page.pageIcon?html}"></#if>
                        ${page.pageTitle?html}
                    </a>
                </li>
            </#list>
            <#if interactive=="on">
                <li>
                    <a href="${servePath}/dynamic.html" rel="section">
                        <i class="icon__refresh"></i>
                        ${dynamicLabel}
                    </a>
                </li>
            </#if>
            <li>
                <a href="${servePath}/tags.html" rel="section">
                    <i class="icon__tags"></i>
                    ${allTagsLabel}
                </a>
            </li>
            <li>
                <a href="${servePath}/archives.html">
                    <i class="icon__inbox"></i>
                    ${archiveLabel}
                </a>
            </li>
            <li>
                <a rel="archive" href="${servePath}/links.html">
                    <i class="icon__link"></i>
                    ${linkLabel}
                </a>
            </li>
            <li>
                <a rel="alternate" href="${servePath}/rss.xml" rel="section">
                    <i class="icon__rss"></i> RSS
                </a>
            </li>
            <#include "../../common-template/macro-management.ftl"><@managementLinks itemTag="li" panelIcon="icon__setting" exitIcon="icon__logout" loginIcon="icon__login"/>
        </ul>
    </main>
</div>
