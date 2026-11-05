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
<#include "macro-header.ftl">
<div id="web_bg" data-type="color"></div>
<link media="all" href="${staticServePath}/skins/${skinDirName}/css/main.css?${staticResourceVersion}"
      rel="stylesheet"/>
<script src="${staticServePath}/js/lib/instantpage.js" type="module" defer></script>
<script src="${staticServePath}/js/lib/lazyload.iife.min.js" async></script>
<script src="${staticServePath}/js/lib/typed.js"></script>
<script>window.Label = window.Label || {}; Label.staticServePath = '${staticServePath?js_string}';</script>
<script src="${staticServePath}/skins/${skinDirName}/js/GlobalConfig.js"></script>
<script src="${staticServePath}/skins/${skinDirName}/js/utils.js"></script>
<script src="${staticServePath}/skins/${skinDirName}/js/activate-power-mode.js"></script>
<#macro header type>
    <header class="header--${type}">
        <nav id="nav" style="opacity: 1; animation: 1s ease 0s 1 normal none running headerNoOpacity;" class="">
            <span class="pull-left" id="blog_name">
                <a class="blog_title" id="site-name" href="${servePath}">${blogTitle} 👋🏼</a>
            </span>
            <span class="pull-right menus">
                <div class="menus_items">
                    <div class="menus_item">
                        <a class="site-page" href="${servePath}">
                            <i class="fa-fw fas fa-home"></i>
                            <span>
                                <#if type == 'article'>
                                    ${blogTitle}
                                <#else>
                                    ${indexLabel}
                                </#if>
                            </span>
                        </a>
                    </div>
                    <div class="menus_item">
                        <a class="site-page" href="${servePath}/timeaxis">
                            <i class="fa-fw fas fa-archive"></i>
                            <span> 时间轴</span>
                        </a>
                    </div>
                    <div class="menus_item">
                        <a class="site-page" href="${servePath}/tags.html">
                            <i class="fa-fw fas fa-tags"></i>
                            <span> 标签</span>
                        </a>
                    </div>
                    <div class="menus_item">
                        <a class="site-page" href="${servePath}/categories.html">
                            <i class="fa-fw fas fa-folder-open"></i>
                            <span> 分类</span></a>
                    </div>
                    <div class="menus_item">
                        <a class="site-page" href="${servePath}/links.html">
                            <i class="fa-fw fas fa-link"></i>
                            <span> 友情链接</span>
                        </a>
                    </div>
                    <div class="menus_item">
                        <a class="site-page" href="${servePath}/follows.html">
                            <i class="fa-fw fas fa-user-friends"></i>
                            <span> 我的关注</span>
                        </a>
                    </div>
                    <#if (enableInstantSearch!true)><div class="menus_item"><a class="site-page" href="${servePath}/search" data-volo-search-trigger title="搜索文章（Ctrl+K）"><i class="fa-fw fas fa-search"></i><span> 搜索</span></a></div></#if>
                    <#if pageNavigations?has_content || (isAdminLoggedIn!false) || (isLoggedIn!false) || (showLoginEntry!false) || (allowRegister!false) || (commentAccessMode!'all') == 'registered'>
                    <div class="menus_item">
                        <a class="site-page">
                            <span class="footer__heart">❤️更多</span>
                            <i class="fas fa-chevron-down menus-expand"></i>
                        </a>
                        <ul class="menus_item_child">
                            <#include "../../common-template/macro-management.ftl"><@managementLinks itemTag="li" linkClass="site-page" panelIcon="fa-fw fas fa-user" exitIcon="fa-fw fas fa-sign-out-alt" loginIcon="fa-fw fas fa-user"/>
                            <#list pageNavigations as page>
                                <li>
                                <a class="site-page" href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}">
                                <#if page.pageIcon != ''>
                                    <i class="fa-fw fas">
                                        <img class="page-icon" src="${page.pageIcon?html}">
                                    </i>
                                </#if>
                                    <span> ${page.pageTitle?html}</span>
                                </a>
                            </li>
                            </#list>
                        </ul>
                    </div>
                    </#if>
                </div>
                <button type="button" class="toggle-menu close" aria-controls="mobile-sidebar-menus" aria-expanded="false" aria-label="展开导航菜单">
                    <span class="site-page">
                        <i class="fas fa-bars fa-fw"></i>
                    </span>
                </button>
            </span>
        </nav>
    </header>
</#macro>

