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
<#macro head title description=''>
<meta charset="utf-8"/>
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=0"/>
<meta name="theme-color" content="#3b3e43">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes"/>
<meta name="apple-mobile-web-app-status-bar-style" content="black">
<meta name="format-detection" content="telephone=no"/>
<title>${title?html}</title>
<script data-bolo-navigation src="${staticServePath}/js/auth-navigation.js?${staticResourceVersion}"></script>
<meta name="bolo-skin" content="${(skinDirName!'')?html}"/>
<meta name="bolo-accent" content="${(appearanceAccent!'forest')?html}"/>
<script data-bolo-navigation src="${staticServePath}/js/appearance.js?${staticResourceVersion}"></script>
<script data-bolo-navigation src="${staticServePath}/js/toc-navigation.js?${staticResourceVersion}"></script>
<link rel="stylesheet" href="${servePath}/js/lib/vditor/dist/index.css?${staticResourceVersion}"/>
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
<meta name="copyright" content="B3log"/>
<meta http-equiv="Window-target" content="_top"/>
<meta property="og:locale" content="${langLabel?html}"/>
<meta property="og:title" content="${title?html}"/>
<meta property="og:site_name" content="${blogTitle?html}"/>
<meta property="og:url"
      content="${servePath}${request.requestURI?html}<#if request.queryString??>?${request.queryString?html}</#if>"/>
<meta property="og:image" content="${faviconURL?html}"/>
<link rel="search" type="application/opensearchdescription+xml" title="${title?html}" href="/opensearch.xml">
<link href="${servePath}/rss.xml" title="RSS" type="application/rss+xml" rel="alternate"/>
<link rel="manifest" href="${servePath}/manifest.json">
    <#if paginationCurrentPageNum??>
        <#if paginationCurrentPageNum == 1>
            <link rel="canonical" href="${servePath}${request.requestURI?html}">
        <#else>
            <link rel="canonical" href="${servePath}${request.requestURI?html}<#if request.queryString??>?${request.queryString?html}</#if>">
        </#if>
    <#else>
        <link rel="canonical" href="${servePath}${request.requestURI?html}">
    </#if>
    <#nested>
<link rel="stylesheet" href="${staticServePath}/scss/appearance.css?${staticResourceVersion}"/>
<#if htmlHead??>
    ${htmlHead}
</#if>
</#macro>
