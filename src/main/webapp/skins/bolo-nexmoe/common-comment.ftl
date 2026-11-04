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
<li id="${comment.oId?html}" class="fn-clear" data-comment-id="${comment.oId?html}" data-comment-thread="${(comment.commentThreadId!comment.oId)?html}" data-comment-depth="${(comment.commentDepth!0)?c}" data-comment-deleted="${(comment.commentDeleted!false)?c}">
    <div class="comment-body">
        <div class="fn-clear comment-meta">
                <img class="comment-avatar" data-avatar-seed="${comment.commentName?html}" src="${comment.commentThumbnailURL?html}" width="32" height="32" alt=""/>
                <span class="comment-author">
                    <#if !(comment.commentURL?starts_with('https://') || comment.commentURL?starts_with('http://')) || comment.commentURL == 'http://' || comment.commentURL == 'https://'>
                        <a>${comment.commentName?html}</a>
                    <#else>
                        <a href="${comment.commentURL?html}" target="_blank" rel="noopener noreferrer nofollow">${comment.commentName?html}</a>
                    </#if>
                    <#if comment.isReply>
                        @
                    <a href="${servePath}${article.permalink}#${comment.commentOriginalCommentId?html}"
                       onmouseover="page.showComment(this, '${comment.commentOriginalCommentId?js_string}', 23);"
                       onmouseout="page.hideComment('${comment.commentOriginalCommentId?js_string}')"
                    >${comment.commentOriginalCommentName?html}</a>
                    </#if>
                </span>
                <span class="comment-details">
                    <time>${comment.commentDate2?string("yyyy-MM-dd HH:mm")}</time>
        <#if (comment.commentIPMasked!'')?has_content>
            <span class="comment-location">${comment.commentIPMasked?html}<#if (comment.commentRegion!'')?has_content> · ${comment.commentRegion?html}</#if></span>
        </#if>
                </span>
        <#if article.commentable>
            <a class="fn-right" href="#comment" data-comment-id="${comment.oId?html}" data-comment-name="${comment.commentName?html}" onclick="page.toggleEditor(this.dataset.commentId, this.dataset.commentName); return false;">${replyLabel}</a>
        </#if>
        </div>
        <div class="comment-content post-body vditor-reset">
        <div data-comment-content>${comment.commentContent}</div>
        </div>
    </div>
</li>
