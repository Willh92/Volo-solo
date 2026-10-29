<#if article?? && article.articleToC?? && article.articleToC?size gt 0>
<aside class="reading-toc-rail" aria-label="${tocLabel?html}">
    <details class="reading-toc-panel" data-reading-toc>
        <summary aria-label="${tocLabel?html}" title="${tocLabel?html}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h12M8 12h12M8 18h12M3 6h.01M3 12h.01M3 18h.01"/></svg><span>${tocLabel}</span><svg class="reading-toc-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4"/></svg></summary>
        <#include "toc.ftl">
    </details>
</aside>
</#if>
