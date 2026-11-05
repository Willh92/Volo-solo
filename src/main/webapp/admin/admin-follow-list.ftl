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
    <div id="followTable"></div>
    <div id="followPagination" class="fn__margin12 fn__right"></div>
</div>
<div class="fn__clear"></div>
<div class="form form__no-table">
    <h3>${addFollowLabel!'添加关注'}</h3>
    <label for="followTitle">${linkTitle1Label}</label>
    <input id="followTitle" type="text" autocomplete="off"/>
    <label for="followAddress">${url1Label}</label>
    <input id="followAddress" type="text" autocomplete="off"/>
    <label for="followDescription">${linkDescription1Label}</label>
    <input id="followDescription" type="text" autocomplete="off"/>
    <label for="followIcon">${linkIcon1Label}</label>
    <input id="followIcon" type="text" autocomplete="off"/>
    <div class="console-form-actions"><button type="button" onclick="admin.followList.add();">${saveLabel}</button></div>
    <div class="fn__clear"></div>
</div>
<div id="updateFollow" class="fn__none form form__no-table" data-title="${updateFollowLabel}">
    <label for="followTitleUpdate">${linkTitle1Label}</label>
    <input id="followTitleUpdate" type="text" autocomplete="off"/>
    <label for="followAddressUpdate">${url1Label}</label>
    <input id="followAddressUpdate" type="text" autocomplete="off"/>
    <label for="followDescriptionUpdate">${linkDescription1Label}</label>
    <input id="followDescriptionUpdate" type="text" autocomplete="off"/>
    <label for="followIconUpdate">${linkIcon1Label}</label>
    <input id="followIconUpdate" type="text" autocomplete="off"/>
    <div class="console-form-actions"><button type="button" onclick="admin.followList.update();">${updateLabel}</button></div>
    <div class="fn__clear"></div>
</div>
${plugins}
