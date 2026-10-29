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
<nav class="navbar navbar-main  is-paddingless ">
    <div class="container ">
        <div class="navbar-brand transparent">
            <a class="navbar-item navbar-logo" href="${servePath}" rel="start">
                <img src="${faviconURL}" alt="${blogTitle!}" height="28"/>
                <span class="fantastic-brand-title">${(blogTitle!'')?html}</span>
            </a>
            <div class="skin-theme-slot fantastic-theme-mobile" data-bolo-theme-slot></div>
            <button type="button" class="navbar-burger burger" data-target="navMenu" aria-controls="navMenu" aria-expanded="false" aria-label="${(navigationLabel!'导航菜单')?html}">
                    <span></span>
                    <span></span>
                    <span></span>
                </button>
        </div>
        <div id="navMenu" class="navbar-menu transparent">
            <div class="navbar-start transparent">
                <div class="navbar-start">
                    <a class="navbar-item fantastic-menu-core" href="${servePath}/">${indexLabel}</a>
                    <a class="navbar-item fantastic-menu-core" href="${servePath}/archives.html">${archiveLabel}</a>
                    <a class="navbar-item fantastic-menu-core" href="${servePath}/tags.html">${allTagsLabel}</a>
                    <a class="navbar-item fantastic-menu-core" href="${servePath}/category.html">${categoryLabel}</a>
                    <a class="navbar-item fantastic-menu-core" href="${servePath}/links.html">${linkLabel}</a>
                    <a class="navbar-item fantastic-menu-core" href="${servePath}/follows.html">${followLabel}</a>
                    <#if interactive == "on"><a class="navbar-item fantastic-menu-core" href="${servePath}/search">${searchLabel}</a></#if>
                    <#list pageNavigations as page>
                        <a class="navbar-item" href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}"
                           rel="section">
                            <#if page.pageIcon !=''><img class="page-icon" src="${page.pageIcon?html}"></#if>
                            ${page.pageTitle?html}
                        </a>
                    </#list>
                </div>
            </div>
            <div class="navbar-end">
                <div class="skin-theme-slot fantastic-theme-desktop" data-bolo-theme-slot></div>
            </div>
        </div>
    </div>
</nav>


