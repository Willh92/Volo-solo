<#--

    Volo - A modern, secure, and lightweight blogging system.
    Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo

    SPDX-License-Identifier: AGPL-3.0-or-later

-->
<div class="form volo-admin-bookshelf">
    <div class="console-section-head">
        <div class="console-section-head__copy">
            <h3>📚 在线书架与内嵌阅读器</h3>
            <p>
                支持抓取 GitBook / HonKit、VuePress / VitePress、MdBook、Docusaurus 多级章节目录或绑定站内系列。在文章或独立页（如 <code>/book.html</code>）插入
                <code>:::bookshelf:::</code> 展示全部书架，或插入 <code>:::book id="书籍ID" :::</code> 嵌入单书沉浸式分栏阅读器。
            </p>
        </div>
        <div class="console-section-head__actions">
            <button type="button" onclick="admin.bookList.copyShortcode(':::bookshelf:::')">复制书架短代码</button>
            <button type="button" onclick="admin.bookList.resetBookForm(); $('#bookTitle').focus();">+ 新增书籍</button>
        </div>
    </div>

    <div id="voloBookTableWrap" class="console-simple-table-wrap">
        <table class="console-simple-table">
            <thead>
                <tr>
                    <th>书籍名称 / ID</th>
                    <th>来源类型与地址</th>
                    <th style="width: 112px;">阅读模式</th>
                    <th style="width: 96px;">章节数</th>
                    <th style="width: 152px;">最近同步</th>
                    <th class="is-right" style="width: 248px;">操作</th>
                </tr>
            </thead>
            <tbody id="voloBookTableBody">
                <tr><td colspan="6" class="console-simple-table__empty">正在加载书架数据...</td></tr>
            </tbody>
        </table>
    </div>

    <div class="console-subcard">
        <h4 id="bookFormHeading">新增 / 编辑书籍</h4>
        <div class="console-form-grid console-form-grid--4">
            <div class="console-form-cell console-form-cell--wide">
                <label for="bookTitle">书籍名称 *</label>
                <input id="bookTitle" type="text" maxlength="120" placeholder="例如：Go 语言入门指南" />
            </div>
            <div class="console-form-cell">
                <label for="bookId">书籍短 ID（可选，a-z0-9_-）</label>
                <input id="bookId" type="text" maxlength="16" placeholder="例如：go-guide（留空自动生成）" />
            </div>
            <div class="console-form-cell">
                <label for="bookEmbedMode">章节打开方式</label>
                <select id="bookEmbedMode">
                    <option value="iframe">页内分栏 / 全屏阅读器（推荐）</option>
                    <option value="link">新标签页打开原站章节</option>
                </select>
            </div>
        </div>

        <div class="console-form-grid console-form-grid--3">
            <div class="console-form-cell">
                <label for="bookSourceType">目录来源类型</label>
                <select id="bookSourceType" onchange="admin.bookList.onSourceTypeChange()">
                    <option value="auto">自动识别（GitBook / VuePress / VitePress / MdBook）</option>
                    <option value="gitbook">GitBook 3 / HonKit</option>
                    <option value="vuepress">VuePress / VitePress / Docusaurus</option>
                    <option value="mdbook">Rust MdBook</option>
                    <option value="series">绑定站内专题系列</option>
                    <option value="manual">纯手动维护 JSON 目录</option>
                </select>
            </div>
            <div id="bookSourceUrlWrap" class="console-form-cell console-form-cell--wide">
                <label for="bookSourceUrl">外部书籍根地址（URL）</label>
                <input id="bookSourceUrl" type="url" placeholder="例如：https://docs.example.com/go/" />
            </div>
            <div id="bookSeriesWrap" class="console-form-cell console-form-cell--wide" style="display: none;">
                <label for="bookSeriesId">绑定站内专题系列</label>
                <select id="bookSeriesId">
                    <option value="">请选择专题系列</option>
                </select>
            </div>
        </div>

        <div class="console-form-grid console-form-grid--3">
            <div class="console-form-cell">
                <label for="bookCoverUrl">封面图 URL（可选）</label>
                <input id="bookCoverUrl" type="text" placeholder="https://... 或 /image/..." />
            </div>
            <div class="console-form-cell console-form-cell--wide">
                <label for="bookDescription">书籍简介（可选）</label>
                <input id="bookDescription" type="text" maxlength="500" placeholder="一句话介绍本书核心内容与适合人群" />
            </div>
        </div>

        <details id="bookManualTocDetails" style="margin-top: 12px;">
            <summary style="cursor: pointer; user-select: none; font-size: 13px; color: var(--bolo-muted);">高级：查看 / 手动编辑章节目录 JSON（留空则保持已同步目录）</summary>
            <div style="margin-top: 10px;">
                <textarea id="bookTocJson" rows="6" style="width: 100%; font-family: monospace; font-size: 12px;"
                          placeholder='[{"title":"第一章 快速开始","url":"https://docs.example.com/go/ch1.html","children":[]}]'></textarea>
            </div>
        </details>

        <div class="console-form-actions" style="margin-top: 16px;">
            <button type="button" onclick="admin.bookList.resetBookForm()">重置表单</button>
            <button type="button" id="saveBookBtn" onclick="admin.bookList.saveBook()">保存书籍配置</button>
        </div>
    </div>

    <hr class="console-section-divider" />

    <div class="console-section-head">
        <div class="console-section-head__copy">
            <h3>🗂️ 专题系列合集（Ordered Series）</h3>
            <p>
                将多篇文章组织为有序系列，文章正文底部将自动渲染「系列目录折叠卡 + 上一篇 / 下一篇导读」，也可直接绑定到上方在线书架。
            </p>
        </div>
        <div class="console-section-head__actions">
            <button type="button" onclick="admin.bookList.resetSeriesForm(); $('#seriesTitle').focus();">+ 新建专题系列</button>
        </div>
    </div>

    <div class="console-simple-table-wrap">
        <table class="console-simple-table">
            <thead>
                <tr>
                    <th>系列名称 / ID</th>
                    <th>系列简介</th>
                    <th style="width: 110px;">包含文章</th>
                    <th class="is-right" style="width: 190px;">操作</th>
                </tr>
            </thead>
            <tbody id="voloSeriesTableBody">
                <tr><td colspan="4" class="console-simple-table__empty">正在加载专题系列...</td></tr>
            </tbody>
        </table>
    </div>

    <div class="console-subcard">
        <h4 id="seriesFormHeading">新建 / 编辑专题系列</h4>
        <input id="seriesEditId" type="hidden" value="" />
        <div class="console-form-grid console-form-grid--3">
            <div class="console-form-cell">
                <label for="seriesTitle">系列标题 *</label>
                <input id="seriesTitle" type="text" maxlength="120" placeholder="例如：Netty 源码深度剖析系列" />
            </div>
            <div class="console-form-cell console-form-cell--wide">
                <label for="seriesDescription">系列导读简介（可选）</label>
                <input id="seriesDescription" type="text" maxlength="500" placeholder="显示在每篇系列文章目录框顶部的导读说明" />
            </div>
        </div>

        <div style="margin-top: 14px;">
            <label style="display: block; margin-bottom: 8px;">系列文章顺序（可从已发布文章快速加入并上下调整顺序）</label>
            <div class="console-inline-bar console-inline-bar--fill" style="margin: 0 0 10px;">
                <select id="seriesArticlePicker">
                    <option value="">-- 选择已发布文章加入本系列 --</option>
                </select>
                <button type="button" onclick="admin.bookList.addPickedArticleToSeries()">加入系列末尾</button>
            </div>
            <div id="seriesArticleOrderList" class="console-series-order-list">
                <span style="color: var(--bolo-muted); font-size: 13px;">暂未添加文章（也可在撰写/编辑文章页面直接选择所属系列）</span>
            </div>
        </div>

        <div class="console-form-actions" style="margin-top: 16px;">
            <button type="button" onclick="admin.bookList.resetSeriesForm()">重置系列表单</button>
            <button type="button" id="saveSeriesBtn" onclick="admin.bookList.saveSeries()">保存专题系列</button>
        </div>
    </div>
</div>
${plugins}
