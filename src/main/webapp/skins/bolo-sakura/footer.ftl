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
<form class="js-search search-form search-form--modal" method="get" action="${servePath}/search" role="search">
    <div class="search-form__inner">
        <div>
            <p class="micro mb-">
                想要找点什么呢？
            </p>
            <i class="fa fa-search"></i><input autocomplete="off" class="text-input" type="search" name="keyword"
                                                       placeholder="Search" required>
        </div>
    </div>
    <button type="button" class="search_close" aria-label="关闭搜索"></button>
</form>
<script src="${staticServePath}/js/lib/compress/pjax.min.js?${staticResourceVersion}"></script>
<script src="${staticServePath}/js/bolo/jquery.cookie.min.js?${staticResourceVersion}"></script>
<script src="${staticServePath}/js/lib/fancybox/jquery.fancybox.min.js?${staticResourceVersion}"></script>
<script src="${staticServePath}/js/lib/lazyload.iife.min.js?${staticResourceVersion}"></script>
<script type="text/javascript" src="${staticServePath}/js/common${miniPostfix!''}.js?${staticResourceVersion}"
        charset="utf-8"></script>
<script type="text/javascript" src="${staticServePath}/skins/${skinDirName}/js/common${miniPostfix!''}.js?${staticResourceVersion}"
        charset="utf-8"></script>
<#include "../../common-template/label.ftl">

${plugins}
