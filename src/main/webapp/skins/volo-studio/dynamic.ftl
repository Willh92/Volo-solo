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
<!DOCTYPE html>
<html lang="zh-CN">
<head><@head title="${dynamicLabel} - ${blogTitle}"><link rel="stylesheet" href="${staticServePath}/skins/${skinDirName}/css/base.css?${staticResourceVersion}"/></@head></head>
<body>
<#include "header.ftl">
<main id="pjax" class="wrapper">
  <section class="article__item">
    <h1 class="article__title">${dynamicLabel}</h1>
    <#if interactive == "true" && recentComments?has_content>
      <ul class="studio-dynamic-list"><#list recentComments as comment>
        <li><header><strong>${comment.commentName?html}</strong><time datetime="${comment.commentDate2?string('yyyy-MM-dd')} ${comment.commentDate2?string('HH:mm')}">${comment.commentDate2?string('yyyy-MM-dd HH:mm')}</time><a href="${servePath}${comment.commentSharpURL?html}">查看评论 →</a></header><div class="vditor-reset">${comment.commentContent}</div></li>
      </#list></ul>
    <#else><p class="ft__gray">暂无评论动态。</p></#if>
  </section>
</main>
<#include "footer.ftl">
</body>
</html>
