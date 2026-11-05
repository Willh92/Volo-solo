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
    <script defer src="${staticServePath}/js/captcha${miniPostfix!''}.js?${staticResourceVersion}"></script>
    <div id="soloEditor" class="comment-composer" role="dialog" aria-label="${(commentLabel!'评论')?html}" aria-hidden="true" inert>
        <div class="comment-composer__inner">
            <#if isLoggedIn!false>
            <div id="soloEditorUserBar" class="comment-composer__visitor">
                <strong>${(userName!'已登录用户')?html}</strong><span><#if isAdminLoggedIn!false>博主<#else>认证读者</#if></span>
                <#if !(isAdminLoggedIn!false)>
                <button type="button" onclick="window.VoloReaderAuth ? window.VoloReaderAuth.logout() : null">退出登录</button>
                </#if>
            </div>
            </#if>
            <div id="soloEditorVisitorCard" class="comment-composer__visitor" hidden>
                <img id="soloEditorVisitorAvatar" alt=""/><strong id="soloEditorVisitorName"></strong><span>访客</span>
                <button id="soloEditorEditIdentity" type="button">修改资料</button>
                <#if (allowRegister!false) || (commentAccessMode!'all') == 'registered'>
                <button type="button" onclick="window.VoloReaderAuth ? window.VoloReaderAuth.open('login') : (window.location.href='${servePath}/user/login')">账号登录</button>
                </#if>
            </div>
            <div id="soloEditorIdentity" class="comment-composer__identity" hidden>
                <label for="boloUser">昵称<span id="soloEditorNameSlot"></span></label>
                <label for="boloSite">个人主页（选填）<span id="soloEditorSiteSlot"></span></label>
                <label for="soloEditorEmail">通知邮箱（选填）<input id="soloEditorEmail" type="email" maxlength="254" autocomplete="off" placeholder="仅用于回复通知，不公开展示"/></label>
            </div>
            <div id="soloEditorComment"></div>
            <div id="soloEditorCaptcha" class="comment-composer__captcha" hidden></div>
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
<script src="${staticServePath}/js/bolo/jquery.cookie.min.js?${staticResourceVersion}"></script>
<script type="text/javascript">
    var page;
    (function () {
        var disposed = false;
        var pendingToggle = null;
        var commentTrigger = null;
        var onPreBootCommentClick = function () {
            if (!pendingToggle) {
                pendingToggle = [undefined, undefined];
            }
        };
        var cleanupPreBoot = function () {
            if (commentTrigger && typeof commentTrigger.removeEventListener === 'function') {
                commentTrigger.removeEventListener('click', onPreBootCommentClick);
            }
            commentTrigger = null;
        };
        var bindPreBootCommentClick = function () {
            if (disposed || commentTrigger || typeof Page === 'function' || typeof document.getElementById !== 'function') {
                return;
            }
            var el = document.getElementById('comment');
            if (el && typeof el.addEventListener === 'function') {
                commentTrigger = el;
                commentTrigger.addEventListener('click', onPreBootCommentClick, { once: true });
            }
        };
        if (window.BoloNavigation && typeof window.BoloNavigation.onLeave === 'function') {
            window.BoloNavigation.onLeave(function () {
                disposed = true;
                cleanupPreBoot();
            });
        }
        if (typeof Page !== 'function') {
            page = window.page = {
                toggleEditor: function (id, name) { pendingToggle = [id, name]; },
                addReplyForm: function (id, name) { pendingToggle = [id, name]; },
                showComment: function () {},
                hideComment: function () {}
            };
            bindPreBootCommentClick();
            if (!commentTrigger && typeof $ === 'function') {
                $(document).ready(bindPreBootCommentClick);
            }
        }
        var bootSoloPage = function () {
            cleanupPreBoot();
            if (disposed || typeof Page !== 'function') {
                return;
            }
            var instance = new Page({
                "commentContentCannotEmptyLabel": "${commentContentCannotEmptyLabel?js_string}",
                "oId": "${oId?js_string}",
                "commentAdministrator": ${(isAdminLoggedIn!false)?c},
                "commentAccessMode": "${(commentAccessMode!'all')?js_string}",
                "allowRegister": ${(allowRegister!false)?c},
                "blogHost": "${blogHost?js_string}",
                "randomArticles1Label": "${randomArticles1Label?js_string}",
            });
            page = window.page = instance;
            $(document).ready(function () {
                if (disposed) {
                    return;
                }
                page = window.page = instance;
                page.load();
                <#nested>
                if (pendingToggle) {
                    var args = pendingToggle;
                    pendingToggle = null;
                    page.toggleEditor(args[0], args[1]);
                }
            });
        };
        if (typeof Page === 'function') {
            bootSoloPage();
        } else {
            var loading = Util.addScript('${staticServePath}/js/page${miniPostfix!''}.js?${staticResourceVersion}', 'soloPageScript', bootSoloPage);
            if (loading && typeof loading.catch === 'function') {
                loading.catch(function () {});
            }
        }
    })();
</script>
</#macro>
