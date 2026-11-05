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
<div class="form">
    <div>
        <label>${title1Label}</label>
        <input id="title" type="text" autocomplete="off"/>
    </div>
    <div>
        <label>${content1Label}</label>
        <div class="fn__right">
            <label for="articleThumbnail" style="margin-bottom: 0">
                ${useTumbnailLabel}
                <input type="checkbox" style="vertical-align: middle;"
                       id="articleThumbnail" onclick="$('.article__thumbnail').slideToggle()" />
            </label>
        </div>
        <div class="fn__clear"></div>
        <div class="article__thumbnail">
            <div class="thumbnail__img"></div>
            <button id="articleThumbnailBtn">${chageLabel}</button>
        </div>
        <div>
            <div id="articleContent" name="articleContent"
                  style="height: 500px;width:100%;"></div>
        </div>
    </div>
    <div class="console-tag-field">
        <label for="articleTagInput">${tags1WithTips1Label}</label>
        <div class="tag__select">
            <div class="console-tag-input">
                <div id="articleTagSelection" class="console-tag-selection" aria-label="已选标签"></div>
                <input id="articleTagInput" type="text" autocomplete="off" maxlength="200" placeholder="输入标签，按回车添加"/>
            </div>
            <input id="tag" type="hidden"/>
            <button id="articleTagToggle" type="button" aria-expanded="false" aria-controls="articleTagPanel">选择标签</button>
        </div>
        <section id="articleTagPanel" class="console-tag-panel" aria-label="已有标签" hidden>
            <div class="console-tag-heading"><strong>已有标签</strong><span id="articleTagCount" role="status"></span></div>
            <input id="articleTagSearch" type="search" placeholder="搜索已有标签" aria-label="搜索已有标签"/>
            <div id="articleTagOptions" class="console-tag-options"></div>
        </section>
    </div>
    <div class="console-form-grid">
        <div class="console-form-cell">
            <label for="categorySelector">${category1Label}</label>
            <select id="categorySelector">
                <option value="">无分类</option>
                <#list categories as category>
                    <option value="${category.oId?html}">${category.categoryTitle?html} (${category.categoryPublishedArticleCount})</option>
                </#list>
            </select>
        </div>
        <div class="console-form-cell">
            <label for="seriesSelector">所属专题系列（可选）</label>
            <select id="seriesSelector">
                <option value="">不加入专题系列</option>
            </select>
        </div>
    </div>
    <div class="console-form-grid">
        <div class="console-form-cell">
            <label for="createDate">${createDateLabel}</label>
            <input id="createDate" type="datetime-local"/>
        </div>
        <div class="console-form-cell">
            <label for="scheduleDate">定时自动发布时间（可选，未来时间到期自动公开）</label>
            <input id="scheduleDate" type="datetime-local"/>
        </div>
    </div>
    <div class="comment-content">
        <label>${abstract1Label}</label>
        <div>
            <div id="abstract" style="height: 200px;width: 100%;" name="abstract"></div>
        </div>
    </div>
    <div class="console-form-grid console-form-grid--3">
        <div class="console-form-cell console-form-cell--wide">
            <label for="permalink">${permalink1Label}</label>
            <input id="permalink" type="text" />
        </div>
        <div class="console-form-cell">
            <label for="viewPwd">${articleViewPwd1Label}</label>
            <input id="viewPwd" type="text" autocomplete="off" />
        </div>
    </div>
    <details id="articleAudioDetails" class="console-subcard">
        <summary>🎙️ 单集播客音频附件（可选，自动渲染文章顶部倍速播放器与 Podcast RSS &lt;enclosure&gt;）</summary>
        <div class="console-form-grid console-form-grid--4" style="margin-bottom: 0;">
            <div class="console-form-cell console-form-cell--wide">
                <label for="audioUrl">音频文件 URL（.mp3 / .m4a / .ogg）</label>
                <input id="audioUrl" type="text" autocomplete="off" placeholder="https://.../episode-01.mp3 或 /image/..." />
            </div>
            <div class="console-form-cell">
                <label for="audioDuration">时长（秒，可选）</label>
                <input id="audioDuration" type="number" min="0" step="1" placeholder="例如 420" />
            </div>
            <div class="console-form-cell">
                <label for="audioSize">文件字节大小（可选）</label>
                <input id="audioSize" type="number" min="0" step="1" placeholder="用于 RSS length" />
            </div>
        </div>
    </details>
    <div>
        <span class="signs">
            <label>${sign1Label}</label>
            <button style="margin-left: 0px;" id="articleSign1" class="selected">${signLabel}1</button>
            <button id="articleSign2">${signLabel}2</button>
            <button id="articleSign3">${signLabel}3</button>
            <button id="articleSign0">${noSignLabel}</button>
        </span>
        <div class="fn__right article-commentable__panel">
            <label class="checkbox">
                <input type="checkbox" id="articleCommentable" checked />
                ${allowCommentLabel}
            </label>
            &nbsp;
            <#if b3logEnabled || fishpiEnabled>
            <span id="postToCommunityPanel">
            <#else>
            <span id="postToCommunityPanel" style="display: none">
            </#if>
                <label class="checkbox">
                    <input id="postToCommunity" type="checkbox" />
                    <a href="https://github.com/Willh92/Volo-solo/blob/HEAD/UPGRADE.md#%E7%A4%BE%E5%8C%BA%E5%90%8C%E6%AD%A5" target="_blank">${syncToCommunityLabel}</a>
                </label>
            </span>
        </div>
        <div class="fn__clear"></div>
    </div>
    <div id="articleAdvancedTools" class="console-subcard" style="display: none;">
        <div class="console-inline-bar" style="justify-content: space-between; margin: 0;">
            <div class="console-inline-bar" style="margin: 0;">
                <strong style="font-size: 13px;">🔗 免密预览分享：</strong>
                <select id="shareTokenDuration">
                    <option value="24h">有效期 24 小时</option>
                    <option value="7d">有效期 7 天</option>
                    <option value="30d">有效期 30 天</option>
                </select>
                <button type="button" onclick="admin.article.generateShareToken()">生成预览链接</button>
                <button type="button" onclick="admin.article.revokeShareTokens()" title="旋转全局签名密钥，使所有已发出的预览链接立即失效">吊销全部链接</button>
            </div>
            <button type="button" onclick="admin.article.toggleRevisions()">🕘 历史版本与回滚（最近 10 版）</button>
        </div>
        <div id="shareTokenBox" style="display: none; margin-top: 12px;">
            <div class="console-inline-bar console-inline-bar--fill" style="margin: 0;">
                <input id="shareTokenOutput" type="text" readonly style="font-family: monospace; font-size: 12px;" />
                <button type="button" onclick="admin.article.copyShareToken()">复制链接</button>
            </div>
        </div>
        <div id="articleRevisionsDrawer" style="display: none; margin-top: 12px; border-top: 1px dashed var(--bolo-line); padding-top: 12px;">
            <div id="articleRevisionsList" style="font-size: 13px;">正在加载历史快照...</div>
            <div id="articleRevisionDiffBox" style="display: none; margin-top: 10px; max-height: 280px; overflow: auto; padding: 12px; border-radius: 8px; border: 1px solid var(--bolo-line); background: var(--bolo-surface); font-family: monospace; font-size: 12px; white-space: pre-wrap;"></div>
        </div>
    </div>
    <div class="console-form-actions">
        <button class="marginRight12" id="cancelDo">${cancelLabel}</button>
        <button id="unSubmitArticle" class="fn__none marginRight12" onclick="admin.article.unPublish();">${unPublishLabel}</button>
        <button class="marginRight12" id="saveArticle">${saveLabel}${draftListLabel}</button>
        <button id="submitArticle">${publishLabel}</button>
    </div>
    <div class="fn__clear"></div>
</div>
${plugins}