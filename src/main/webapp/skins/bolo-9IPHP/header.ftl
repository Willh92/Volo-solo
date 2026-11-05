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
<header>
    <div class="banner">
        <div class="fn-clear wrapper">
            <h1 class="fn-inline">
                <a href="${servePath}" rel="start">
                    ${blogTitle}
                </a>
            </h1>
            <small> &nbsp; ${blogSubtitle}</small>
            <div class="theme9-header-tools">
                <div class="skin-theme-slot theme9-mobile" data-bolo-theme-slot></div>
                <button class="icon-list" type="button" aria-label="${(navigationLabel!'导航菜单')?html}" aria-controls="navigation9iphp" aria-expanded="false"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M5 12h14M5 17h14"/></svg></button>
            </div>
            <div class="fn-right">
                <#include "../../common-template/macro-management.ftl"><@managementLinks linkClass="fn__flex-inline" panelIcon="icon-setting" exitIcon="icon-logout" loginIcon="icon-login"/>
            </div>
        </div>
    </div>

    <div class="navbar">
        <div class="fn-clear wrapper">
            <nav class="fn-left">
                <a href="${servePath}">
                    <i class="icon-home"></i>
                    ${indexLabel}
                </a>
                <#list pageNavigations as page>
                    <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="section">
                        <#if page.pageIcon != ''><img class="page-icon" src="${page.pageIcon?html}"></#if>${page.pageTitle?html}
                    </a>
                </#list>
                <#if interactive == "true">
                <a href="${servePath}/dynamic.html" rel="section">
                    <i class="icon-refresh"></i> ${dynamicLabel}
                </a>
                </#if>
                <a href="${servePath}/tags.html" rel="section">
                    <i class="icon-tags"></i> ${allTagsLabel}
                </a>
                <a href="${servePath}/archives.html">
                    <i class="icon-inbox"></i> ${archiveLabel}
                </a>
                <a rel="archive" href="${servePath}/links.html">
                    <i class="icon-link"></i> ${linkLabel}
                </a>
                <a rel="archive" href="${servePath}/follows.html">
                    <i class="icon-link"></i> ${followLabel}
                </a>
                <a rel="alternate" href="${servePath}/rss.xml" rel="section">
                    <i class="icon-rss"></i> RSS
                </a>
            </nav>
            <div class="fn-right navigation-tools">

                <form class="form" action="${servePath}/search">
                    <input autocomplete="off" placeholder="${searchLabel}" id="search" type="text" name="keyword"/>
                    <button type="submit"><i class="icon-search"></i></button>
                </form>

                <div class="skin-theme-slot theme9-desktop" data-bolo-theme-slot></div>
            </div>
        </div>
    </div>
</header>
<div class="responsive fn-none">
    <ul class="list" id="navigation9iphp">
        <li>
            <a href="${servePath}">
                <i class="icon-home"></i>
                ${indexLabel}
            </a>
        </li>
        <#list pageNavigations as page>
            <li>
                <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="section">
                    <#if page.pageIcon != ''><img class="page-icon" src="${page.pageIcon?html}"></#if>${page.pageTitle?html}
                </a>
            </li>
        </#list>
        <li>
            <a href="${servePath}/dynamic.html" rel="section">
                <i class="icon-refresh"></i> ${dynamicLabel}
            </a>
        </li>
        <li>
            <a href="${servePath}/tags.html" rel="section">
                <i class="icon-tags"></i> ${allTagsLabel}
            </a>
        </li>
        <li>
            <a href="${servePath}/archives.html">
                <i class="icon-inbox"></i> ${archiveLabel}
            </a>
        </li>
        <li>
            <a rel="archive" href="${servePath}/links.html">
                <i class="icon-link"></i> ${linkLabel}
            </a>
        </li>
        <li>
            <a rel="archive" href="${servePath}/follows.html">
                <i class="icon-link"></i> ${followLabel}
            </a>
        </li>
        <li>
            <a rel="alternate" href="${servePath}/rss.xml" rel="section">
                <i class="icon-rss"></i> RSS
            </a>
        </li>
        <#include "../../common-template/macro-management.ftl"><@managementLinks itemTag="li" panelIcon="icon-setting" exitIcon="icon-logout" loginIcon="icon-login"/>
    </ul>
</div>
