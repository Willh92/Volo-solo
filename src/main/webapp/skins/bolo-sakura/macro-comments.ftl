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
<#macro comments commentList article count>
    <#if article.commentable>
        <div id="respond" class="comment-respond">
            <p>
                <i class="fa fa-code"></i> Markdown Supported while <i class="fa fa-code" aria-hidden="true"></i> Forbidden
            </p>
            <input autocomplete="off" type="text" placeholder="你的昵称" id="boloUser" style="margin-bottom: 10px">
            <input autocomplete="off" type="text" placeholder="你的个人主页URL（选填）" id="boloSite" style="margin-bottom: 10px">
            <div class="comment-textarea">
                <textarea placeholder="${postCommentsLabel} ..." aria-label="${postCommentsLabel}" class="commentbody" id="comment" rows="5" tabindex="4" readonly autocomplete="off" name="volo-comment-trigger"></textarea>
            </div>
        </div>
        <script type="text/javascript" src="${staticServePath}/js/bolo/sweetalert.min.js"></script>
    </#if>
    <h3 id="comments-list-title">${commentLabel} · <span class="noticom">${count}</span></h3>
    <ul id="comments" class="comment-list">
    <#list commentList as comment>
        <#include 'common-comment.ftl'/>
    </#list>
    </ul>
</#macro>
