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
<#include "../../common-template/macro-common_head.ftl">
<#include "macro-comments.ftl">
<#include "../../common-template/macro-comment_script.ftl">
<!DOCTYPE html>
<html>
<head>
    <@head title="${article.articleTitle} - ${blogTitle}" description="${article.articleAbstract?html}">
        <link rel="stylesheet"
              href="${staticServePath}/skins/${skinDirName}/css/base.css?${staticResourceVersion}"/>
        <#if previousArticlePermalink??>
            <link rel="prev" title="${(previousArticleTitle)?html}" href="${servePath}${previousArticlePermalink}">
        </#if>
        <#if nextArticlePermalink??>
            <link rel="next" title="${(nextArticleTitle)?html}" href="${servePath}${nextArticlePermalink}">
        </#if>
    </@head>
</head>
<body class="studio-page studio-article-page">
<#include "header.ftl">
<div id="pjax" class="wrapper studio-article-layout">
    <#if pjax><!---- pjax {#pjax} start ----></#if>
    <#include "../../common-template/rail-toc.ftl">
    <div class="studio-article-main">
    <div class="article__item">
        <h2 class="article__title">
            <a rel="bookmark" href="${servePath}${article.articlePermalink}">
            ${(article.articleTitle)?html}
            </a>
            <#if article.articlePutTop>
            <sup>
                ${topArticleLabel}
            </sup>
            </#if>
        </h2>

        <div class="ft__gray studio-article-meta">
            <time>
            ${article.articleCreateDate?string("yyyy-MM-dd")}<#if article.articleCreateDate?string("yyyy-MM-dd") != article.articleUpdateDate?string("yyyy-MM-dd")> / ${article.articleUpdateDate?string("yyyy-MM-dd")}</#if>
            </time>
            <span class="studio-article-tags">
            <#list article.articleTags?split(",") as articleTag>
                <a rel="tag" href="${servePath}/tags/${articleTag?url('UTF-8')}" class="ft__red">
                    ${articleTag?html}</a><#if articleTag_has_next>, </#if>
            </#list>
            </span>
            <div class="studio-article-counts">
                <#if interactive == "on">
                <a class="ft__red" href="${servePath}${article.articlePermalink}#comments"><#if article.articleCommentCount gt 0>${article.articleCommentCount} </#if>${commentLabel}</a>
                •
                </#if>
                ${article.articleViewCount} ${viewLabel}
            </div>
        </div>

        <div class="vditor-reset article__content">
            ${article.articleContent}
            <#if "" != article.articleSign.signHTML?trim>
            <div>
                ${article.articleSign.signHTML}
            </div>
            </#if>
        </div>
        <script>
            var loggedIn = ${article.logged};
        </script>

        <#include "article-adjacent.ftl"/>
    </div>

    <#if interactive == "on">
    <@comments commentList=articleComments article=article></@comments>
    </#if>
    <#if 0 != relevantArticlesDisplayCount>
        <section id="relevantArticles" class="studio-reading-links"></section>
    </#if>
    <#if 0 != randomArticlesDisplayCount>
        <section id="randomArticles" class="studio-reading-links"></section>
    </#if>
    </div>
    <#if pjax><!---- pjax {#pjax} end ----></#if>
</div>
<#include "footer.ftl">
<#if pjax><!---- pjax {#pjax} start ----></#if>
<@comment_script oId=article.oId commentable=article.commentable>
<#if 0 != randomArticlesDisplayCount>
    page.loadRandomArticles();
</#if>
<#if 0 != relevantArticlesDisplayCount>
    page.loadRelevantArticles('${article.oId}', '<h4>${relevantArticles1Label}</h4>');
</#if>
</@comment_script>
<#if pjax><!---- pjax {#pjax} end ----></#if>
</body>
</html>
