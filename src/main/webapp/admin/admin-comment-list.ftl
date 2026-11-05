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
<form class="form console-table-toolbar console-list-filter">
    <div class="console-table-toolbar__group">
        <label for="commentStatusFilter">状态筛选</label>
        <select id="commentStatusFilter" onchange="admin.commentList.getList(1)">
            <option value="all">全部评论</option>
            <option value="pending">待审核</option>
            <option value="approved">已公开</option>
            <option value="rejected">已驳回</option>
        </select>
        <span id="commentPendingBadge" class="console-form-help" role="status"></span>
    </div>
    <div class="console-table-toolbar__group console-table-toolbar__group--grow">
        <input id="commentListInput" type="search"/>
        <button type="submit" class="search-btn">${searchLabel!'搜索'}</button>
    </div>
</form>
<div id="commentTable">
</div>
<div id="commentPagination" class="fn__right fn__margin12">
</div>
<div class="fn__clear"></div>
${plugins}
