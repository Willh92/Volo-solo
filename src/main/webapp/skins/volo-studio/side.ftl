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

<aside class="studio-side">
<#if (noticeBoard!"")?trim?has_content && !(studioNoticeInHero!false)><section class="studio-side-card studio-notice"><h3>公告</h3><div class="vditor-reset">${noticeBoard}</div></section></#if>
<section class="studio-side-card"><h3>链接与订阅</h3><div class="studio-side-links"><a href="${servePath}/links.html">友情链接 →</a><a href="${servePath}/follows.html">我的关注 →</a><a href="${servePath}/rss.xml">订阅 RSS ↗</a></div></section>
<#if mostUsedTags?size gt 0><section class="studio-side-card"><h3>常用标签</h3><div class="tags"><#list mostUsedTags as tag><a href="${servePath}/tags/${tag.tagTitle?url('UTF-8')}">${tag.tagTitle?html}</a></#list></div></section></#if>
<#if (mostViewCountArticles![])?has_content><section class="studio-side-card"><h3>${mostViewCountArticlesLabel}</h3><ul class="studio-side-articles"><#list mostViewCountArticles as article><li><a href="${servePath}${article.articlePermalink?html}" title="${article.articleTitle?html}">${article.articleTitle?html}</a></li></#list></ul></section></#if>
<#if interactive == "on" && (mostCommentArticles![])?has_content><section class="studio-side-card"><h3>${mostCommentArticlesLabel}</h3><ul class="studio-side-articles"><#list mostCommentArticles as article><li><a href="${servePath}${article.articlePermalink?html}" title="${article.articleTitle?html}">${article.articleTitle?html}</a></li></#list></ul></section></#if>
</aside>
