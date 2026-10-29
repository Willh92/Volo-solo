<#if article?? && article.articleToC?? && article.articleToC?size gt 0>
<details class="reading-toc-inline" data-reading-toc>
    <summary><span>${tocLabel?html}</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4"/></svg></summary>
    <#include "toc.ftl">
</details>
</#if>
