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
<main class="article-list fn__clear">
    <#list articles as article>
        <#assign isRss=(article.isRss!false)?c=="true">
            <article class="item">
                <div class="item__container">
                    <div class="item__cover" style="background-image: url('${(article.articleImg1URL!'')?replace('\\', '')?replace("'", '%27')?replace('(', '%28')?replace(')', '%29')?html}')"></div>
                    <div rel="bookmark" class="item__abstract">
                        <a pjax-title="${(article.articleTitle)?html}" href="${(isRss?then((article.articlePermalink?starts_with('https://') || article.articlePermalink?starts_with('http://'))?then(article.articlePermalink, '#'), servePath + article.articlePermalink))?html}">
                            ${article.articleAbstractText}
                        </a>
                    </div>
                    <div class="item__slant"></div>
                    <div class="item__slant item__slant--white"></div>
                    <div class="item__main">
                        <span class="item__sup">
                            <#if article.articlePutTop>
                                <sup class="ft__red">
                                    ${topArticleLabel}
                                </sup>
                            </#if>
                            <#if article.hasUpdated>
                                <sup class="ft__red">
                                    ${updatedLabel}
                                </sup>
                            </#if>
                        </span>
                        <h2 class="item__title">
                            <a class="skin-list-title" rel="bookmark" href="${(isRss?then((article.articlePermalink?starts_with('https://') || article.articlePermalink?starts_with('http://'))?then(article.articlePermalink, '#'), servePath + article.articlePermalink))?html}" title="${(article.articleTitle)?html}">
                                ${(article.articleTitle)?html}
                            </a>
                        </h2>
                        <#assign cardCategory=(article.articleCategory!'')?trim>
                        <#assign cardTag=(article.articleTags!'')?split(',')?first?trim>
                        <#if cardCategory?has_content>
                            <a class="item__badge" href="${servePath}/category/${article.categoryURI?html}" title="${cardCategory?html}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7V5h6l2 2h10v12H3Z"/></svg><span>${cardCategory?html}</span></a>
                        <#elseif cardTag?has_content>
                            <a class="item__badge" href="${servePath}/tags/${cardTag?url('UTF-8')}" title="${cardTag?html}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 3h8l10 10-8 8L3 11Z"/><circle cx="7.5" cy="7.5" r="1"/></svg><span>${cardTag?html}</span></a>
                        </#if>
                    </div>
                </div>
            </article>
    </#list>
</main>
<#if 0 !=paginationPageCount>
    <nav class="pagination">
        <#if 1 !=paginationPageNums?first>
            <a href="${servePath}${path}?p=${paginationPreviousPageNum}" class="pagination__item">←</a>
            <a class="pagination__item" href="${servePath}${path}">1</a>
            <span class="pagination__item pagination__item--text">...</span>
        </#if>
        <#list paginationPageNums as paginationPageNum>
            <#if paginationPageNum==paginationCurrentPageNum>
                <span class="pagination__item pagination__item--current">
                    ${paginationPageNum}
                </span>
                <#else>
                    <a class="pagination__item"
                        href="${servePath}${path}?p=${paginationPageNum}">
                        ${paginationPageNum}
                    </a>
            </#if>
        </#list>
        <#if paginationPageNums?last !=paginationPageCount>
            <span class="pagination__item pagination__item--text">...</span>
            <a href="${servePath}${path}?p=${paginationPageCount}"
                class="pagination__item">
                ${paginationPageCount}
            </a>
            <a href="${servePath}${path}?p=${paginationNextPageNum}" class="pagination__item">→</a>
        </#if>
    </nav>
</#if>
