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
    <div id="articleDraftRecoveryBar" class="console-recovery-banner" style="display: none;" role="status">
        <div class="console-recovery-banner__copy">
            <strong>检测到未提交的本地草稿快照</strong>
            <span id="articleDraftRecoveryMeta"></span>
        </div>
        <div class="console-recovery-banner__actions">
            <button type="button" onclick="admin.article.restoreLocalDraft()">恢复草稿</button>
            <button type="button" class="is-ghost" onclick="admin.article.discardLocalDraft()">丢弃快照</button>
        </div>
    </div>
    <div>
        <label>${title1Label}</label>
        <input id="title" type="text" autocomplete="off"/>
    </div>
    <div>
        <div class="console-editor-topbar">
            <label style="margin: 0;">${content1Label}</label>
            <div class="console-editor-topbar__tools">
                <button type="button" id="articleQuickInsertBtn" class="console-btn-compact" onclick="admin.article.openQuickInsert('media')">快捷插入（图库 / 书架 / 锁块 / 引用）</button>
                <label for="articleThumbnail" class="checkbox" style="margin: 0;">
                    <input type="checkbox" id="articleThumbnail" onclick="$('.article__thumbnail').slideToggle()" />
                    <span>${useTumbnailLabel}</span>
                </label>
            </div>
        </div>
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
<div id="voloQuickInsertModal" class="console-modal-backdrop" style="display: none;" onclick="if(event.target===this)admin.article.closeQuickInsert()">
    <div class="console-modal" role="dialog" aria-modal="true" aria-labelledby="voloQuickInsertTitle">
        <div class="console-modal__header">
            <div>
                <h4 id="voloQuickInsertTitle">快捷内容插入</h4>
                <p>可视化选择本地图库、在线书架、隐藏内容锁块或站内文章引用链接。</p>
            </div>
            <button type="button" class="console-modal__close" onclick="admin.article.closeQuickInsert()" aria-label="关闭">×</button>
        </div>
        <div class="console-modal__tabs" role="tablist">
            <button type="button" data-qtab="media" class="is-active" onclick="admin.article.switchQuickInsertTab('media')">本地媒体库</button>
            <button type="button" data-qtab="books" onclick="admin.article.switchQuickInsertTab('books')">电子书与书架</button>
            <button type="button" data-qtab="lock" onclick="admin.article.switchQuickInsertTab('lock')">局部隐藏锁块</button>
            <button type="button" data-qtab="cite" onclick="admin.article.switchQuickInsertTab('cite')">站内文章引用</button>
        </div>
        <div class="console-modal__body">
            <div id="quickInsertPanel_media" class="console-modal__panel">
                <div class="console-inline-bar console-inline-bar--fill">
                    <input id="quickMediaSearchInput" type="search" placeholder="按文件名搜索本地图片..." onkeydown="if(event.key==='Enter'){event.preventDefault();admin.article.loadQuickMedia();}"/>
                    <button type="button" onclick="admin.article.loadQuickMedia()">检索</button>
                </div>
                <div id="quickMediaGrid" class="console-quick-media-grid"></div>
            </div>
            <div id="quickInsertPanel_books" class="console-modal__panel" style="display: none;">
                <div class="console-inline-bar" style="justify-content: space-between;">
                    <span class="console-form-help" style="margin: 0;">可插入整页多书书架或单本章节阅读器短代码。</span>
                    <button type="button" onclick="admin.article.insertQuickText('\n:::bookshelf:::\n')">插入整页在线书架</button>
                </div>
                <div id="quickBooksList" class="console-quick-list"></div>
            </div>
            <div id="quickInsertPanel_lock" class="console-modal__panel" style="display: none;">
                <div class="console-form-grid">
                    <div class="console-form-cell">
                        <label for="quickLockType">解锁条件类型</label>
                        <select id="quickLockType" onchange="document.getElementById('quickLockPwdCell').style.display=this.value==='pwd'?'':'none'">
                            <option value="pwd">输入口令密码可见（type="pwd"）</option>
                            <option value="user">登录读者账号可见（type="user"）</option>
                        </select>
                    </div>
                    <div class="console-form-cell" id="quickLockPwdCell">
                        <label for="quickLockPwd">局部解锁密码</label>
                        <input id="quickLockPwd" type="text" maxlength="64" placeholder="例如 2026" />
                    </div>
                </div>
                <div class="console-form-cell" style="margin-bottom: 12px;">
                    <label for="quickLockHint">未解锁时的提示文案（可选）</label>
                    <input id="quickLockHint" type="text" maxlength="120" placeholder="例如：请输入口令或登录账号后阅读本段核心内容" />
                </div>
                <div class="console-form-cell">
                    <label for="quickLockBody">受保护的局部正文（支持完整 Markdown）</label>
                    <textarea id="quickLockBody" rows="4" placeholder="在此输入需要保护的正文、下载链接或配置片段..."></textarea>
                </div>
                <div class="console-form-actions" style="margin-top: 12px;">
                    <button type="button" onclick="admin.article.insertQuickLockBlock()">插入隐藏锁块</button>
                </div>
            </div>
            <div id="quickInsertPanel_cite" class="console-modal__panel" style="display: none;">
                <div class="console-inline-bar console-inline-bar--fill">
                    <input id="quickCiteSearchInput" type="search" placeholder="输入文章标题关键词搜索站内文章（自动建立双向反向链接）..." onkeydown="if(event.key==='Enter'){event.preventDefault();admin.article.loadQuickArticles();}"/>
                    <button type="button" onclick="admin.article.loadQuickArticles()">搜索</button>
                </div>
                <div id="quickCiteList" class="console-quick-list"></div>
            </div>
        </div>
    </div>
</div>
${plugins}