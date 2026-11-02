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
    <div class="wrapper">
        <a href="${servePath}" rel="start" class="header__logo">
            <img src="${adminUser.userAvatar}" />
            <span class="pinghsu-brand-title">${blogTitle?html}</span>
        </a>
        <nav class="header__nav mobile__none">
            <a href="${servePath}/tags.html" rel="section">
                Tags
            </a>
            <a href="${servePath}/archives.html">
                Archives
            </a>
            <a rel="archive" href="${servePath}/links.html">
                Links
            </a>
            <a rel="archive" href="${servePath}/follows.html">
                Follows
            </a>

                <a href="${servePath}/search" class="search">
                    <svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 28 28">
                        <path fill="#444" d="M19.385 11.846c0-4.156-3.382-7.538-7.538-7.538s-7.538 3.382-7.538 7.538 3.382 7.538 7.538 7.538 7.538-3.382 7.538-7.538zM28 25.846c0 1.178-0.976 2.154-2.154 2.154-0.572 0-1.127-0.236-1.514-0.639l-5.772-5.755c-1.969 1.363-4.325 2.087-6.714 2.087-6.546 0-11.846-5.3-11.846-11.846s5.3-11.846 11.846-11.846 11.846 5.3 11.846 11.846c0 2.389-0.724 4.745-2.087 6.714l5.772 5.772c0.387 0.387 0.623 0.942 0.623 1.514z"></path>
                    </svg>
                </a>

        </nav>
        <div class="skin-theme-slot" data-bolo-theme-slot></div>
        <button type="button" class="header__bar fn__none" onclick="$(this).next().slideToggle()" aria-label="${(navigationLabel!'导航菜单')?html}">
            <svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20">
                <path d="M3 5h14M3 10h14M3 15h14"></path>
            </svg>
        </button>
        <main class="header__menu fn__none">
            <ul>
                <#include "../../common-template/macro-management.ftl"><@managementLinks itemTag="li" panelIcon="icon__setting" exitIcon="icon__logout" loginIcon="icon__login"/>
                <#list pageNavigations as page>
                    <li>
                        <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="section">
                            ${page.pageTitle?html}
                        </a>
                    </li>
                </#list>
                <li>
                    <a href="${servePath}/tags.html" rel="section">
                        Tags
                    </a>
                </li>
                <li>
                    <a href="${servePath}/archives.html">
                        Archives
                    </a>
                </li>
                <li>
                    <a rel="archive" href="${servePath}/links.html">
                        Links
                    </a>
                </li>
                <li>
                    <a rel="archive" href="${servePath}/follows.html">
                        Follows
                    </a>
                </li>

                    <li>
                        <a href="${servePath}/search">
                            Search
                        </a>
                    </li>

                <li>
                    <a rel="alternate" href="${servePath}/rss.xml" rel="section">
                        RSS
                    </a>
                </li>
            </ul>
        </main>
    </div>
</header>
