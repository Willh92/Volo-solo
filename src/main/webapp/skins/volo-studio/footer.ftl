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

<#include "../../common-template/macro-user_site.ftl">
<#assign studioContacts><@userSite dir=""/></#assign>
<footer class="studio-footer">
  <#if studioContacts?trim?has_content><nav class="studio-contacts" aria-label="联系方式">${studioContacts}</nav></#if>
  <div class="studio-footer-meta"><span>© ${year} ${blogTitle?html}</span><span>Made with <a href="https://github.com/Willh92/Volo-solo" target="_blank" rel="noopener noreferrer">Volo</a> · Studio</span></div>
  <div class="studio-statistics" aria-label="站点统计"><span>${statistic.statisticPublishedBlogArticleCount} 篇文章</span><#if interactive == "true"><a href="${servePath}/dynamic.html">${statistic.statisticPublishedBlogCommentCount} 条评论</a></#if><span>${statistic.statisticBlogViewCount} 次浏览</span><span>${onlineVisitorCnt} 人在线</span></div>
  <#if footerContent?has_content><div class="studio-footer-content">${footerContent}</div></#if>
</footer>
<button id="studioBackToTop" class="studio-back-top" type="button" title="返回顶部" aria-label="返回顶部" hidden><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 14 6-6 6 6"/></svg></button>
<script src="${staticServePath}/js/lib/compress/pjax.min.js?${staticResourceVersion}"></script><script src="${staticServePath}/js/common${miniPostfix}.js?${staticResourceVersion}"></script><script src="${staticServePath}/skins/${skinDirName}/js/common${miniPostfix}.js?${staticResourceVersion}"></script><#include "../../common-template/label.ftl">${plugins}
