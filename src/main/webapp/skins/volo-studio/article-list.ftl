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

<div class="studio-posts"><#list articles as article><#assign isRss=(article.isRss!false)?c=="true"><#assign articleURL=isRss?then(article.articlePermalink, servePath + article.articlePermalink)>
<article class="article__item"><#assign coverURL=article.articleCoverURL!""><a class="studio-post-cover<#if !coverURL?has_content> studio-post-cover--empty</#if>" href="${articleURL?html}" tabindex="-1" aria-hidden="true"><#if coverURL?has_content><img src="${coverURL?html}" alt="" loading="lazy" decoding="async"/></#if><#include "cover-art.ftl"></a><div class="studio-post-meta"><time datetime="${article.articleCreateDate?string('yyyy-MM-dd')}">${article.articleCreateDate?string('yyyy.MM.dd')}</time><#if article.articlePutTop><span class="studio-badge">置顶</span></#if></div>
<h2 class="article__title"><a class="skin-list-title" rel="bookmark" href="${articleURL?html}" title="${(article.articleTitle)?html}">${article.articleTitle?html}</a></h2><div class="vditor-reset article__content studio-abstract">${article.articleAbstract}</div>
<div class="studio-post-foot"><span><#list article.articleTags?split(',') as tag><#if tag_index lt 2><a href="${servePath}/tags/${tag?url('UTF-8')}"># ${tag?html}</a></#if></#list></span><a class="article__more" href="${articleURL?html}" aria-label="阅读 ${article.articleTitle?html}">阅读 <span aria-hidden="true">↗</span></a></div></article>
<#else><div class="studio-empty"><h2>故事即将开始</h2><p>下一篇记录，值得期待。</p></div></#list></div>
<#if paginationPageCount gt 1><nav class="pagination" aria-label="文章分页"><#list paginationPageNums as number><#if number == paginationCurrentPageNum><span class="pagination__item pagination__item--current" aria-current="page">${number}</span><#else><a class="pagination__item" href="${servePath}${path}?p=${number}">${number}</a></#if></#list></nav></#if>
