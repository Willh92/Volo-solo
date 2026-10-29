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

<#macro studioCustomNavigation>
  <#list pageNavigations as page><a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}" rel="noopener noreferrer" title="${page.pageTitle?html}"><#if page.pageIcon?has_content><img class="studio-nav-icon" src="${page.pageIcon?html}" alt=""/></#if><span>${page.pageTitle?html}</span></a></#list>
</#macro>
<a class="studio-skip" href="#pjax">跳到正文</a>
<header class="studio-header"><div class="studio-header-inner">
  <#assign studioAvatar=((adminUser.userAvatar)!'')?trim>
  <a class="studio-brand" href="${servePath}/"><span class="studio-brand-avatar"><#if studioAvatar?has_content && !studioAvatar?ends_with('/images/volo-mark.svg')><img src="${studioAvatar?html}" alt="" decoding="async" onerror="this.hidden=true;this.nextElementSibling.hidden=false"/><span class="studio-mark" aria-hidden="true" hidden>V.</span><#else><span class="studio-mark" aria-hidden="true">V.</span></#if></span><span>${blogTitle?html}<small>记录 · 思考 · 分享</small></span></a>
  <nav aria-label="主导航"><a href="${servePath}/">首页</a><a href="${servePath}/archives.html">归档</a><a href="${servePath}/tags.html">标签</a><a href="${servePath}/categories.html">分类</a>
    <#if pageNavigations?has_content>
      <div class="studio-custom-links"><@studioCustomNavigation/></div>
      <details class="studio-nav-more"><summary>更多<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5"/></svg></summary><div class="studio-nav-panel"><@studioCustomNavigation/></div></details>
    </#if>
  </nav>
  <div class="studio-header-actions"><#if interactive == "on"><details class="studio-header-search"><summary aria-label="搜索文章" title="搜索文章"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 4.5 4.5"/></svg></summary><form action="${servePath}/search" method="get" class="studio-search" role="search"><label class="studio-sr-only" for="studioHeaderSearch">搜索文章</label><input id="studioHeaderSearch" type="search" name="keyword" placeholder="搜索文章…" required/><button type="submit" aria-label="搜索"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 4.5 4.5"/></svg></button></form></details></#if><div data-bolo-theme-slot></div></div>
</div></header>
