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
<footer class="footer">
    <div class="wrapper">
        <#include "../../common-template/macro-user_site.ftl">
        <@userSite dir=""></@userSite> <br>
        &copy; ${year}
        <a href="${servePath}">${blogTitle}</a>
        ${footerContent}
        <span class="footer__heart">❤️</span>
        Powered by <a href="https://github.com/Willh92/Volo-solo" target="_blank">Volo</a>
        <br>
        Theme ${skinDirName?remove_beginning('bolo-')}
        <sup>[<a href="https://www.cnblogs.com/jajian" target="_blank">ref</a>]</sup>
        by <a href="http://vanessa.b3log.org" target="_blank">Vanessa</a> <br>
    </div>
</footer>
<button class="side__top" id="sideTop" type="button" aria-label="${goTopLabel!'返回顶部'}" title="${goTopLabel!'返回顶部'}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 14 6-6 6 6"/></svg></button>


<script type="text/javascript" src="${staticServePath}/js/lib/compress/pjax.min.js" charset="utf-8"></script>
<script type="text/javascript" src="${staticServePath}/js/common${miniPostfix}.js?${staticResourceVersion}"
        charset="utf-8"></script>
<script type="text/javascript"
        src="${staticServePath}/skins/${skinDirName}/js/circleMagic${miniPostfix}.js?${staticResourceVersion}"
        charset="utf-8"></script>
<script type="text/javascript"
        src="${staticServePath}/skins/${skinDirName}/js/common${miniPostfix}.js?${staticResourceVersion}"
        charset="utf-8"></script>
<#include "../../common-template/label.ftl">
${plugins}
