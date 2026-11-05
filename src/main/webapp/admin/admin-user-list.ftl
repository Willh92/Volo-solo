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
    <div class="console-table-toolbar">
        <div class="console-table-toolbar__group">
            <label for="userRoleFilter">角色筛选</label>
            <select id="userRoleFilter" onchange="admin.userList.getList(1)">
                <option value="all">全部角色</option>
                <option value="visitorRole">注册读者 (访客)</option>
                <option value="defaultRole">协作者</option>
                <option value="adminRole">管理员</option>
            </select>
        </div>
        <div class="console-table-toolbar__group console-table-toolbar__group--grow">
            <input id="userKeywordFilter" type="search" placeholder="搜索昵称或邮箱..." aria-label="搜索昵称或邮箱" onkeydown="if(event.key==='Enter'){event.preventDefault();admin.userList.getList(1)}"/>
            <button type="button" onclick="admin.userList.getList(1)">筛选</button>
        </div>
    </div>
    <div id="userTable"></div>
    <div id="userPagination" class="fn__margin12 fn__right"></div>
</div>
<div class="fn__clear"></div>
<div id="userUpdate" class="fn__none form form__no-table" data-title="${(userProfileLabel!'用户资料')?html}">
    <label for="userNameUpdate">${userName1Label}<span style="color: #991a1a"></span></label>
    <input id="userNameUpdate" type="text" autocomplete="off"/>
    <label for="userURLUpdate">${userURL1Label}</label>
    <input id="userURLUpdate" type="text" autocomplete="off"/>
    <label for="userAvatarUpdate">${userAvatar1Label}</label>
    <input id="userAvatarUpdate" type="text" autocomplete="off"/>
    <label for="userB3KeyUpdate">${passwordLabel}</label>
    <input id="userB3KeyUpdate" type="password" autocomplete="new-password" placeholder="${password1Label}"/>

    <div class="console-form-actions"><button type="button" onclick="admin.userList.update();">${updateLabel}</button></div>
    <div class="fn__clear"></div>
</div>
${plugins}
