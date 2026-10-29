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
<#include "macro-utility_page.ftl"/>
<@utilityPage title="搜索文章" type="search">
<section class="search-heading" aria-labelledby="searchTitle">
    <h1 id="searchTitle">搜索文章</h1>
    <p>从记录中，找到想读的那一篇。</p>
</section>
<form class="search-form" action="${servePath}/search" method="get" role="search" target="_self">
    <label class="search-label" for="searchKeyword">搜索关键词</label>
    <input id="searchKeyword" name="keyword" type="search" value="${keyword?html}" placeholder="输入标题、标签或正文关键词" maxlength="200"/>
    <button class="utility-button" type="submit">搜索</button>
</form>
<#if keyword?has_content && articles?size gt 0>
<p class="search-summary">“${keyword?html}”的搜索结果 · 本页 ${articles?size} 篇</p>
<section class="search-results" aria-label="搜索结果">
    <#list articles as article>
    <article class="search-result">
        <h2><a href="${servePath}${article.articlePermalink?html}">${article.articleTitle?html}</a></h2>
        <#if (article.articleAbstractText!"")?has_content><p>${article.articleAbstractText?html}</p></#if>
        <div class="search-result-meta">
            <time datetime="${article.articleCreateDate?string('yyyy-MM-dd')}">${article.articleCreateDate?string('yyyy-MM-dd')}</time>
            <span class="search-result-tags"><#list article.articleTags?split(',') as tag><#if tag?has_content><a href="${servePath}/tags/${tag?url('UTF-8')}"># ${tag?html}</a></#if></#list></span>
            <span>${article.articleCommentCount} ${commentLabel}</span><span>${article.articleViewCount} ${viewLabel}</span>
        </div>
    </article>
    </#list>
</section>
<#if pagination.paginationPageCount gt 1>
<nav class="search-pagination" aria-label="搜索结果分页">
    <#if pagination.paginationCurrentPageNum gt 1><a href="${servePath}/search?keyword=${keyword?url('UTF-8')}&amp;p=${pagination.paginationCurrentPageNum - 1}" aria-label="上一页">←</a></#if>
    <#list pagination.paginationPageNums as pageNumber><#if pageNumber == pagination.paginationCurrentPageNum><span aria-current="page">${pageNumber}</span><#else><a href="${servePath}/search?keyword=${keyword?url('UTF-8')}&amp;p=${pageNumber}">${pageNumber}</a></#if></#list>
    <#if pagination.paginationCurrentPageNum lt pagination.paginationPageCount><a href="${servePath}/search?keyword=${keyword?url('UTF-8')}&amp;p=${pagination.paginationCurrentPageNum + 1}" aria-label="下一页">→</a></#if>
</nav>
</#if>
<#else>
<section class="search-empty" aria-live="polite">
    <#if keyword?has_content><h2>暂时没有找到相关文章</h2><p>换一个关键词，或试试更简短的描述。</p>
    <#else><h2>想找点什么？</h2><p>输入关键词后，按回车或点击搜索。</p></#if>
</section>
</#if>
</@utilityPage>
