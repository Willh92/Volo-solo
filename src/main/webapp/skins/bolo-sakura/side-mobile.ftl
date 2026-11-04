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
<div id="mo-nav">
    <div class="m-avatar">
        <img src="${adminUser.userAvatar}">
    </div>
    <p class="mobile-subtitle">
        ${blogSubtitle}
    </p>
    <div class="mobile">
        <#include "../../common-template/macro-user_site.ftl"/>
        <@userSite dir=""></@userSite>
    </div>

    <div class="m-search">
        <form class="m-search-form" method="get" action="${servePath}/search" role="search">
            <input autocomplete="off" class="m-search-input" type="search" name="keyword" placeholder="搜索..." required>
        </form>
    </div>

    <ul id="menu-new-1" class="menu">

        <li><a href="${servePath}/"><span class="faa-parent animated-hover"><i
                            class="fa fa-home faa-horizontal" aria-hidden="true"></i> 首页</span></a>
        <li><a href="${servePath}/links.html"><span class="faa-parent animated-hover"><i
                            class="fa fa-link faa-horizontal" aria-hidden="true"></i> 友链</span></a>
        <li><a href="${servePath}/follows.html"><span class="faa-parent animated-hover"><i
                            class="fa fa-link faa-horizontal" aria-hidden="true"></i> 关注</span></a>
        <li><a href="${servePath}/tags.html"><span class="faa-parent animated-hover"><i
                            class="fa fa-tag faa-horizontal" aria-hidden="true"></i> 标签</span></a>
        <li><a href="${servePath}/archives.html"><span class="faa-parent animated-hover"><i
                            class="fa fa-archive faa-horizontal" aria-hidden="true"></i> 归档</span></a>
        <#include "../../common-template/macro-management.ftl"><@managementLinks itemTag="li" panelIcon="fa fa-cog" exitIcon="fa fa-sign-out" loginIcon="fa fa-sign-in"/>
        <#list pageNavigations as page>
            <li><a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}"><span
                            class="faa-parent animated-hover">
                                            <#if page.pageIcon?contains("/")>
                                                <img class="icon faa-horizontal" aria-hidden="true" style="height: 16px; width: 16px; vertical-align: -2px; object-fit: contain;"
                                                     src="${page.pageIcon?html}">
                                            <#else>
                                                <i class="fa fa-${page.pageIcon?html} faa-horizontal" aria-hidden="true"></i>
                                            </#if> ${page.pageTitle?html}</span></a></li>
        </#list>
    </ul>
    <p class="mobile-attribution">
        © ${year} <a href="${servePath}">${blogTitle}</a><br><br>
        Powered by <a href="https://github.com/Willh92/Volo-solo" target="_blank">Volo</a><br>
        Theme <a rel="friend" href="https://github.com/mashirozx/Sakura" target="_blank">${skinDirName?remove_beginning('bolo-')}</a>
        by <a rel="friend" href="https://hello.2heng.xin/@mashiro" target="_blank">Mashiro</a><br>
        <span>${viewLabel}</span>
        ${statistic.statisticBlogViewCount}
        <span>${articleLabel}</span>
        ${statistic.statisticPublishedBlogArticleCount}
        <#if interactive == "on">
        <span>${commentLabel}</span>
        ${statistic.statisticPublishedBlogCommentCount}<br>
        </#if>
        ${footerContent}
    </p>
</div>
<button type="button" class="sakura-menu-backdrop" aria-label="关闭菜单" tabindex="-1"></button>
<button type="button" onclick="topFunction()" class="mobile-cd-top" id="moblieGoTop" title="返回顶部" aria-label="返回顶部"><i class="fa fa-chevron-up"
                                                                                            aria-hidden="true"></i>
</button>
