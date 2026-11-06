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
<#--

    Bolo - A stable and beautiful blogging system based in Solo.
    Copyright (c) 2020, https://github.com/adlered

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
<#--

    Solo - A small and beautiful blogging system written in Java.
    Copyright (c) 2010-present, b3log.org

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
<#macro head title description='' isErrorPage=false>
<meta charset="utf-8"/>
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"/>
<meta name="theme-color" content="#3b3e43">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes"/>
<meta name="apple-mobile-web-app-status-bar-style" content="black">
<meta name="format-detection" content="telephone=no"/>
<title>${title?html}</title>
<script data-bolo-navigation src="${staticServePath}/js/auth-navigation${miniPostfix!''}.js?${staticResourceVersion}"></script>
<meta name="bolo-skin" content="${(skinDirName!'')?html}"/>
<meta name="bolo-accent" content="${(appearanceAccent!'forest')?html}"/>
<script data-bolo-navigation src="${staticServePath}/js/appearance${miniPostfix!''}.js?${staticResourceVersion}"></script>
<script data-bolo-navigation src="${staticServePath}/js/lib/jquery/jquery.min.js?${staticResourceVersion}"></script>
<script data-bolo-navigation defer src="${staticServePath}/js/toc-navigation${miniPostfix!''}.js?${staticResourceVersion}"></script>
<link rel="stylesheet" href="${staticServePath}/js/lib/vditor/dist/index.css?${staticResourceVersion}"/>
    <#if description != ''>
<meta name="description" content="${description?html}"/>
<meta property="og:description" content="${description?html}"/>
    <#elseif metaDescription??>
<meta name="description" content="${metaDescription?html}"/>
<meta property="og:description" content="${metaDescription?html}"/>
    <#else>
    <#if !blogSubtitle??>
        <#assign blogSubtitle = ''>
    </#if>
<meta name="description" content="${blogTitle?html}。${blogSubtitle?html}"/>
<meta property="og:description" content="${blogTitle?html}。${blogSubtitle?html}"/>
    </#if>
    <#if metaKeywords??>
    <meta name="keywords" content="${metaKeywords?html}"/>
    </#if>
<link rel="dns-prefetch" href="${staticServePath}"/>
<link rel="preconnect" href="${staticServePath}">
<link rel="icon" href="${faviconURL?html}"/>
<link rel="apple-touch-icon" href="${faviconURL?html}">
<link rel="shortcut icon" href="${faviconURL?html}">
<#assign voloResolvedDesc = (description != '')?then(description, (metaDescription?? && metaDescription != '')?then(metaDescription, blogTitle + '。' + (blogSubtitle!'')))/>
<#assign voloResolvedImage = (voloOgImage!'')/>
<#if voloResolvedImage == '' && article?? && (article.articleImg1URL!'') != ''>
    <#assign voloResolvedImage = article.articleImg1URL/>
</#if>
<#if voloResolvedImage == ''>
    <#assign voloResolvedImage = faviconURL!''/>
</#if>
<meta name="copyright" content="Volo"/>
<meta http-equiv="Window-target" content="_top"/>
<meta property="og:type" content="<#if article??>article<#else>website</#if>"/>
<meta property="og:locale" content="${langLabel?html}"/>
<meta property="og:title" content="${title?html}"/>
<meta property="og:site_name" content="${blogTitle?html}"/>
<#if isErrorPage>
<meta property="og:url" content="${servePath}/"/>
<#else>
<meta property="og:url"
      content="${servePath}${request.requestURI?html}<#if request.queryString??>?${request.queryString?html}</#if>"/>
</#if>
<meta property="og:image" content="${voloResolvedImage?html}"/>
<meta name="twitter:card" content="<#if article?? && (voloOgImage!'') != ''>summary_large_image<#else>summary</#if>"/>
<meta name="twitter:title" content="${title?html}"/>
<meta name="twitter:description" content="${voloResolvedDesc?html}"/>
<meta name="twitter:image" content="${voloResolvedImage?html}"/>
<#if article?? && (article.articleTitle??)>
<script type="application/ld+json">
{"@context":"https://schema.org","@type":"BlogPosting","headline":"${article.articleTitle?js_string}","description":"${voloResolvedDesc?js_string}","image":"${voloResolvedImage?js_string}","url":"${(servePath + (article.articlePermalink!''))?js_string}","author":{"@type":"Person","name":"${(article.authorName!blogTitle)?js_string}"}<#if article.articleCreateDate??>,"datePublished":"${article.articleCreateDate?string("yyyy-MM-dd'T'HH:mm:ssZ")?js_string}"</#if><#if article.articleUpdateDate??>,"dateModified":"${article.articleUpdateDate?string("yyyy-MM-dd'T'HH:mm:ssZ")?js_string}"</#if>}
</script>
</#if>
<link rel="search" type="application/opensearchdescription+xml" title="${title?html}" href="/opensearch.xml">
<link href="${servePath}/rss.xml" title="RSS" type="application/rss+xml" rel="alternate"/>
<link rel="manifest" href="${servePath}/manifest.json">
    <#if !isErrorPage>
        <#if paginationCurrentPageNum??>
        <#if paginationCurrentPageNum == 1>
            <link rel="canonical" href="${servePath}${request.requestURI?html}">
        <#else>
            <link rel="canonical" href="${servePath}${request.requestURI?html}<#if request.queryString??>?${request.queryString?html}</#if>">
        </#if>
    <#else>
        <link rel="canonical" href="${servePath}${request.requestURI?html}">
    </#if>
    </#if>
    <#nested>
<link rel="stylesheet" href="${staticServePath}/scss/appearance.css?${staticResourceVersion}"/>
<script>
window.VoloSiteConfig = {
    servePath: "${(servePath!'')?js_string}",
    allowRegister: ${(allowRegister!false)?c},
    commentAccessMode: "${(commentAccessMode!'all')?js_string}",
    enableInstantSearch: ${(enableInstantSearch!true)?c},
    isLoggedIn: ${(isLoggedIn!false)?c},
    isAdminLoggedIn: ${(isAdminLoggedIn!false)?c},
    userName: "${((isLoggedIn!false)?then(userName!'', ''))?js_string}"
};
</script>
<script data-bolo-navigation defer src="${staticServePath}/js/blog-enhancements${miniPostfix!''}.js?${staticResourceVersion}"></script>
<#if htmlHead??>
    ${htmlHead}
</#if>
</#macro>
