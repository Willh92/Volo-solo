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
<div class="side fn__flex-column">
    <div class="overlay">
        <#if article?? && article.articleToC?? && article.articleToC?size gt 0>
        <button type="button" onclick="Yilia.toggleToc(true)" class="toc-btn" aria-expanded="true">
            ${tocLabel}
        </button>
        </#if>
    </div>
    <#include "../../common-template/macro-user_site.ftl" />
    <div class="user__sites">
        <@userSite dir="" />
    </div>
    <header class="content fn__flex-1">
        <a href="${servePath}">
            <img class="avatar" src="${adminUser.userAvatar}" title="${(adminUser.userName!'')?html}" alt="${(adminUser.userName!'')?html}" />
        </a>
        <hgroup>
            <h1>
                <a href="${servePath}">
                    ${blogTitle}
                </a>
            </h1>
        </hgroup>
        <div class="yilia-profile-tools"><p class="subtitle">${blogSubtitle}</p><div class="skin-theme-slot" data-bolo-theme-slot></div></div>
        <nav>
            <ul>
                <#list pageNavigations as page>
                    <li>
                        <a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}">
                            <#if page.pageIcon !=''><img class="page-icon" src="${page.pageIcon?html}"></#if>
                            ${page.pageTitle?html}
                        </a>
                    </li>
                </#list>
                <li>
                    <a href="${servePath}/dynamic.html">
                        ${dynamicLabel}
                    </a>
                    &nbsp; &nbsp;
                    <a href="${servePath}/tags.html">
                        ${allTagsLabel}
                    </a>
                    &nbsp; &nbsp;
                    <a href="${servePath}/archives.html">
                        ${archiveLabel}
                    </a>
                </li>
                <li>
                    <a href="${servePath}/links.html">
                        ${linkLabel}
                    </a>
                    &nbsp; &nbsp;
                    <a href="${servePath}/follows.html">
                        ${followLabel}
                    </a>

                        &nbsp; &nbsp;
                        <a href="${servePath}/search?keyword=">
                            Search
                        </a>

                </li>
            </ul>
        </nav>
    </header>
    <footer>
        <#if noticeBoard??>
            <div class="vditor-reset">
                ${noticeBoard}
            </div>
        </#if>
        <a rel="alternate" href="${servePath}/rss.xml" title="${subscribeLabel}" class="icon-rss"></a>
        <#if isAdminLoggedIn!false><a href="${adminPanelURL?html}" title="后台管理" aria-label="后台管理" class="icon-setting" data-management-link="panel"></a><a href="${logoutURL?html}" title="退出登录" aria-label="退出登录" class="icon-logout" data-management-link="logout"></a><#elseif isLoggedIn!false><a href="${logoutURL?html}" data-volo-reader-logout data-no-pjax title="退出读者（${(userName!'已登录')?html}）" aria-label="退出读者（${(userName!'已登录')?html}）" class="icon-logout" data-management-link="reader-logout"></a><#else><#if (allowRegister!false) || (commentAccessMode!'all') == 'registered'><a href="${servePath}/user/login" data-volo-auth-trigger="login" title="读者登录" aria-label="读者登录" class="icon-login" data-management-link="reader-login"></a></#if><#if showLoginEntry!false><a href="${adminLoginURL?html}" title="管理员登录" aria-label="管理员登录" class="icon-login" data-management-link="login"></a></#if></#if>
    </footer>
    <#if article?? && article.articleToC?? && article.articleToC?size &gt; 0>
        <nav class="toc" aria-label="${tocLabel?html}" hidden>
            <div class="yilia-toc-heading">
                <strong>${tocLabel}</strong><span class="yilia-toc-count">${article.articleToC?size}</span><div class="skin-theme-slot yilia-toc-theme" data-bolo-theme-slot></div>
                <button type="button" onclick="Yilia.toggleToc(false)" class="yilia-toc-close" aria-label="${closeLabel!'关闭目录'}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button>
            </div>
            <div class="yilia-toc-progress" role="progressbar" aria-label="${tocLabel?html}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><span></span></div>
            <#include "../../common-template/toc.ftl" />
        </nav>
    </#if>
</div>
