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
<footer class="footer fn-clear">
    &copy; ${year}
    <a href="${servePath}">${blogTitle}</a>
    ${footerContent}
    <br/>
    Powered by <a href="https://github.com/Willh92/Volo-solo" target="_blank">Volo</a>
    <span class="ft-warn">&heartsuit;</span>
    Theme ${skinDirName?remove_beginning('bolo-')}
    <sup>[<a href="https://github.com/9IPHP/9IPHP" target="_blank">ref</a>]</sup>
    by <a href="http://vanessa.b3log.org" target="_blank">Vanessa</a>
</footer>
<button class="icon-up" type="button" onclick="Util.goTop()" aria-label="${goTopLabel?html}" title="${goTopLabel?html}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 14 6-6 6 6"/></svg></button>
<script src="${staticServePath}/js/lib/compress/pjax.min.js?${staticResourceVersion}"></script>
<script src="${staticServePath}/js/common${miniPostfix}.js?${staticResourceVersion}"></script>
<script src="${staticServePath}/skins/${skinDirName}/js/common${miniPostfix}.js?${staticResourceVersion}"></script>
<#include "../../common-template/label.ftl">
${plugins}
