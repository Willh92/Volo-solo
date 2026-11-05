<#-- Volo utility pages. Derived from Bolo, Copyright (c) 2020-present, bolo-blog. AGPL-3.0-or-later. -->
<#include "macro-common_head.ftl"/>
<#macro utilityPage title type="search">
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <@head title="${title} - ${blogTitle!'Volo'}">
        <meta name="robots" content="noindex,follow"/>
    </@head>
</head>
<body class="utility-page utility-page--${type}">
<header class="utility-topbar">
    <a class="utility-brand" href="${servePath}/" aria-label="${(blogTitle!'Volo')?html}，首页">
        <span class="utility-brand-mark" aria-hidden="true">V.</span>
        <span class="utility-brand-title">${(blogTitle!'Volo')?html}</span>
    </a>
    <div class="utility-header-tools">
        <a class="utility-home" href="${servePath}/">返回首页 <span aria-hidden="true">↗</span></a>
        <div class="skin-theme-slot utility-theme-slot" data-bolo-theme-slot></div>
    </div>
</header>
<main class="utility-main">
    <#nested>
</main>
<footer class="utility-footer">Powered by <a href="https://github.com/Willh92/Volo-solo" target="_blank" rel="noopener">Volo</a> ${(boloVersion!'3.0.0')?html}</footer>
</body>
</html>
</#macro>

<#macro errorPage code title description hint retry=false redirectSeconds=0>
<@utilityPage title="${code} ${title}" type="error">
<section class="utility-error-card" aria-labelledby="errorTitle">
    <div class="utility-error-art" aria-hidden="true">
        <span class="utility-error-code">${code}</span>
        <svg viewBox="0 0 180 60"><path d="M4 30h48l18-18h40l18 18h48M4 30h48l18 18h40l18-18h48"/><circle cx="90" cy="30" r="5"/></svg>
    </div>
    <div class="utility-error-copy">
        <p class="utility-eyebrow">HTTP ${code}</p>
        <h1 id="errorTitle">${title}</h1>
        <p class="utility-error-description">${description}</p>
        <p class="utility-error-hint">${hint}</p>
        <div class="utility-actions">
            <a class="utility-button" href="${servePath}/">回到首页 <span aria-hidden="true">↗</span></a>
            <#if retry><button type="button" class="utility-button utility-button--secondary" onclick="location.reload()">重新尝试</button>
            <#elseif code == "404" || code == "410"><a class="utility-button utility-button--secondary" href="${servePath}/search"<#if enableInstantSearch!true> data-volo-search-trigger</#if>>搜索文章</a></#if>
        </div>
        <#if redirectSeconds gt 0><p class="utility-return" data-home-countdown="${redirectSeconds}" data-home-url="${servePath}/">将在 <span data-countdown-value>${redirectSeconds}</span> 秒后返回首页。<button type="button" data-countdown-cancel>留在此页</button></p></#if>
    </div>
</section>
<#if redirectSeconds gt 0><script src="${staticServePath}/js/error-page.js?${staticResourceVersion}"></script></#if>
</@utilityPage>
</#macro>
