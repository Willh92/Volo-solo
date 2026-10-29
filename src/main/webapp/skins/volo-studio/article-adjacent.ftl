<#if previousArticlePermalink?? || nextArticlePermalink??>
<nav class="article__near" aria-label="相邻文章">
    <#if previousArticlePermalink??>
    <a href="${servePath}${previousArticlePermalink?html}" rel="prev" title="${previousArticleTitle?html}">
        <strong>上一篇 · 较早文章</strong>
        <span>${previousArticleTitle?html}</span>
    </a>
    </#if>
    <#if nextArticlePermalink??>
    <a href="${servePath}${nextArticlePermalink?html}" rel="next" title="${nextArticleTitle?html}">
        <strong>下一篇 · 较新文章</strong>
        <span>${nextArticleTitle?html}</span>
    </a>
    </#if>
</nav>
</#if>
