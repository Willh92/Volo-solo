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
<div id="mobile-sidebar">
    <div id="menu_mask"></div>
    <div id="mobile-sidebar-menus">
        <button type="button" class="toggle-menu close sidebar-menu-close" aria-controls="mobile-sidebar-menus" aria-expanded="false" aria-label="关闭导航菜单">
            <span class="site-page">
                <i class="fa-fw fas fa-arrow-circle-right" aria-hidden="true"></i>
            </span>
        </button>
        <div class="mobile_author_icon"><img class="avatar-img"
                                             src="${adminUser.userAvatar}"
                                             onerror="onerror=null;src='${staticServePath}/skins/${skinDirName}/images/friend_404.gif'" alt="avatar">
        </div>
        <div class="mobile_post_data">
            <div class="mobile_data_item is-center">
                <div class="mobile_data_link"><a href="${servePath}/archives.html">
                        <div class="headline">文章</div>
                        <div class="length_num">${statistic.statisticPublishedBlogArticleCount}</div>
                    </a></div>
            </div>
            <div class="mobile_data_item is-center">
                <div class="mobile_data_link"><a href="${servePath}/tags.html">
                        <div class="headline">标签</div>
                        <div class="length_num">${mostUsedTags?size}</div>
                    </a></div>
            </div>
            <div class="mobile_data_item is-center">
                <div class="mobile_data_link"><a href="${servePath}/categories.html">
                        <div class="headline">分类</div>
                        <div class="length_num">${mostUsedCategories?size}</div>
                    </a></div>
            </div>
        </div>
        <hr>
        <div class="menus_items">

            <div class="menus_item"><a class="site-page" href="${servePath}"><i
                            class="fa-fw fas fa-home"></i><span> 主页</span></a></div>
            <div class="menus_item"><a class="site-page" href="${servePath}/timeaxis"><i
                            class="fa-fw fas fa-archive"></i><span> 时间轴</span></a></div>
            <div class="menus_item"><a class="site-page" href="${servePath}/tags.html"><i
                            class="fa-fw fas fa-tags"></i><span> 标签</span></a></div>
            <div class="menus_item"><a class="site-page" href="${servePath}/categories.html"><i
                            class="fa-fw fas fa-folder-open"></i><span> 分类</span></a></div>
            <div class="menus_item"><a class="site-page" href="${servePath}/links.html"><i
                            class="fa-fw fas fa-link"></i><span> 友情链接</span></a></div>
            <div class="menus_item"><a class="site-page" href="${servePath}/follows.html"><i
                            class="fa-fw fas fa-user-friends"></i><span> 我的关注</span></a></div>
                <#list pageNavigations as page>
                    <div class="menus_item">
                    <a class="site-page" href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}">
                                <#if page.pageIcon != ''>
                                    <i class="fa-fw fas">
                                        <img class="page-icon" src="${page.pageIcon?html}">
                                    </i>
                                </#if>
                                <span> ${page.pageTitle?html}</span>
                            </a>
                    </div>
                </#list>
        </div>
    </div>
</div>
