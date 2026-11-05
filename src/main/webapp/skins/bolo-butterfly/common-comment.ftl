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
<div class="vcard" id="${comment.oId?html}" xmlns="http://www.w3.org/1999/html" data-comment-id="${comment.oId?html}" data-comment-thread="${(comment.commentThreadId!comment.oId)?html}" data-comment-depth="${(comment.commentDepth!0)?c}" data-comment-deleted="${(comment.commentDeleted!false)?c}">
    <div class = "vavatar">
        <img data-avatar-seed="${comment.commentName?html}" class="vcimg" src="${comment.commentThumbnailURL?html}" loading="lazy" decoding="async">
    </div>
    <div class="vh">
        <div class="vhead">
            <div class = "vcmeta">
                <#if !(comment.commentURL?starts_with('https://') || comment.commentURL?starts_with('http://')) || comment.commentURL == 'http://' || comment.commentURL == 'https://'>
                    <a href="javascript:void(0);" rel="nofollow">${comment.commentName?html}</a>
                <#else>
                    <a class="vnick" rel="nofollow noopener noreferrer" href="${comment.commentURL?html}" target="_blank">${comment.commentName?html}</a>
                </#if>
            </div>
            <#if article.commentable>
                <a rel="nofollow" class="vat" href="#comment" data-comment-id="${comment.oId?html}" data-comment-name="${comment.commentName?html}" onclick="page.toggleEditor(this.dataset.commentId, this.dataset.commentName); return false;">
                        <span class = "vicon">
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><path d="M256 32C114.6 32 0 125.1 0 240c0 47.6 19.9 91.2 52.9 126.3C38 405.7 7 439.1 6.5 439.5c-6.6 7-8.4 17.2-4.6 26S14.4 480 24 480c61.5 0 110-25.7 139.1-46.3C192 442.8 223.2 448 256 448c141.4 0 256-93.1 256-208S397.4 32 256 32zm0 368c-26.7 0-53.1-4.1-78.4-12.1l-22.7-7.2-19.5 13.8c-14.3 10.1-33.9 21.4-57.5 29 7.3-12.1 14.4-25.7 19.9-40.2l10.6-28.1-20.6-21.8C69.7 314.1 48 282.2 48 240c0-88.2 93.3-160 208-160s208 71.8 208 160-93.3 160-208 160z"></path></svg>
                        </span>
                </a>
            </#if>
            <div class="vtime"><time>${comment.commentDate2?string("yyyy-MM-dd HH:mm")}</time>
                <#if (comment.commentIPMasked!'')?has_content>
                    <span class="comment-location">${comment.commentIPMasked?html}<#if (comment.commentRegion!'')?has_content> · ${comment.commentRegion?html}</#if></span>
                </#if>
            </div>
        </div>
        <div class="ivcon">
                <span class = "vctext">
                <#if comment.isReply>
                    ${replyLabel}
                    <a href="${servePath}${article.permalink}#${comment.commentOriginalCommentId?html}"
                       onmouseover="page.showComment(this, '${comment.commentOriginalCommentId?js_string}', 23);"
                       onmouseout="page.hideComment('${comment.commentOriginalCommentId?js_string}')"
                    >@${comment.commentOriginalCommentName?html}</a>
                </#if>
                </span>
            <span>
                <div class="vcontent">
                    <div data-comment-content>${comment.commentContent}</div>
                </div>
                </span>
        </div>
    </div>
</div>
