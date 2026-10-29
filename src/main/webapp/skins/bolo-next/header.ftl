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
    <div class="header-line"></div>
    <div class="fn-clear wrapper">
        <div class="skin-theme-slot" data-bolo-theme-slot></div>
        <div class="logo-wrap">
            <a href="${servePath}" rel="start">
                <span class="logo-line-before"><i></i></span>
                <span class="site-title">${blogTitle}</span>
                <span class="logo-line-after"><i></i></span>
            </a>
        </div>

        <button type="button" class="site-nav-toggle fn-right" aria-label="${(navigationLabel!'导航菜单')?html}" aria-controls="nextMainMenu" aria-expanded="false" onclick="var open=this.getAttribute('aria-expanded')!=='true';this.setAttribute('aria-expanded',String(open));$('#nextMainMenu').stop(true,true)[open?'slideDown':'slideUp'](160);$('.header-line').toggle(open);">
            <span class="btn-bar"></span>
            <span class="btn-bar"></span>
            <span class="btn-bar"></span>
        </button>

        <nav>
            <ul class="menu" id="nextMainMenu">
                <li class="menu-item menu-core"><a href="${servePath}/">${indexLabel}</a></li>
                <li class="menu-item menu-core"><a href="${servePath}/tags.html">${allTagsLabel}</a></li>
                <li class="menu-item menu-core"><a href="${servePath}/archives.html">${archiveLabel}</a></li>
                <li class="menu-item menu-core"><a href="${servePath}/categories.html">${categoryLabel}</a></li>
                <li class="menu-item menu-core"><a href="${servePath}/links.html">${linkLabel}</a></li>
                <li class="menu-item menu-core"><a href="${servePath}/follows.html">${followLabel}</a></li>
                <#if interactive == "on"><li class="menu-item menu-core"><a href="${servePath}/search">${searchLabel}</a></li></#if>
                <#list pageNavigations as page>
                <li class="menu-item">
                    <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="section">
                        ${page.pageTitle?html}
                    </a>
                </li>
                </#list>
            </ul>

            <#if interactive == "on">
            <div class="site-search">
                <form action="${servePath}/search">
                    <input placeholder="${searchLabel}" id="search" type="text" name="keyword"/>
                    <input type="submit" value="" class="fn-none" />
                </form>
            </div>
            </#if>
        </nav>
    </div>
</header>
