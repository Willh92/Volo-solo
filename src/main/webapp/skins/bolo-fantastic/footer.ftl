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
<footer class="footer fantastic-footer">
    <div class="container">
        <div class="fantastic-footer-main">
            <div class="fantastic-footer-credit">
                <p>&copy; ${.now?string('yyyy')} ${(blogTitle!'')?html}</p>
                <p class="fantastic-footer-source">
                    <span>Powered by <a href="https://github.com/Willh92/Volo-solo" target="_blank" rel="noopener noreferrer">Volo</a></span>
                    <span>Theme <a href="https://github.com/csfwff/bolo-fantastic">Fantastic</a> by <a href="https://github.com/csfwff" target="_blank" rel="noopener noreferrer">唐墨夏</a></span>
                </p>
            </div>
            <nav class="fantastic-footer-links" aria-label="页脚导航">
                <#if interactive=="on"><a href="${servePath}/search">搜索</a></#if>
                <a href="${servePath}/tags.html">${allTagsLabel}</a>
                <a href="${servePath}/archives.html">${archiveLabel}</a>
                <a href="${servePath}/links.html">${linkLabel}</a>
                <a href="${servePath}/follows.html">${followLabel}</a>
            </nav>
        </div>
        <#if footerContent?has_content><div class="fantastic-footer-custom">${footerContent}</div></#if>
    </div>
</footer>
<script type="text/javascript" src="${staticServePath}/js/lib/jquery/jquery.min.js" charset="utf-8"></script>
<script src="${staticServePath}/js/lib/compress/pjax.min.js"></script>
<script src="${staticServePath}/js/common${miniPostfix}.js?${staticResourceVersion}"></script>
<script src="${staticServePath}/skins/${skinDirName}/js/common${miniPostfix}.js?${staticResourceVersion}"></script>
<#include "../../common-template/label.ftl">
    ${plugins}
