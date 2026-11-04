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
    <div id="categoryTable"></div>
    <div id="categoryPagination" class="fn__margin12 fn__right"></div>
</div>
<div class="fn__clear"></div>
<div id="categoryEditor" class="form form__no-table">
    <h3 id="categoryEditorTitle">${addCategoryLabel!'添加分类'}</h3>
    <label for="categoryName">${linkTitle1Label}</label>
    <input id="categoryName" type="text" autocomplete="off"/>
    <label for="categoryURI">URI：</label>
    <input id="categoryURI" type="text" autocomplete="off"/>
    <label for="categoryDesc">${linkDescription1Label}</label>
    <input id="categoryDesc" type="text" autocomplete="off"/>
    <div class="console-form-actions"><button id="categoryCancelEdit" type="button" onclick="admin.categoryList.cancelEdit();" hidden>${cancelLabel}</button><button id="categorySave" type="button" onclick="admin.categoryList.add();">${saveLabel}</button></div>
</div>
${plugins}
