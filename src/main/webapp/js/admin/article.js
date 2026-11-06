/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/**
 * @fileoverview article for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */
admin.article = {
  // 当发文章，取消发布，更新文章时设置为 false。不需在离开编辑器时进行提示。
  isConfirm: true,
  status: {
    id: undefined,
    isArticle: undefined,
  },
  content: '',
  /**
   * @description 获取文章并把值塞入发布文章页面
   * @param {String} id 文章 id
   * @param {Boolean} isArticle 文章或者草稿
   */
  get: function (id, isArticle) {
    this.status.id = id
    this.status.isArticle = isArticle
    admin.selectTab('article/article')
  },
  /**
   * @description 获取文章内容
   */
  getAndSet: function () {
    $('#loadMsg').text(Label.loadingLabel)
    $('#tipMsg').text('')
    $.ajax({
      url: Label.servePath + '/console/article/' +
        admin.article.status.id,
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        // set default value for article.
        $('#title').val(result.article.articleTitle)
        admin.editors.articleEditor.setContent(result.article.articleContent)
        admin.editors.abstractEditor.setContent(result.article.articleAbstract)
        var loadedArticleId = result.article.oId || admin.article.status.id
        var articleEdWrapper = admin.editors.articleEditor
        admin.article.content = articleEdWrapper && articleEdWrapper.editorReady && typeof articleEdWrapper.getContent === 'function'
          ? articleEdWrapper.getContent()
          : (result.article.articleContent || '')
        admin.article._loadedServerTitle = String(result.article.articleTitle || '')
        admin.article._loadedServerContent = String(admin.article.content || '')
        if (articleEdWrapper && !articleEdWrapper.editorReady && articleEdWrapper.ready && typeof articleEdWrapper.ready.then === 'function') {
          articleEdWrapper.ready.then(function (ed) {
            if (ed && typeof ed.getValue === 'function' && !articleEdWrapper._userTyped &&
              admin.article.status && admin.article.status.id === loadedArticleId) {
              admin.article.content = ed.getValue()
              admin.article._loadedServerContent = admin.article.content
            }
          })
        }

        var tags = result.article.articleTags,
          tagsString = ''
        for (var i = 0; i < tags.length; i++) {
          if (0 === i) {
            tagsString = tags[i].tagTitle
          } else {
            tagsString += ',' + tags[i].tagTitle
          }
        }

        $('#tag').val(tagsString)
        admin.tagPicker.refresh()
        $('#permalink').val(result.article.articlePermalink)
        $('#viewPwd').val(result.article.articleViewPwd)
        $("#categorySelector").val(result.category);
        if (Array.isArray(result.seriesList)) {
          admin.article._populateSeriesOptions(result.seriesList, result.seriesId || '')
        } else {
          $('#seriesSelector').val(result.seriesId || '')
        }
        $('#audioUrl').val(result.article.articleAudioURL || '')
        $('#audioDuration').val(result.article.articleAudioDuration > 0 ? result.article.articleAudioDuration : '')
        $('#audioSize').val(result.article.articleAudioSize > 0 ? result.article.articleAudioSize : '')
        $('#articleAudioDetails').prop('open', Boolean(result.article.articleAudioURL))

        $('#articleCommentable').
          prop('checked', result.article.articleCommentable)

        // signs
        var signs = result.article.signs
        $('.signs button').each(function (i) {
          if (parseInt(result.article.articleSignId) ===
            parseInt(signs[i].oId)) {
            $('#articleSign' + signs[i].oId).addClass('selected')
          } else {
            $('#articleSign' + signs[i].oId).removeClass('selected')
          }
        })

        // Article create date
        function getNowFormatDate (date) {
          var y = date.getFullYear();
          var m = date.getMonth() + 1;
          m = m < 10 ? ('0' + m) : m;
          var d = date.getDate();
          d = d < 10 ? ('0' + d) : d;
          var h = date.getHours();
          h = h < 10 ? ('0' + h) : h;
          var minute = date.getMinutes();
          minute = minute < 10 ? ('0' + minute) : minute;
          var second = date.getSeconds();
          second = second < 10 ? ('0' + second) : second;
          return y + '-' + m + '-' + d + 'T' + h + ':' + minute + ':' + second;
        }

        var createDate = new Date();
        createDate.setTime(result.article.articleCreated);
        $('#createDate').val(getNowFormatDate(createDate));

        if (result.article.articleScheduleTime && Number(result.article.articleScheduleTime) > 0) {
          var schedDate = new Date()
          schedDate.setTime(Number(result.article.articleScheduleTime))
          $('#scheduleDate').val(getNowFormatDate(schedDate))
        } else {
          $('#scheduleDate').val('')
        }
        $('#shareTokenBox').hide()
        $('#articleRevisionsDrawer').hide()
        $('#articleRevisionDiffBox').hide()

        admin.article.setStatus()
        admin.article._checkLocalDraftRecovery(result.article)
        $('#loadMsg').text('')
      },
    })
  },
  /**
   * @description 删除文章
   * @param {String} id 文章 id
   * @param {String} fromId 文章来自草稿夹(draft)/文件夹(article)
   * @param {String} title 文章标题
   */
  del: function (id, fromId, title) {
    var isDelete = confirm(Label.confirmRemoveLabel + Label.articleLabel + '"' +
      Util.htmlDecode(title) + '"?')
    if (isDelete) {
      $('#loadMsg').text(Label.loadingLabel)
      $('#tipMsg').text('')

      $.ajax({
        url: Label.servePath + '/console/article/' + id,
        type: 'DELETE',
        cache: false,
        success: function (result, textStatus) {
          $('#tipMsg').text(result.msg)
          if (!result.sc) {
            $('#loadMsg').text('')
            return
          }

          var tableSelector = '#' + fromId + 'Table .table-body tr:not(.table-expendRow)'
          var rowCount = (typeof document !== 'undefined' && document && typeof document.querySelectorAll === 'function')
            ? document.querySelectorAll(tableSelector).length
            : $(tableSelector).length
          if (rowCount <= 1) {
            const refreshPage = Math.max(
              (admin[fromId + 'List'].tablePagination.currentPage - 1), 1)
            admin[fromId + 'List'].getList(refreshPage)
            admin.setHashByPage(refreshPage)
          } else {
            admin[fromId + 'List'].getList(
              admin[fromId + 'List'].tablePagination.currentPage)
          }
        },
      })
    }
  },
  /**
   * @@description 添加文章
   * @param {Boolean} articleStatus 0：已发布，1：草稿
   */
  add: function (articleStatus) {
    if (admin.article.validate()) {
      var that = this
      that._addDisabled()

      $('#loadMsg').text(Label.loadingLabel)
      $('#tipMsg').text('')
      var signId = ''
      $('.signs button').each(function () {
        if (this.className === 'selected') {
          signId = this.id.substr(this.id.length - 1, 1)
        }
      })

      var articleContent = admin.editors.articleEditor.getContent(),
        articleAbstract = admin.editors.abstractEditor.getContent()

      if ($('#articleThumbnail').prop('checked')) {
        var thumbUrl = this._extractThumbnailUrl()
        if (thumbUrl) {
          articleContent = '![](' + thumbUrl + ')\n\n' + articleContent
        }
      }

      var requestJSONObject = {
        'article': {
          'articleTitle': $('#title').val(),
          'articleContent': articleContent,
          'articleAbstract': articleAbstract,
          'articleTags': this.trimUniqueArray($('#tag').val()).toString(),
          'articlePermalink': $('#permalink').val(),
          'articleStatus': articleStatus,
          'articleSignId': signId,
          'postToCommunity': $('#postToCommunity').prop('checked'),
          'articleCommentable': $('#articleCommentable').prop('checked'),
          'articleViewPwd': $('#viewPwd').val(),
          'category': $("#categorySelector").val(),
          'seriesId': $('#seriesSelector').val() || '',
          'articleAudioURL': $.trim($('#audioUrl').val() || ''),
          'articleAudioDuration': parseInt($('#audioDuration').val() || '0', 10) || 0,
          'articleAudioSize': parseInt($('#audioSize').val() || '0', 10) || 0,
        },
      }
      var created = new Date($('#createDate').val()).getTime();
      if (!isNaN(created)) {
        requestJSONObject.article.articleCreated = created;
      }
      var schedVal = $('#scheduleDate').val();
      var schedTime = schedVal ? new Date(schedVal).getTime() : 0;
      requestJSONObject.article.articleScheduleTime = (!isNaN(schedTime) && schedTime > 0) ? schedTime : 0;

      $.ajax({
        url: Label.servePath + '/console/article/',
        type: 'POST',
        cache: false,
        data: JSON.stringify(requestJSONObject),
        success: function (result) {
          $('#tipMsg').text(result.msg)
          if (!result.sc) {
            return
          }

          admin.article._clearLocalDraft(admin.article.status.id || 'new')
          admin.article.status.id = undefined
          if (articleStatus === 0 && !(requestJSONObject.article.articleScheduleTime > Date.now())) {
            admin.selectTab('article/article-list')
          } else {
            admin.selectTab('article/draft-list')
          }

          admin.article.isConfirm = false
        },
        complete: function (jqXHR, textStatus) {
          that._removeDisabled()
          $('#loadMsg').text('')

        },
      })
    }
  },
  /**
   * @description 更新文章
   * @param {Boolean} articleStatus 0：已发布，1：草稿
   */
  update: function (articleStatus) {
    if (admin.article.validate()) {
      var that = this
      that._addDisabled()

      $('#loadMsg').text(Label.loadingLabel)
      $('#tipMsg').text('')
      var signId = ''
      $('.signs button').each(function () {
        if (this.className === 'selected') {
          signId = this.id.substr(this.id.length - 1, 1)
        }
      })

      var articleContent = admin.editors.articleEditor.getContent(),
        articleAbstract = admin.editors.abstractEditor.getContent()
      if ($('#articleThumbnail').prop('checked')) {
        var thumbUrl = this._extractThumbnailUrl()
        if (thumbUrl) {
          articleContent = '![](' + thumbUrl + ') \n\n' + articleContent
        }
      }
      var requestJSONObject = {
        'article': {
          'oId': this.status.id,
          'articleTitle': $('#title').val(),
          'articleContent': articleContent,
          'articleAbstract': articleAbstract,
          'articleTags': this.trimUniqueArray($('#tag').val()).toString(),
          'articlePermalink': $('#permalink').val(),
          'articleStatus': articleStatus,
          'articleSignId': signId,
          'articleCommentable': $('#articleCommentable').prop('checked'),
          'articleViewPwd': $('#viewPwd').val(),
          'postToCommunity': $('#postToCommunity').prop('checked'),
          'category': $("#categorySelector").val(),
          'seriesId': $('#seriesSelector').val() || '',
          'articleAudioURL': $.trim($('#audioUrl').val() || ''),
          'articleAudioDuration': parseInt($('#audioDuration').val() || '0', 10) || 0,
          'articleAudioSize': parseInt($('#audioSize').val() || '0', 10) || 0,
          'articleCreated': new Date($('#createDate').val()).getTime(),
          'articleScheduleTime': (function () {
            var sv = $('#scheduleDate').val();
            var st = sv ? new Date(sv).getTime() : 0;
            return (!isNaN(st) && st > 0) ? st : 0;
          })(),
        },
      }

      $.ajax({
        url: Label.servePath + '/console/article/',
        type: 'PUT',
        cache: false,
        data: JSON.stringify(requestJSONObject),
        success: function (result, textStatus) {
          $('#tipMsg').text(result.msg)
          if (!result.sc) {
            return
          }

          if (articleStatus === 0 && !(requestJSONObject.article.articleScheduleTime > Date.now())) {
            admin.selectTab('article/article-list')
          } else {
            admin.selectTab('article/draft-list')
          }

          $('#tipMsg').text(Label.updateSuccLabel || '更新成功')

          admin.article._clearLocalDraft(admin.article.status.id || 'new')
          admin.article.status.id = undefined
          admin.article.isConfirm = false

        },
        complete: function (jqXHR, textStatus) {
          that._removeDisabled()
          $('#loadMsg').text('')

        },
      })
    }
  },
  /**
   * @description 发布文章页面设置文章按钮、发布到社区等状态的显示
   */
  setStatus: function () {
    $.ajax({// Gets all tags
      url: Label.servePath + '/console/tags',
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        admin.tagPicker.setTags(result.tags)

        $('#loadMsg').text('')
      },
    })

    this._loadSeriesCatalog()
    if (this.status && this.status.id) {
      $('#articleAdvancedTools').show()
    } else {
      $('#articleAdvancedTools').hide()
    }

    // set button status
    if (this.status) {
      if (this.status.isArticle) {
        $('#unSubmitArticle').show()
        $('#saveArticle').hide()
        $('#submitArticle').show()
      } else {
        $('#submitArticle').show()
        $('#unSubmitArticle').hide()
        $('#saveArticle').show()
      }
    } else {
      $('#submitArticle').show()
      $('#unSubmitArticle').hide()
      $('#saveArticle').show()
      $('#postToCommunityPanel').show()
    }
  },
  /**
   * @description 清除发布文章页面的输入框的内容
   */
  clear: function () {
    this.status = {
      id: undefined,
      isArticle: undefined,
    }
    this.setStatus()

    $('#title').val('')

    admin.editors.articleEditor.setContent('')
    admin.editors.abstractEditor.setContent('')

    // reset tag
    $('#tag').val('')
    admin.tagPicker.reset()

    $('#permalink').val('')
    $('#viewPwd').val('')
    $('#categorySelector').val('')
    $('#seriesSelector').val('')
    $('#createDate').val('')
    $('#scheduleDate').val('')
    $('#audioUrl').val('')
    $('#audioDuration').val('')
    $('#audioSize').val('')
    $('#articleAudioDetails').prop('open', false)
    $('#articleAdvancedTools').hide()
    $('#shareTokenBox').hide()
    $('#articleRevisionsDrawer').hide()
    $('#articleRevisionDiffBox').hide()
    $('#articleTagInput').val('')
    this._loadedServerTitle = ''
    this._loadedServerContent = ''
    this._checkLocalDraftRecovery(null)
    this.content = ''
    $('#articleCommentable').prop('checked', true)
    $('#postToCommunity').prop('checked', false)
    $('.signs button').each(function (i) {
      if (i === 0) {
        this.className = 'selected'
      } else {
        this.className = ''
      }
    })

    if ($('#articleThumbnail').prop('checked')) {
      $('#articleThumbnail').click()
    }
  },
  /**
   * @description 初始化发布文章页面
   */
  init: function (fun) {
    $('#submitArticle,#saveArticle,#unSubmitArticle').prop('disabled', true)
    admin.tagPicker.init()
    // Inits Signs.
    $('.signs button').click(function (i) {
      $('.signs button').removeClass('selected')
      $(this).addClass('selected')
    })

    $('#tipMsg').text(Label.uploadMsg)

    // Load safe, keyboard-accessible tag choices below the input.
    $.ajax({// Gets all tags
      url: Label.servePath + '/console/tags',
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        admin.tagPicker.setTags(result.tags)

        $('#loadMsg').text('')
      },
    })
    // submit action
    $('#submitArticle').click(function () {
      if (admin.article.status.id) {
        admin.article.update(0)
      } else {
        admin.article.add(0)
      }
    })

    $('#saveArticle').click(function () {
      if (admin.article.status.id) {
        admin.article.update(admin.article.status.isArticle ? 0 : 1)
      } else {
        admin.article.add(1)
      }
    })

    $('#cancelDo').click(function () {
      history.go(-1);
    })

    // editor
    admin.editors.articleEditor = new SoloEditor({
      outline: true,
      id: 'articleContent',
      height: 500,
      fun: fun,
      onInput: function () { admin.article._scheduleDraftSnapshot() },
      previewMode: 'both',
      resize: false,
      typewriterMode: true,
    })

    admin.editors.abstractEditor = new SoloEditor({
      id: 'abstract',
      height: 200,
      previewMode: 'editor',
      resize: true,
      typewriterMode: false,
      onInput: function () { admin.article._scheduleDraftSnapshot() },
    })
    Promise.all([admin.editors.articleEditor.ready, admin.editors.abstractEditor.ready]).then(editors => {
      if (editors.every(Boolean)) $('#submitArticle,#saveArticle,#unSubmitArticle').prop('disabled', false)
      $('#title,#permalink,#tag').off('input.voloDraft').on('input.voloDraft', function () {
        admin.article._scheduleDraftSnapshot()
      })
      if (!admin.article.status || !admin.article.status.id) {
        admin.article._checkLocalDraftRecovery(null)
      }
    })

    // thumbnail
    $('#articleThumbnailBtn').click(function () {
      $.ajax({// Gets all tags
        url: Label.servePath + '/console/thumbs?n=1&w=768&h=432',
        type: 'GET',
        cache: false,
        success: function (result, textStatus) {
          if (!result.sc) {
            $('#loadMsg').text(result.msg)
            return
          }

          $('#articleThumbnailBtn').
            prev().
            css('background-image', 'url(' + result.data[0] + ')')
        },
      })
    }).click()
  },
  /**
   * @description 验证发布文章字段的合法性
   */
  validate: function () {
    var articleContent = admin.editors.articleEditor.getContent()

    if ($('#title').val().replace(/\s/g, '') === '') {
      $('#tipMsg').text(Label.titleEmptyLabel)
      $('#title').focus().val('')
    } else if (articleContent.replace(/\s/g, '') === '') {
      $('#tipMsg').text(Label.contentEmptyLabel)
    } else {
      return true
    }
    return false
  },
  /**
   * @description 取消发布
   */
  unPublish: function () {
    if (confirm('移至草稿箱将导致本次对文章的修改丢失，建议先点击\"发布\"将文章保存，再移至草稿箱。你确定要继续吗？')) {
      var that = this
      that._addDisabled()
      $.ajax({
        url: Label.servePath + '/console/article/unpublish/' +
          admin.article.status.id,
        type: 'PUT',
        cache: false,
        success: function (result, textStatus) {
          $('#tipMsg').text(result.msg)
          if (!result.sc) {
            return
          }

          admin.selectTab('article/draft-list')
          admin.article._clearLocalDraft(admin.article.status.id || 'new')
          admin.article.status.id = undefined
          admin.article.isConfirm = false
        },
        complete: function (jqXHR, textStatus) {
          that._removeDisabled()
          $('#loadMsg').text('')
        },
      })
    }
  },
  /**
   * @description 数组中无重复
   * @param {String} str 被解析的字符串
   * @returns {String} 无重复的字符串
   */
  _formatDate: function (value, mode) {
    if (typeof Util !== 'undefined' && Util && typeof Util.formatDate === 'function') {
      return Util.formatDate(value, mode)
    }
    if (value === null || value === undefined || typeof value === 'boolean' || value === '') return '—'
    var d = value instanceof Date ? value : new Date(typeof value === 'string' && /^\d+$/.test(value.trim()) ? Number(value.trim()) : value)
    if (!Number.isFinite(d.getTime()) || d.getTime() < 0) return '—'
    var pad = function (n) { return (n < 10 ? '0' : '') + n }
    var ymd = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    if (mode === 1) return ymd + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
    if (mode === 2) return ymd + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
    return ymd
  },
  _extractThumbnailUrl: function () {
    var raw = String($('.thumbnail__img').css('background-image') || '').trim()
    if (!raw || raw.toLowerCase() === 'none') return ''
    var match = /^url\(\s*['"]?(.+?)['"]?\s*\)$/i.exec(raw)
    var url = match && match[1] ? match[1].trim() : ''
    if (!url || url.toLowerCase() === 'none') return ''
    return url.replace('w/768', 'w/960').replace('h/432', 'h/540')
  },
  trimUniqueArray: function (str) {
    var parts = String(str == null ? '' : str).split(',')
    var seen = new Set()
    var unique = []
    for (var i = 0; i < parts.length; i++) {
      var tag = parts[i].replace(/(^\s*)|(\s*$)/g, '')
      if (tag !== '' && !seen.has(tag)) {
        seen.add(tag)
        unique.push(tag)
      }
    }
    return unique.join(',')
  },
  /**
   * @description 点击发文文章时的处理
   */
  prePost: function () {
    $('#loadMsg').text(Label.loadingLabel)
    admin.article.content = ''
    if (!admin.editors.articleEditor.getContent) {
      return
    }

    var articleContent = admin.editors.articleEditor.getContent()

    if (window.location.hash === '#article/article' &&
      articleContent.replace(/\s/g, '') !== '') {
      if (confirm(Label.editorPostLabel)) {
        admin.article.clear()
      }
    }
    $('#tipMsg').text('')
    $('#loadMsg').text('')
  },
  /**
   * @description: 仿重复提交，点击一次后，按钮设置为 disabled
   */
  _addDisabled: function () {
    $('#unSubmitArticle').attr('disabled', 'disabled')
    $('#saveArticle').attr('disabled', 'disabled')
    $('#submitArticle').attr('disabled', 'disabled')
  },
  /**
   * @description: 仿重复提交，当后台有数据返回后，按钮移除 disabled 状态
   */
  _removeDisabled: function () {
    $('#unSubmitArticle').removeAttr('disabled')
    $('#saveArticle').removeAttr('disabled')
    $('#submitArticle').removeAttr('disabled')
  },
  _populateSeriesOptions: function (list, selectedId) {
    var sel = $('#seriesSelector')
    if (!sel.length) return
    var keep = selectedId !== undefined ? selectedId : (sel.val() || '')
    sel.empty().append($('<option value="">不加入专题系列</option>'))
    for (var i = 0; i < list.length; i++) {
      var s = list[i]
      sel.append($('<option></option>').val(s.id).text(s.title))
    }
    sel.val(keep || '')
  },
  _loadSeriesCatalog: function () {
    var that = this
    $.ajax({
      url: Label.servePath + '/console/series',
      type: 'GET',
      cache: false,
      success: function (res) {
        if (res && res.sc && Array.isArray(res.series)) {
          that._populateSeriesOptions(res.series)
        }
      },
    })
  },
  generateShareToken: function () {
    if (!this.status || !this.status.id) return
    var duration = $('#shareTokenDuration').val() || '24h'
    $.ajax({
      url: Label.servePath + '/console/article/' + encodeURIComponent(this.status.id) + '/share-token',
      type: 'POST',
      contentType: 'application/json;charset=UTF-8',
      data: JSON.stringify({ action: 'create', duration: duration }),
      success: function (res) {
        if (!res || !res.sc) {
          $('#tipMsg').text((res && res.msg) || '生成预览链接失败')
          return
        }
        $('#shareTokenOutput').val(res.shareUrl || '')
        $('#shareTokenBox').show()
        $('#tipMsg').text(res.msg || '已生成限时免密预览链接')
      },
    })
  },
  copyShareToken: function () {
    var val = $('#shareTokenOutput').val()
    if (!val) return
    if (typeof Util !== 'undefined' && Util.copyCodeText) {
      Util.copyCodeText(val).then(function (ok) {
        $('#tipMsg').text(ok ? '预览链接已复制到剪贴板' : '请手动复制输入框中的链接')
      })
    } else if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(val).then(function () {
        $('#tipMsg').text('预览链接已复制到剪贴板')
      })
    }
  },
  revokeShareTokens: function () {
    if (!this.status || !this.status.id) return
    if (!confirm('重置全局预览密钥后，所有已分享的草稿/密码免密预览链接将立即失效。确定继续吗？')) return
    $.ajax({
      url: Label.servePath + '/console/article/' + encodeURIComponent(this.status.id) + '/share-token',
      type: 'POST',
      contentType: 'application/json;charset=UTF-8',
      data: JSON.stringify({ action: 'revoke' }),
      success: function (res) {
        $('#shareTokenBox').hide()
        $('#tipMsg').text((res && res.msg) || '已吊销所有预览链接')
      },
    })
  },
  toggleRevisions: function () {
    var drawer = $('#articleRevisionsDrawer')
    if (drawer.is(':visible')) {
      drawer.slideUp(150)
      return
    }
    drawer.slideDown(150)
    this.loadRevisions()
  },
  loadRevisions: function () {
    if (!this.status || !this.status.id) return
    var box = $('#articleRevisionsList')
    box.html('正在加载历史版本...')
    $('#articleRevisionDiffBox').hide()
    var that = this
    var aid = this.status.id
    var esc = (typeof Util !== 'undefined' && Util.escapeHtml) || function (v) {
      return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
      })
    }
    $.ajax({
      url: Label.servePath + '/console/article/' + encodeURIComponent(aid) + '/revisions',
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!res || !res.sc || !Array.isArray(res.revisions) || res.revisions.length === 0) {
          box.html('<span style="opacity:0.7;">暂无历史快照（每次保存或更新文章时自动保留最近 10 个版本）。</span>')
          return
        }
        var html = '<ul style="list-style:none;margin:0;padding:0;">'
        for (var i = 0; i < res.revisions.length; i++) {
          var r = res.revisions[i]
          var revTs = Number(r.revisionCreated || 0)
          var timeStr = revTs > 0 ? that._formatDate(revTs, 2) : '—'
          var revId = esc(r.oId)
          html += '<li style="display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid rgba(127,127,127,0.14);gap:8px;flex-wrap:wrap;">' +
            '<span><strong>' + esc(timeStr) + '</strong> · ' + esc(r.revisionTitle || '') +
            (r.revisionAuthorName ? ' <em style="opacity:0.75;">(' + esc(r.revisionAuthorName) + ')</em>' : '') +
            (r.revisionNote ? ' <span style="opacity:0.7;">[' + esc(r.revisionNote) + ']</span>' : '') +
            '</span>' +
            '<span class="console-row-actions">' +
            '<a href="javascript:void(0)" data-rev="' + revId + '" onclick="admin.article.viewRevisionDiff(this.dataset.rev)">对比差异</a>' +
            '<a href="javascript:void(0)" data-rev="' + revId + '" onclick="admin.article.restoreRevision(this.dataset.rev)">一键回滚</a>' +
            '</span></li>'
        }
        html += '</ul>'
        box.html(html)
      },
    })
  },
  viewRevisionDiff: function (revId) {
    if (!this.status || !this.status.id || !revId) return
    var that = this
    $.ajax({
      url: Label.servePath + '/console/article/' + encodeURIComponent(this.status.id) + '/revisions/' + encodeURIComponent(revId),
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!res || !res.sc || !res.revision) {
          $('#tipMsg').text((res && res.msg) || '加载历史快照失败')
          return
        }
        var oldContent = String(res.revision.revisionContent || '')
        var curContent = admin.editors.articleEditor.getContent() || ''
        var diffHtml = that._buildLineDiffHtml(oldContent, curContent)
        $('#articleRevisionDiffBox').html(diffHtml).show()
      },
    })
  },
  _buildLineDiffHtml: function (oldText, newText) {
    var esc = function (v) {
      return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
      })
    }
    var oldLines = oldText.replace(/\r\n/g, '\n').split('\n')
    var newLines = newText.replace(/\r\n/g, '\n').split('\n')
    var max = Math.max(oldLines.length, newLines.length)
    var out = ['<div style="margin-bottom:6px;font-weight:bold;">版本差异对比（<span style="color:#dc2626;">- 历史快照</span> / <span style="color:#16a34a;">+ 当前编辑器</span>）：</div>']
    var diffCount = 0
    for (var i = 0; i < max && out.length < 400; i++) {
      var o = oldLines[i]
      var n = newLines[i]
      if (o === n) continue
      diffCount++
      if (o !== undefined) {
        out.push('<div style="color:#dc2626;background:rgba(220,38,38,0.08);padding:1px 4px;">- [L' + (i + 1) + '] ' + esc(o) + '</div>')
      }
      if (n !== undefined) {
        out.push('<div style="color:#16a34a;background:rgba(22,163,74,0.08);padding:1px 4px;">+ [L' + (i + 1) + '] ' + esc(n) + '</div>')
      }
    }
    if (diffCount === 0) {
      out.push('<div style="opacity:0.75;">当前编辑器正文与该历史快照完全一致。</div>')
    }
    return out.join('')
  },
  _loadedServerTitle: '',
  _loadedServerContent: '',
  _draftTimer: null,
  _draftStoragePrefix: 'volo.draftSnapshot.',
  _escapeAttr: function (v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    })
  },
  _getDraftKey: function (idOverride) {
    var keyId = idOverride !== undefined ? idOverride : (this.status && this.status.id)
    return this._draftStoragePrefix + (keyId || 'new')
  },
  _getLocalStorage: function () {
    try {
      if (typeof window !== 'undefined' && window.localStorage) return window.localStorage
    } catch (e) {}
    return null
  },
  _scheduleDraftSnapshot: function () {
    var that = this
    if (this._draftTimer) clearTimeout(this._draftTimer)
    this._draftTimer = setTimeout(function () {
      that._draftTimer = null
      that._saveLocalDraftSnapshot()
    }, 2500)
  },
  _saveLocalDraftSnapshot: function () {
    var store = this._getLocalStorage()
    if (!store || !admin.editors || !admin.editors.articleEditor || typeof admin.editors.articleEditor.getContent !== 'function') return
    var title = String($('#title').val() || '')
    var content = String(admin.editors.articleEditor.getContent() || '')
    if (!title.replace(/\s+/g, '') && !content.replace(/\s+/g, '')) return
    if (title === this._loadedServerTitle && content === this._loadedServerContent) return
    var abstractContent = (admin.editors.abstractEditor && typeof admin.editors.abstractEditor.getContent === 'function')
      ? String(admin.editors.abstractEditor.getContent() || '') : ''
    var key = this._getDraftKey()
    var payload = {
      title: title,
      content: content,
      abstractContent: abstractContent,
      tags: String($('#tag').val() || ''),
      permalink: String($('#permalink').val() || ''),
      category: String($('#categorySelector').val() || ''),
      seriesId: String($('#seriesSelector').val() || ''),
      updatedAt: Date.now(),
    }
    try {
      store.setItem(key, JSON.stringify(payload))
      this._pruneDraftSnapshots(store)
    } catch (e) {}
  },
  _pruneDraftSnapshots: function (store) {
    var entries = []
    for (var i = 0; i < store.length; i++) {
      var k = store.key(i)
      if (k && k.indexOf(this._draftStoragePrefix) === 0) {
        var ts = 0
        try {
          var parsed = JSON.parse(store.getItem(k) || '{}')
          ts = Number(parsed.updatedAt || 0)
        } catch (e) {}
        entries.push({ key: k, updatedAt: ts })
      }
    }
    if (entries.length <= 10) return
    entries.sort(function (a, b) { return b.updatedAt - a.updatedAt })
    for (var j = 10; j < entries.length; j++) {
      store.removeItem(entries[j].key)
    }
  },
  _checkLocalDraftRecovery: function (serverArticle) {
    var bar = $('#articleDraftRecoveryBar')
    var store = this._getLocalStorage()
    if (!store || !bar.length) return
    var raw = store.getItem(this._getDraftKey())
    if (!raw) {
      bar.hide()
      return
    }
    try {
      var snap = JSON.parse(raw)
      var sTitle = serverArticle ? String(serverArticle.articleTitle || '') : ''
      var sContent = serverArticle ? String(serverArticle.articleContent || '') : ''
      var snapTitle = String((snap && snap.title) || '')
      var snapContent = String((snap && snap.content) || '')
      if ((!snapTitle.replace(/\s+/g, '') && !snapContent.replace(/\s+/g, '')) ||
          (snapTitle === sTitle && snapContent === sContent)) {
        bar.hide()
        return
      }
      var snapTs = Number(snap.updatedAt || 0)
      var snapFmt = snapTs > 0 ? this._formatDate(snapTs, 2) : '—'
      var timeText = snapFmt !== '—' ? ('保存于 ' + snapFmt) : ''
      var previewTitle = snapTitle ? ('《' + snapTitle.slice(0, 32) + '》 · ') : ''
      $('#articleDraftRecoveryMeta').text(previewTitle + timeText + ' · 正文 ' + snapContent.length + ' 字')
      bar.show()
    } catch (e) {
      bar.hide()
    }
  },
  restoreLocalDraft: function () {
    var store = this._getLocalStorage()
    if (!store) return
    var raw = store.getItem(this._getDraftKey())
    if (!raw) return
    try {
      var snap = JSON.parse(raw)
      if (snap.title !== undefined) $('#title').val(snap.title)
      if (snap.content !== undefined && admin.editors.articleEditor) {
        admin.editors.articleEditor.setContent(snap.content)
      }
      if (snap.abstractContent !== undefined && admin.editors.abstractEditor) {
        admin.editors.abstractEditor.setContent(snap.abstractContent)
      }
      if (snap.tags !== undefined) {
        $('#tag').val(snap.tags)
        if (admin.tagPicker && typeof admin.tagPicker.refresh === 'function') admin.tagPicker.refresh()
      }
      if (snap.permalink !== undefined) $('#permalink').val(snap.permalink)
      if (snap.category !== undefined) $('#categorySelector').val(snap.category)
      if (snap.seriesId !== undefined) $('#seriesSelector').val(snap.seriesId)
      $('#articleDraftRecoveryBar').hide()
      if (admin.showNotice) admin.showNotice('已恢复本地草稿快照，请核对后保存或发布。')
      else $('#tipMsg').text('已恢复本地草稿快照，请核对后保存或发布。')
    } catch (e) {}
  },
  discardLocalDraft: function () {
    this._clearLocalDraft()
    if (admin.showNotice) admin.showNotice('已丢弃本地草稿快照。')
  },
  _clearLocalDraft: function (idOverride) {
    if (this._draftTimer) {
      clearTimeout(this._draftTimer)
      this._draftTimer = null
    }
    var store = this._getLocalStorage()
    if (store) {
      try { store.removeItem(this._getDraftKey(idOverride)) } catch (e) {}
    }
    $('#articleDraftRecoveryBar').hide()
  },
  openQuickInsert: function (tab) {
    $('#voloQuickInsertModal').show()
    this.switchQuickInsertTab(tab || 'media')
  },
  closeQuickInsert: function () {
    $('#voloQuickInsertModal').hide()
  },
  switchQuickInsertTab: function (tab) {
    var active = tab || 'media'
    $('#voloQuickInsertModal [data-qtab]').each(function () {
      var isCur = $(this).attr('data-qtab') === active
      $(this).toggleClass('is-active', isCur)
    })
    ;['media', 'books', 'lock', 'cite'].forEach(function (name) {
      var panel = $('#quickInsertPanel_' + name)
      if (name === active) panel.show()
      else panel.hide()
    })
    if (active === 'media') this.loadQuickMedia()
    else if (active === 'books') this.loadQuickBooks()
    else if (active === 'cite') this.loadQuickArticles()
  },
  insertQuickText: function (snippet) {
    if (!snippet) return
    var edWrapper = admin.editors && admin.editors.articleEditor
    if (edWrapper && typeof edWrapper.insertContent === 'function') {
      edWrapper.insertContent(snippet)
    } else if (edWrapper && edWrapper.editor && typeof edWrapper.editor.insertValue === 'function') {
      edWrapper.editor.insertValue(snippet)
    } else if (edWrapper && typeof edWrapper.setContent === 'function') {
      var cur = typeof edWrapper.getContent === 'function' ? edWrapper.getContent() : ''
      edWrapper.setContent((cur ? cur + '\n' : '') + snippet)
    }
    this.closeQuickInsert()
    this._scheduleDraftSnapshot()
  },
  loadQuickMedia: function () {
    var that = this
    var q = String($('#quickMediaSearchInput').val() || '').trim()
    var grid = $('#quickMediaGrid')
    grid.html('<p class="console-form-help">正在加载本地媒体库...</p>')
    $.ajax({
      url: Label.servePath + '/console/media/local?q=' + encodeURIComponent(q),
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!res || !res.sc || !res.configured) {
          grid.html('<p class="console-form-help">' + that._escapeAttr((res && res.msg) || '当前未启用本地图床或暂无媒体文件。') + '</p>')
          return
        }
        var items = Array.isArray(res.items) ? res.items : []
        if (!items.length) {
          grid.html('<p class="console-form-help">没有匹配的本地图片，可直接粘贴或拖拽图片到编辑器上传。</p>')
          return
        }
        var html = ''
        for (var i = 0; i < Math.min(items.length, 60); i++) {
          var it = items[i]
          var safeName = that._escapeAttr(it.name || 'image')
          var safeUrl = that._escapeAttr(it.url || '')
          var md = it.markdown || ('![' + (it.name || 'image') + '](' + (it.url || '') + ')')
          var safeMd = that._escapeAttr(md)
          html += '<div class="console-quick-media-item">' +
            '<div class="console-quick-media-item__thumb"><img src="' + safeUrl + '" alt="' + safeName + '" loading="lazy"/></div>' +
            '<div class="console-quick-media-item__name" title="' + safeName + '">' + safeName + '</div>' +
            '<button type="button" data-md="' + safeMd + '" onclick="admin.article.insertQuickText(this.getAttribute(\'data-md\') + \'\\n\')">插入图片</button>' +
            '</div>'
        }
        grid.html(html)
      },
    })
  },
  loadQuickBooks: function () {
    var that = this
    var box = $('#quickBooksList')
    box.html('<p class="console-form-help">正在加载书架列表...</p>')
    $.ajax({
      url: Label.servePath + '/console/books',
      type: 'GET',
      cache: false,
      success: function (res) {
        var books = (res && res.sc && Array.isArray(res.books)) ? res.books : []
        if (!books.length) {
          box.html('<p class="console-form-help">暂未添加单本书籍，您仍可直接点击上方“插入整页在线书架”或在“管理 → 书架与系列”中新建书籍。</p>')
          return
        }
        var html = ''
        for (var i = 0; i < books.length; i++) {
          var b = books[i]
          var safeTitle = that._escapeAttr(b.title || b.id)
          var safeId = that._escapeAttr(b.id || '')
          var shortcode = '\n:::book id="' + String(b.id || '').replace(/"/g, '') + '":::\n'
          html += '<div class="console-quick-row">' +
            '<div class="console-quick-row__main"><strong>' + safeTitle + '</strong><span><code>id="' + safeId + '"</code> · ' + Number(b.chapterCount || 0) + ' 章</span></div>' +
            '<button type="button" data-code="' + that._escapeAttr(shortcode) + '" onclick="admin.article.insertQuickText(this.getAttribute(\'data-code\'))">插入此书</button>' +
            '</div>'
        }
        box.html(html)
      },
    })
  },
  insertQuickLockBlock: function () {
    var type = $('#quickLockType').val() === 'user' ? 'user' : 'pwd'
    var pwd = String($('#quickLockPwd').val() || '').trim().replace(/"/g, '')
    var hint = String($('#quickLockHint').val() || '').trim().replace(/"/g, '’')
    var body = String($('#quickLockBody').val() || '').trim() || '在此输入受保护的正文内容...'
    if (type === 'pwd' && !pwd) {
      if (admin.showNotice) admin.showNotice('请填写局部解锁密码。', 'error')
      $('#quickLockPwd').focus()
      return
    }
    var header = ':::lock type="' + type + '"'
    if (type === 'pwd') header += ' pwd="' + pwd + '"'
    if (hint) header += ' hint="' + hint + '"'
    var block = '\n' + header + '\n' + body + '\n:::\n'
    $('#quickLockBody').val('')
    this.insertQuickText(block)
  },
  loadQuickArticles: function () {
    var that = this
    var kw = String($('#quickCiteSearchInput').val() || '').trim()
    var box = $('#quickCiteList')
    box.html('<p class="console-form-help">正在检索已发布文章...</p>')
    var qs = kw ? ('?k=' + encodeURIComponent(kw)) : ''
    $.ajax({
      url: Label.servePath + '/console/articles/status/published/1/25/10' + qs,
      type: 'GET',
      cache: false,
      success: function (res) {
        var articles = (res && res.sc && Array.isArray(res.articles)) ? res.articles : []
        if (!articles.length) {
          box.html('<p class="console-form-help">未找到匹配的已发布文章。</p>')
          return
        }
        var html = ''
        for (var i = 0; i < articles.length; i++) {
          var a = articles[i]
          var rawTitle = (typeof Util !== 'undefined' && Util.htmlDecode) ? Util.htmlDecode(a.articleTitle || '') : String(a.articleTitle || '')
          var cleanTitle = rawTitle.replace(/[\[\]]/g, '') || '站内文章'
          var permalink = String(a.articlePermalink || '')
          var fullPath = (Label.servePath || '') + (permalink.charAt(0) === '/' ? permalink : ('/' + permalink))
          var mdLink = '[' + cleanTitle + '](' + fullPath + ')'
          html += '<div class="console-quick-row">' +
            '<div class="console-quick-row__main"><strong>' + that._escapeAttr(rawTitle) + '</strong><span>' + that._escapeAttr(permalink) + '</span></div>' +
            '<button type="button" data-md="' + that._escapeAttr(mdLink) + '" onclick="admin.article.insertQuickText(this.getAttribute(\'data-md\'))">插入引用</button>' +
            '</div>'
        }
        box.html(html)
      },
    })
  },
  restoreRevision: function (revId) {
    if (!this.status || !this.status.id || !revId) return
    if (!confirm('确定回滚到该历史版本吗？回滚前系统会自动为您保存当前内容的最新快照。')) return
    var that = this
    $.ajax({
      url: Label.servePath + '/console/article/' + encodeURIComponent(this.status.id) + '/revisions/' + encodeURIComponent(revId) + '/restore',
      type: 'POST',
      contentType: 'application/json;charset=UTF-8',
      data: '{}',
      success: function (res) {
        if (!res || !res.sc) {
          $('#tipMsg').text((res && res.msg) || '回滚失败')
          return
        }
        $('#tipMsg').text(res.msg || '已回滚至所选历史版本')
        that.getAndSet()
      },
    })
  },
}

/**
 * @description 注册到 admin 进行管理
 */
admin.register.article = {
  'obj': admin.article,
  'init': admin.article.init,
  'refresh': function () {
    if (admin.article.status.id) {
      return
    }
    admin.editors.abstractEditor.setContent('')
    admin.editors.articleEditor.setContent('')
    $('#loadMsg').text('')
    $('#tipMsg').text(Label.uploadMsg)
  },
}
