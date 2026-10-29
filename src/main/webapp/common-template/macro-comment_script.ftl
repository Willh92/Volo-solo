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
<#macro comment_script oId commentable>
<#if commentable>
    <link rel="preload" as="script" href="${servePath}/js/lib/vditor/dist/index.min.js"/>
    <div id="soloEditor" class="comment-composer" role="dialog" aria-label="${(commentLabel!'评论')?html}" aria-hidden="true" inert>
        <div class="comment-composer__inner">
            <div id="soloEditorVisitorCard" class="comment-composer__visitor" hidden>
                <img id="soloEditorVisitorAvatar" alt=""/><strong id="soloEditorVisitorName"></strong><span>访客</span>
                <button id="soloEditorEditIdentity" type="button">修改资料</button>
            </div>
            <div id="soloEditorIdentity" class="comment-composer__identity" hidden>
                <label for="boloUser">昵称<span id="soloEditorNameSlot"></span></label>
                <label for="boloSite">个人主页（选填）<span id="soloEditorSiteSlot"></span></label>
                <label for="soloEditorEmail">通知邮箱（选填）<input id="soloEditorEmail" type="email" maxlength="254" autocomplete="email" placeholder="仅用于回复通知，不公开展示"/></label>
            </div>
            <div id="soloEditorComment"></div>
            <div class="comment-composer__actions">
                <div id="soloEditorReplyTarget"></div>
                <div id="soloEditorError" role="alert"></div>
                <button id="soloEditorCancel" type="button">
                    ${cancelLabel}
                </button>
                <button id="soloEditorRetry" type="button" hidden>重新加载</button>
                <button id="soloEditorAdd" type="button" disabled>
                    ${confirmLabel}
                </button>
            </div>
        </div>
    </div>
</#if>
<script type="text/javascript">
    Util.addScript('${staticServePath}/js/page.js?${staticResourceVersion}', 'soloPageScript')
    var page = new Page({
        "commentContentCannotEmptyLabel": "${commentContentCannotEmptyLabel}",
        "oId": "${oId}",
        "blogHost": "${blogHost}",
        "randomArticles1Label": "${randomArticles1Label}",
    });
    $(document).ready(function () {
        page.load();
        <#nested>
    });
</script>
<script src="${staticServePath}/js/bolo/jquery.cookie.min.js"></script>
</#macro>
