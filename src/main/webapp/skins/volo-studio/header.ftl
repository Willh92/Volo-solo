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

<#macro studioCustomNavigation links=pageNavigations>
  <#list links as page><a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="noopener noreferrer" title="${page.pageTitle?html}"><#if page.pageIcon?has_content><#if page.pageIcon?ends_with('/images/github-icon.png') || page.pageIcon?ends_with('/images/github-icon-light.svg')><span class="studio-nav-icon studio-nav-github" aria-hidden="true"></span><#elseif page.pageIcon?ends_with('/images/admin-panel.svg')><span class="studio-nav-icon studio-nav-panel-icon" aria-hidden="true"></span><#elseif page.pageIcon?ends_with('/images/admin-logout.svg')><span class="studio-nav-icon studio-nav-logout-icon" aria-hidden="true"></span><#else><img class="studio-nav-icon" src="${page.pageIcon?html}" alt=""/></#if></#if><span>${page.pageTitle?html}</span></a></#list>
</#macro>
<#assign studioMoreLinks=[]>
<#list pageNavigations as page><#if !(isAdminLoggedIn!false) || (page.pagePermalink != adminPanelURL && page.pagePermalink != logoutURL)><#assign studioMoreLinks=studioMoreLinks+[page]></#if></#list>
<a class="studio-skip" href="#pjax">跳到正文</a>
<header class="studio-header"><div class="studio-header-inner">
  <#assign studioAvatar=((adminUser.userAvatar)!'')?trim>
  <a class="studio-brand" href="${servePath}/"><span class="studio-brand-avatar"><#if studioAvatar?has_content && !studioAvatar?ends_with('/images/volo-mark.svg')><img src="${studioAvatar?html}" alt="" decoding="async" onerror="this.hidden=true;this.nextElementSibling.hidden=false"/><span class="studio-mark" aria-hidden="true" hidden>V.</span><#else><span class="studio-mark" aria-hidden="true">V.</span></#if></span><span>${blogTitle?html}<small>记录 · 思考 · 分享</small></span></a>
  <nav aria-label="主导航"><a href="${servePath}/">首页</a><a href="${servePath}/archives.html">归档</a><a href="${servePath}/tags.html">标签</a><a href="${servePath}/categories.html">分类</a>
    <#if pageNavigations?has_content>
      <div class="studio-custom-links"><@studioCustomNavigation/></div>
      <#if studioMoreLinks?has_content><details class="studio-nav-more"><summary>更多<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5"/></svg></summary><div class="studio-nav-panel"><@studioCustomNavigation links=studioMoreLinks/></div></details></#if>
    </#if>
    <#if isAdminLoggedIn!false><span class="studio-management-mobile"><a href="${adminPanelURL?html}" rel="nofollow" title="后台管理" aria-label="后台管理"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="4" width="17" height="16" rx="2"/><path d="M3.5 9h17M9 9v11"/></svg></a><a href="${logoutURL?html}" rel="nofollow" title="退出登录" aria-label="退出登录"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h5M10 12h11m-4-4 4 4-4 4"/></svg></a></span><#elseif isLoggedIn!false><span class="studio-management-mobile"><a href="${logoutURL?html}" rel="nofollow" data-volo-reader-logout data-no-pjax title="退出读者（${(userName!'已登录')?html}）" aria-label="退出读者（${(userName!'已登录')?html}）"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h5M10 12h11m-4-4 4 4-4 4"/></svg></a></span><#elseif (allowRegister!false) || (commentAccessMode!'all') == 'registered'><span class="studio-management-mobile"><a href="${servePath}/user/login" rel="nofollow" data-volo-auth-trigger="login" title="读者登录" aria-label="读者登录"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 20c1.5-4 5-6 8-6s6.5 2 8 6"/></svg></a></span></#if>
  </nav>
  <div class="studio-header-actions"><details class="studio-header-search"><summary aria-label="搜索文章" title="搜索文章（Ctrl+K）"<#if enableInstantSearch!true> data-volo-search-trigger</#if>><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 4.5 4.5"/></svg></summary><form action="${servePath}/search" method="get" class="studio-search" role="search"><label class="studio-sr-only" for="studioHeaderSearch">搜索文章</label><input autocomplete="off" id="studioHeaderSearch" type="search" name="keyword" placeholder="搜索文章…" required/><button type="submit" aria-label="搜索"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 4.5 4.5"/></svg></button></form></details><#if !(isAdminLoggedIn!false) && (isLoggedIn!false)><a class="volo-header-reader-pill" href="${logoutURL?html}" rel="nofollow" data-volo-reader-logout data-no-pjax title="已登录读者：${(userName!'读者')?html}（点击退出）"><span>${(userName!'读者')?html}</span><small>退出</small></a><#elseif !(isLoggedIn!false) && ((allowRegister!false) || (commentAccessMode!'all') == 'registered')><a class="volo-header-reader-pill" href="${servePath}/user/login" rel="nofollow" data-volo-auth-trigger="login" title="读者登录 / 注册"><span>登录</span></a></#if><div data-bolo-theme-slot></div></div>
</div></header>
