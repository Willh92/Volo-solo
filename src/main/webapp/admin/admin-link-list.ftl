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
<div>
    <form class="form console-table-toolbar console-list-filter">
        <div class="console-table-toolbar__group console-table-toolbar__group--grow">
            <input id="linkListInput" type="search"/>
            <button type="submit" class="search-btn">${searchLabel!'搜索'}</button>
        </div>
        <div class="console-table-toolbar__group">
            <button id="checkLinksHealthBtn" type="button" class="console-btn-secondary" title="并发检测全部友链 HTTP 连通性与响应耗时（自动防御内网/本机 SSRF）" onclick="admin.linkList.checkHealth(this);">巡检友链存活</button>
        </div>
    </form>
    <div id="linkTable"></div>
    <div id="linkPagination" class="fn__margin12 fn__right"></div>
</div>
<div class="fn__clear"></div>
<div class="form form__no-table">
    <h3>${addLinkLabel!'添加链接'}</h3>
    <label for="linkTitle">${linkTitle1Label}</label>
    <input id="linkTitle" type="text" autocomplete="off"/>
    <label for="linkAddress">${url1Label}</label>
    <input id="linkAddress" type="text" autocomplete="off"/>
    <label for="linkDescription">${linkDescription1Label}</label>
    <input id="linkDescription" type="text" autocomplete="off"/>
    <label for="linkIcon">${linkIcon1Label}</label>
    <input id="linkIcon" type="text" autocomplete="off"/>
    <div class="console-form-actions"><button type="button" onclick="admin.linkList.add();">${saveLabel}</button></div>
    <div class="fn__clear"></div>
</div>
<div id="updateLink" class="fn__none form form__no-table" data-title="${updateLinkLabel}">
    <label for="linkTitleUpdate">${linkTitle1Label}</label>
    <input id="linkTitleUpdate" type="text" autocomplete="off"/>
    <label for="linkAddressUpdate">${url1Label}</label>
    <input id="linkAddressUpdate" type="text" autocomplete="off"/>
    <label for="linkDescriptionUpdate">${linkDescription1Label}</label>
    <input id="linkDescriptionUpdate" type="text" autocomplete="off"/>
    <label for="linkIconUpdate">${linkIcon1Label}</label>
    <input id="linkIconUpdate" type="text" autocomplete="off"/>
    <div class="console-form-actions"><button type="button" onclick="admin.linkList.update();">${updateLabel}</button></div>
    <div class="fn__clear"></div>
</div>
${plugins}
