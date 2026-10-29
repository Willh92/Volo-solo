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
<header class="header">
    <div class="fn__flex-1">
        <a href="${servePath}" rel="start" class="vditor-tooltipped__w vditor-tooltipped" aria-label="${blogTitle}">
            <i class="icon__home"></i>
        </a>
        <#list pageNavigations as page>
            <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}"
                class="vditor-tooltipped__w vditor-tooltipped" rel="section" aria-label="${page.pageTitle?html}">
                <#if page.pageIcon !=''><img src="${page.pageIcon?html}">
                    <#else>
                        <i class="icon__page"></i>
                </#if>
            </a>
        </#list>
        <a href="${servePath}/categories.html" rel="section" aria-label="${categoryLabel}" class="vditor-tooltipped vditor-tooltipped__w">
            <i class="icon__category"></i>
        </a>
        <a href="${servePath}/tags.html" rel="section" aria-label="${allTagsLabel}" class="vditor-tooltipped vditor-tooltipped__w">
            <i class="icon__tags"></i>
        </a>
        <a href="${servePath}/archives.html" aria-label="${archiveLabel}" class="vditor-tooltipped vditor-tooltipped__w">
            <i class="icon__inbox"></i>
        </a>
        <a rel="archive" href="${servePath}/links.html" aria-label="${linkLabel}" class="vditor-tooltipped vditor-tooltipped__w">
            <i class="icon__link"></i>
        </a>
        <a rel="archive" href="${servePath}/follows.html" aria-label="${followLabel}" class="vditor-tooltipped vditor-tooltipped__w">
            <i class="icon__link"></i>
        </a>
        <#if interactive=="on">
            <a href="${servePath}/search" class="vditor-tooltipped__w vditor-tooltipped" aria-label="${searchLabel}">
                <i class="icon__search"></i>
            </a>
        </#if>
        <a rel="alternate" href="${servePath}/rss.xml" rel="section" aria-label="RSS" class="vditor-tooltipped vditor-tooltipped__w">
            <i class="icon__rss"></i>
        </a>
        <div class="skin-theme-slot" data-bolo-theme-slot></div>
    </div>
    <div class="jane-header-tools">
        <span onclick="Util.goTop()" data-jane-back-top
            aria-label="${putTopLabel}" class="vditor-tooltipped vditor-tooltipped__w">
            <i class="icon__up"></i>
        </span>
    </div>
</header>
