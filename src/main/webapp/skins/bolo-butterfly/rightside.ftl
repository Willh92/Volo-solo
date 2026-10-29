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
<#--<div class="rightside rin" id="rightside" style="opacity: 1; transform: translateX(-38px);">-->
<#--    <div id="rightside-config-hide" class="rightside-out">-->
<#--&lt;#&ndash;        <button id="readmode" type="button" title="閱讀模式"><i class="fas fa-book-open"></i></button>&ndash;&gt;-->
<#--        <button id="font_plus" type="button" title="放大字型"><i class="fas fa-plus"></i></button>-->
<#--        <button id="font_minus" type="button" title="縮小字型"><i class="fas fa-minus"></i></button>-->
<#--&lt;#&ndash;        <button class="translate_chn_to_cht" id="translateLink" type="button" title="簡繁轉換"> 簡</button>&ndash;&gt;-->
<#--        <button id="darkmode" type="button" title="淺色和深色模式轉換(暂未开发)"><i class="fas fa-adjust"></i></button>-->
<#--    </div>-->
<#--    <div id="rightside-config-show">-->
<#--        <button id="rightside_config" type="button" title="設定"><i class="fas fa-cog"></i></button>-->
<#--&lt;#&ndash;        TODO     修改样式 &ndash;&gt;-->
<#--        <button id="to_comment" type="button" title="直達評論"><i class="scroll_to_comment fas fa-comments"></i></button>-->
<#--&lt;#&ndash;        <button class="close" id="mobile-toc-button" type="button" title="目錄"><i class="fas fa-list-ul"></i></button>&ndash;&gt;-->
<#--        <button id="go-up" type="button" title="回到頂部"><i class="fas fa-arrow-up"></i></button>-->
<#--    </div>-->
<#--</div>-->
<section class="rightside" id="rightside" aria-hidden="true" inert>
    <div id="rightside-config-hide" aria-hidden="true" inert>

        <button id="readmode" title="阅读模式"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 7c-3-3-7-3-10-2v14c3-1 7-1 10 2m0-14c3-3 7-3 10-2v14c-3-1-7-1-10 2V7Z"/></svg></button>
        <button id="font_plus" title="放大字体"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg></button>
        <button id="font_minus" title="缩小字体"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14"/></svg></button>

        <button id="darkmode" title="浅色和深色模式转换"><i class="fas fa-adjust"></i></button>
    </div>
    <div id="rightside-config-show">
        <button id="rightside_config" title="设置" aria-label="展开阅读设置" aria-controls="rightside-config-hide" aria-expanded="false"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 3-.7 2.3-2 .9-2.2-.5L2 9l1.6 1.8v2.4L2 15l2.1 3.3 2.2-.5 2 .9L9 21h6l.7-2.3 2-.9 2.2.5L22 15l-1.6-1.8v-2.4L22 9l-2.1-3.3-2.2.5-2-.9L15 3Z"/><circle cx="12" cy="12" r="3"/></svg></button>

        <#if article?? && article.articleToC?? && article.articleToC?size gt 0>
        <button class="close" id="mobile-toc-button" title="${tocLabel?html}"><i class="fas fa-list-ul"></i></button>
        </#if>
        <button id="go-up" title="回到顶部"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6"/></svg></button>
    </div>
</section>

