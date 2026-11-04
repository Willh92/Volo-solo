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
<#setting locale="en_US">
<li id="${comment.oId?html}" class="item" data-comment-id="${comment.oId?html}" data-comment-thread="${(comment.commentThreadId!comment.oId)?html}" data-comment-depth="${(comment.commentDepth!0)?c}" data-comment-deleted="${(comment.commentDeleted!false)?c}">
    <div class="fn__clear">
        <div class="item__name">
            <#if !(comment.commentURL?starts_with('https://') || comment.commentURL?starts_with('http://')) || comment.commentURL == 'http://' || comment.commentURL == 'https://'>
                <span class="ft__fade">${comment.commentName?html}</span>
            <#else>
            <a class="ft__link" href="${comment.commentURL?html}" target="_blank" rel="noopener noreferrer nofollow">${comment.commentName?html}</a>
            </#if>

            <#if comment.isReply>
            @ <a href="${servePath}${article.permalink}#${comment.commentOriginalCommentId?html}"
                 onmouseover="page.showComment(this, '${comment.commentOriginalCommentId?js_string}', 6);"
                 onmouseout="page.hideComment('${comment.commentOriginalCommentId?js_string}')"
            >${comment.commentOriginalCommentName?html}</a>
            </#if>
        </div>
    </div>
    <div class="vditor-reset">
    <div data-comment-content>${comment.commentContent}</div>
    </div>
    <div class="item__meta fn__clear">
        <time>
        ${comment.commentDate2?string["MMM d, yyyy"]}
        </time>
        <#if (comment.commentIPMasked!'')?has_content>
            <span class="comment-location">${comment.commentIPMasked?html}<#if (comment.commentRegion!'')?has_content> · ${comment.commentRegion?html}</#if></span>
        </#if>
        <#if article?? && article.commentable>
            <a class="fn__right item__reply"
               href="#comment" data-comment-id="${comment.oId?html}" data-comment-name="${comment.commentName?html}" onclick="page.toggleEditor(this.dataset.commentId, this.dataset.commentName); return false;">${replyLabel}</a>
        </#if>
    </div>
</li>