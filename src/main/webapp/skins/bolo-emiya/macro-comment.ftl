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
<#macro dynamic_comment comment>
  <div class="comment" id="${comment.oId}">
    <div class="comment__info">
      <#if "http://" == comment.commentURL>
      <span class="username">${comment.commentName?html}</span>
      <#else>
      <a class="username" href="${comment.commentURL?html}" target="_blank">${comment.commentName?html}</a>
      </#if>
      <a class="btn" href="${servePath}${comment.commentSharpURL}">${viewLabel}»</a>
    </div>
    <main class="comment__detail">
      <div class="vditor-reset">
        <div data-comment-content>${comment.commentContent}</div>
      </div>
      <time class="time">${comment.commentDate?string("yyyy-MM-dd HH:mm")}</time>
      <#if (comment.commentIPMasked!'')?has_content><span class="comment-location">${comment.commentIPMasked?html}<#if (comment.commentRegion!'')?has_content> · ${comment.commentRegion?html}</#if></span></#if>
    </main>
  </div>
</#macro>

<#macro article_comment comment article>
  <div class="comment" id="${comment.oId}" data-comment-id="${comment.oId?html}" data-comment-thread="${(comment.commentThreadId!comment.oId)?html}" data-comment-depth="${(comment.commentDepth!0)?c}" data-comment-deleted="${(comment.commentDeleted!false)?c}">
    <div class="comment__info">
      <#if "http://" == comment.commentURL || "https://" == comment.commentURL>
        <span class="username">${comment.commentName?html}</span>
        <#else>
        <a class="username" href="${comment.commentURL?html}" target="_blank">${comment.commentName?html}</a>
      </#if>
      <#if comment.isReply>
      @<a 
        class="replyName J_replyName"
        href="javascript:;"
        data-originalId="${comment.commentOriginalCommentId}" 
      >${comment.commentOriginalCommentName?html}</a>
      </#if>
      <#if article.commentable>
        <a class="btn" href="#comment" data-comment-id="${comment.oId?html}" data-comment-name="${comment.commentName?html}" onclick="page.toggleEditor(this.dataset.commentId, this.dataset.commentName); return false;">${replyLabel}</a>
      </#if>
    </div>
    <main class="comment__detail">
      <div class="vditor-reset">
        <div data-comment-content>${comment.commentContent}</div>
      </div>
      <time class="time">${comment.commentDate2?string("yyyy-MM-dd HH:mm")}</time>
      <#if (comment.commentIPMasked!'')?has_content><span class="comment-location">${comment.commentIPMasked?html}<#if (comment.commentRegion!'')?has_content> · ${comment.commentRegion?html}</#if></span></#if>
    </main>
  </div>
</#macro>

<#macro article_comments commentList article>
<#if article.commentable>
  <input id="boloUser" placeholder="你的昵称" style="border: 1px solid #eee; padding: 20px; width: fill-available; width: -webkit-fill-available;">
  <input id="boloSite" placeholder="你的个人主页URL（选填）" style="border: 1px solid #eee; padding: 20px; width: fill-available; width: -webkit-fill-available;">
  <textarea rows="3" placeholder="${postCommentsLabel}" class="comment__textarea" id="comment" readonly autocomplete="off" name="volo-comment-trigger"></textarea>
  <script type="text/javascript" src="${staticServePath}/js/bolo/sweetalert.min.js"></script>
</#if>
<#if commentList?size != 0>
  <h3>评论列表</h3>
  <ul class="article__comments" id="comments">
    <#list commentList as comment>
      <@article_comment comment=comment article=article></@article_comment>
    </#list>
  </ul>
</#if>
</#macro>