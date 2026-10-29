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
<!DOCTYPE html><html lang="zh-CN"><head><@head title="${blogTitle}"><link rel="stylesheet" href="${staticServePath}/skins/${skinDirName}/css/base.css?${staticResourceVersion}"/></@head></head>
<body class="studio-page"><#include "header.ftl">
<#assign studioNoticeInHero=true>
<h1 class="studio-sr-only">${blogTitle?html}</h1>
<#if (noticeBoard!'')?trim?has_content><section class="studio-hero" aria-label="公告"><div class="studio-hero-copy"><h2 class="studio-hero-label">公告</h2><div class="vditor-reset studio-hero-notice">${noticeBoard}</div></div><svg class="studio-hero-graphic" viewBox="0 0 168 104" aria-hidden="true"><circle class="studio-hero-orbit" cx="91" cy="51" r="40"/><path class="studio-notice-sound" d="M124 38a25 25 0 0 1 0 28m11-36a38 38 0 0 1 0 44"/><path class="studio-notice-horn" d="M55 39h19l34-17v60L74 65H55a9 9 0 0 1-9-9v-8a9 9 0 0 1 9-9Z"/><path class="studio-notice-detail" d="M74 39v26m-17 1 5 21h15l-6-21M36 43v19"/></svg></section></#if>
<main id="pjax" class="wrapper studio-layout"><div class="studio-feed"><div class="studio-section-heading"><h2>最近更新</h2></div><#include "article-list.ftl"></div><#include "side.ftl"></main>
<#include "footer.ftl"></body></html>
