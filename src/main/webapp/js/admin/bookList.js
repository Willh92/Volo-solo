/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * @fileoverview Bookshelf & Ordered Article Series management for Volo admin console.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 */
admin.bookList = {
    books: [],
    series: [],
    articleMap: {},
    editingSeriesArticles: [],

    escape: function (val) {
        if (typeof Util !== 'undefined' && Util.escapeHtml) {
            return Util.escapeHtml(val)
        }
        return String(val == null ? '' : val)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;')
    },

    formatTime: function (ms) {
        if (!ms || Number(ms) <= 0) return '尚未同步'
        var d = new Date(Number(ms))
        if (isNaN(d.getTime())) return '尚未同步'
        var pad = function (n) { return n < 10 ? '0' + n : String(n) }
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
            ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
    },

    init: function () {
        this.loadArticlesCatalog()
        this.refresh()
    },

    refresh: function () {
        var that = this
        $('#loadMsg').text(Label.loadingLabel || 'Loading...')
        $.ajax({
            url: Label.servePath + '/console/books',
            type: 'GET',
            cache: false,
            success: function (res) {
                if (!res || !res.sc) {
                    admin.showNotice((res && res.msg) || '加载书架失败', 'error')
                    return
                }
                that.books = Array.isArray(res.books) ? res.books : []
                that.series = Array.isArray(res.series) ? res.series : []
                that.renderBooks()
                that.renderSeries()
                that.populateSeriesSelect()
            },
            complete: function () {
                $('#loadMsg').text('')
            }
        })
    },

    loadArticlesCatalog: function () {
        var that = this
        $.ajax({
            url: Label.servePath + '/console/articles/status/published/1/100/10',
            type: 'GET',
            cache: false,
            success: function (res) {
                if (!res || !res.sc || !Array.isArray(res.articles)) return
                var picker = $('#seriesArticlePicker')
                picker.empty().append($('<option value="">-- 选择已发布文章加入本系列 --</option>'))
                for (var i = 0; i < res.articles.length; i++) {
                    var a = res.articles[i]
                    var rawTitle = Util && Util.htmlDecode ? Util.htmlDecode(a.articleTitle) : a.articleTitle
                    that.articleMap[a.oId] = rawTitle || a.oId
                    var opt = $('<option></option>').val(a.oId).text(rawTitle)
                    picker.append(opt)
                }
                that.renderSeriesArticleOrder()
            }
        })
    },

    populateSeriesSelect: function () {
        var sel = $('#bookSeriesId')
        var current = sel.val() || ''
        sel.empty().append($('<option value="">请选择专题系列</option>'))
        for (var i = 0; i < this.series.length; i++) {
            var s = this.series[i]
            sel.append($('<option></option>').val(s.id).text(s.title + ' (' + ((s.articleIds && s.articleIds.length) || 0) + ' 篇)'))
        }
        if (current) sel.val(current)
    },

    onSourceTypeChange: function () {
        var t = $('#bookSourceType').val()
        if (t === 'series') {
            $('#bookSourceUrlWrap').hide()
            $('#bookSeriesWrap').show()
        } else if (t === 'manual') {
            $('#bookSourceUrlWrap').hide()
            $('#bookSeriesWrap').hide()
            $('#bookManualTocDetails').prop('open', true)
        } else {
            $('#bookSourceUrlWrap').show()
            $('#bookSeriesWrap').hide()
        }
    },

    renderBooks: function () {
        var tbody = $('#voloBookTableBody')
        if (!this.books.length) {
            tbody.html('<tr><td colspan="6" class="console-simple-table__empty">暂无书籍配置。可在下方填写外部电子书根地址（如 <code>https://docs.example.com/go/</code>）后点击保存并同步章节。</td></tr>')
            return
        }
        var html = ''
        for (var i = 0; i < this.books.length; i++) {
            var b = this.books[i]
            var safeId = this.escape(b.id)
            var safeTitle = this.escape(b.title)
            var srcLabel = this.escape(b.sourceType || 'auto')
            var srcDetail = b.sourceType === 'series'
                ? ('站内系列: ' + this.escape(b.seriesId || ''))
                : this.escape(b.sourceUrl || '手动 JSON')
            var modeLabel = b.embedMode === 'link' ? '外链打开' : '页内阅读器'
            html += '<tr>' +
                '<td><strong>' + safeTitle + '</strong><br/><code style="font-size:12px;opacity:0.75;">id="' + safeId + '"</code></td>' +
                '<td style="word-break: break-all;"><span style="opacity:0.8;">[' + srcLabel + ']</span> ' + srcDetail + '</td>' +
                '<td>' + modeLabel + '</td>' +
                '<td>' + Number(b.chapterCount || 0) + ' 章</td>' +
                '<td style="font-size: 12px;">' + this.escape(this.formatTime(b.syncedAt)) + '</td>' +
                '<td class="is-right"><div class="console-row-actions is-right">' +
                '<a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.bookList.syncBook(this.dataset.id)">同步目录</a>' +
                '<a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.bookList.copyBookShortcode(this.dataset.id)">复制短代码</a>' +
                '<a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.bookList.editBook(this.dataset.id)">编辑</a>' +
                '<a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.bookList.deleteBook(this.dataset.id)">删除</a>' +
                '</div></td></tr>'
        }
        tbody.html(html)
    },

    copyShortcode: function (text) {
        if (typeof Util !== 'undefined' && Util.copyCodeText) {
            Util.copyCodeText(text).then(function (ok) {
                admin.showNotice(ok ? ('已复制短代码：' + text) : ('请手动复制：' + text))
            })
            return
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(function () {
                admin.showNotice('已复制短代码：' + text)
            })
        } else {
            admin.showNotice('短代码：' + text)
        }
    },

    copyBookShortcode: function (id) {
        this.copyShortcode(':::book id="' + id + '" :::')
    },

    resetBookForm: function () {
        $('#bookFormHeading').text('新增书籍')
        $('#bookTitle').val('')
        $('#bookId').val('').prop('readonly', false)
        $('#bookEmbedMode').val('iframe')
        $('#bookSourceType').val('auto')
        $('#bookSourceUrl').val('')
        $('#bookSeriesId').val('')
        $('#bookCoverUrl').val('')
        $('#bookDescription').val('')
        $('#bookTocJson').val('')
        $('#bookManualTocDetails').prop('open', false)
        this.onSourceTypeChange()
    },

    editBook: function (id) {
        var target = null
        for (var i = 0; i < this.books.length; i++) {
            if (this.books[i].id === id) {
                target = this.books[i]
                break
            }
        }
        if (!target) return
        $('#bookFormHeading').text('编辑书籍：' + target.title)
        $('#bookTitle').val(target.title || '')
        $('#bookId').val(target.id || '').prop('readonly', true)
        $('#bookEmbedMode').val(target.embedMode || 'iframe')
        $('#bookSourceType').val(target.sourceType || 'auto')
        $('#bookSourceUrl').val(target.sourceUrl || '')
        $('#bookSeriesId').val(target.seriesId || '')
        $('#bookCoverUrl').val(target.coverUrl || '')
        $('#bookDescription').val(target.description || '')
        $('#bookTocJson').val('')
        this.onSourceTypeChange()

        $.ajax({
            url: Label.servePath + '/console/books/' + encodeURIComponent(id) + '/toc',
            type: 'GET',
            cache: false,
            success: function (res) {
                if (res && res.sc && Array.isArray(res.toc) && res.toc.length > 0) {
                    $('#bookTocJson').val(JSON.stringify(res.toc, null, 2))
                }
            }
        })
        $('#bookTitle').focus()
    },

    saveBook: function () {
        var that = this
        var title = $.trim($('#bookTitle').val())
        if (!title) {
            admin.showNotice('请填写书籍名称', 'error')
            $('#bookTitle').focus()
            return
        }
        var payload = {
            id: $.trim($('#bookId').val()),
            title: title,
            embedMode: $('#bookEmbedMode').val(),
            sourceType: $('#bookSourceType').val(),
            sourceUrl: $.trim($('#bookSourceUrl').val()),
            seriesId: $('#bookSeriesId').val() || '',
            coverUrl: $.trim($('#bookCoverUrl').val()),
            description: $.trim($('#bookDescription').val())
        }
        var rawToc = $.trim($('#bookTocJson').val())
        if (rawToc) {
            try {
                var parsed = JSON.parse(rawToc)
                if (!Array.isArray(parsed)) {
                    admin.showNotice('手动章节目录 JSON 必须是数组格式', 'error')
                    return
                }
                payload.toc = parsed
            } catch (e) {
                admin.showNotice('手动章节目录 JSON 解析失败：' + e.message, 'error')
                return
            }
        }

        $('#saveBookBtn').prop('disabled', true)
        $('#loadMsg').text(Label.loadingLabel || 'Saving...')
        $.ajax({
            url: Label.servePath + '/console/books',
            type: 'POST',
            contentType: 'application/json;charset=UTF-8',
            data: JSON.stringify(payload),
            success: function (res) {
                if (!res || !res.sc) {
                    admin.showNotice((res && res.msg) || '保存书籍失败', 'error')
                    return
                }
                admin.showNotice(res.msg || '书籍配置已保存')
                var savedBook = res.book
                that.resetBookForm()
                // Auto-sync immediately if external URL book has 0 chapters and no manual TOC was supplied
                if (savedBook && savedBook.id && !rawToc && savedBook.sourceType !== 'series' && savedBook.sourceType !== 'manual' && Number(savedBook.chapterCount || 0) === 0) {
                    that.syncBook(savedBook.id)
                } else {
                    that.refresh()
                }
            },
            complete: function () {
                $('#saveBookBtn').prop('disabled', false)
                $('#loadMsg').text('')
            }
        })
    },

    syncBook: function (id) {
        var that = this
        $('#loadMsg').text('正在抓取并解析章节目录...')
        $.ajax({
            url: Label.servePath + '/console/books/' + encodeURIComponent(id) + '/sync',
            type: 'POST',
            contentType: 'application/json;charset=UTF-8',
            data: '{}',
            timeout: 30000,
            success: function (res) {
                if (!res || !res.sc) {
                    admin.showNotice((res && res.msg) || '同步章节目录失败', 'error')
                    return
                }
                admin.showNotice(res.msg || '目录同步成功')
                that.refresh()
            },
            complete: function () {
                $('#loadMsg').text('')
            }
        })
    },

    deleteBook: function (id) {
        var that = this
        if (!confirm('确定要从在线书架移除书籍 [' + id + '] 吗？')) return
        $.ajax({
            url: Label.servePath + '/console/books/' + encodeURIComponent(id),
            type: 'DELETE',
            success: function (res) {
                if (!res || !res.sc) {
                    admin.showNotice((res && res.msg) || '删除失败', 'error')
                    return
                }
                admin.showNotice(res.msg || '书籍已删除')
                that.refresh()
            }
        })
    },

    // ========================== Ordered Series ==========================

    renderSeries: function () {
        var tbody = $('#voloSeriesTableBody')
        if (!this.series.length) {
            tbody.html('<tr><td colspan="4" class="console-simple-table__empty">暂无专题系列。可在下方创建新系列并编排文章顺序。</td></tr>')
            return
        }
        var html = ''
        for (var i = 0; i < this.series.length; i++) {
            var s = this.series[i]
            var safeId = this.escape(s.id)
            var count = Array.isArray(s.articleIds) ? s.articleIds.length : 0
            html += '<tr>' +
                '<td><strong>' + this.escape(s.title) + '</strong><br/><code style="font-size:12px;opacity:0.75;">' + safeId + '</code></td>' +
                '<td>' + this.escape(s.description || '—') + '</td>' +
                '<td>' + count + ' 篇</td>' +
                '<td class="is-right"><div class="console-row-actions is-right">' +
                '<a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.bookList.editSeries(this.dataset.id)">编辑与排序</a>' +
                '<a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.bookList.deleteSeries(this.dataset.id)">删除</a>' +
                '</div></td></tr>'
        }
        tbody.html(html)
    },

    resetSeriesForm: function () {
        $('#seriesFormHeading').text('新建专题系列')
        $('#seriesEditId').val('')
        $('#seriesTitle').val('')
        $('#seriesDescription').val('')
        this.editingSeriesArticles = []
        this.renderSeriesArticleOrder()
    },

    editSeries: function (id) {
        var target = null
        for (var i = 0; i < this.series.length; i++) {
            if (this.series[i].id === id) {
                target = this.series[i]
                break
            }
        }
        if (!target) return
        $('#seriesFormHeading').text('编辑专题系列：' + target.title)
        $('#seriesEditId').val(target.id)
        $('#seriesTitle').val(target.title || '')
        $('#seriesDescription').val(target.description || '')
        this.editingSeriesArticles = Array.isArray(target.articleIds) ? target.articleIds.slice() : []
        this.renderSeriesArticleOrder()
        $('#seriesTitle').focus()
    },

    addPickedArticleToSeries: function () {
        var aid = $('#seriesArticlePicker').val()
        if (!aid) return
        if (this.editingSeriesArticles.indexOf(aid) === -1) {
            this.editingSeriesArticles.push(aid)
            this.renderSeriesArticleOrder()
        }
        $('#seriesArticlePicker').val('')
    },

    moveSeriesArticle: function (idx, delta) {
        var next = idx + delta
        if (next < 0 || next >= this.editingSeriesArticles.length) return
        var tmp = this.editingSeriesArticles[idx]
        this.editingSeriesArticles[idx] = this.editingSeriesArticles[next]
        this.editingSeriesArticles[next] = tmp
        this.renderSeriesArticleOrder()
    },

    removeSeriesArticle: function (idx) {
        this.editingSeriesArticles.splice(idx, 1)
        this.renderSeriesArticleOrder()
    },

    renderSeriesArticleOrder: function () {
        var box = $('#seriesArticleOrderList')
        if (!box.length) return
        if (!this.editingSeriesArticles.length) {
            box.html('<span style="opacity: 0.65; font-size: 13px;">暂未添加文章（也可在撰写/编辑文章页面直接选择所属系列）</span>')
            return
        }
        var html = '<ol style="margin: 0; padding-left: 22px;">'
        for (var i = 0; i < this.editingSeriesArticles.length; i++) {
            var aid = this.editingSeriesArticles[i]
            var title = this.articleMap[aid] || ('文章 ID: ' + aid)
            html += '<li><div class="console-series-order-item">' +
                '<span>' + this.escape(title) + '</span>' +
                '<span class="console-row-actions">' +
                '<a href="javascript:void(0)" onclick="admin.bookList.moveSeriesArticle(' + i + ', -1)" title="上移">上移</a>' +
                '<a href="javascript:void(0)" onclick="admin.bookList.moveSeriesArticle(' + i + ', 1)" title="下移">下移</a>' +
                '<a href="javascript:void(0)" onclick="admin.bookList.removeSeriesArticle(' + i + ')">移除</a>' +
                '</span></div></li>'
        }
        html += '</ol>'
        box.html(html)
    },

    saveSeries: function () {
        var that = this
        var title = $.trim($('#seriesTitle').val())
        if (!title) {
            admin.showNotice('请填写系列标题', 'error')
            $('#seriesTitle').focus()
            return
        }
        var payload = {
            id: $.trim($('#seriesEditId').val()),
            title: title,
            description: $.trim($('#seriesDescription').val()),
            articleIds: this.editingSeriesArticles.slice()
        }
        $('#saveSeriesBtn').prop('disabled', true)
        $.ajax({
            url: Label.servePath + '/console/series',
            type: 'POST',
            contentType: 'application/json;charset=UTF-8',
            data: JSON.stringify(payload),
            success: function (res) {
                if (!res || !res.sc) {
                    admin.showNotice((res && res.msg) || '保存系列失败', 'error')
                    return
                }
                admin.showNotice(res.msg || '专题系列已保存')
                that.resetSeriesForm()
                that.refresh()
            },
            complete: function () {
                $('#saveSeriesBtn').prop('disabled', false)
            }
        })
    },

    deleteSeries: function (id) {
        var that = this
        if (!confirm('确定要删除该专题系列吗？（不会删除系列内的文章本身）')) return
        $.ajax({
            url: Label.servePath + '/console/series/' + encodeURIComponent(id),
            type: 'DELETE',
            success: function (res) {
                if (!res || !res.sc) {
                    admin.showNotice((res && res.msg) || '删除系列失败', 'error')
                    return
                }
                admin.showNotice(res.msg || '系列已移除')
                that.refresh()
            }
        })
    }
}

admin.register['book-list'] = {
    obj: admin.bookList,
    init: admin.bookList.init,
    refresh: admin.bookList.refresh
}
