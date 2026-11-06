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
 * comment list for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* comment-list 相关操作 */
admin.commentList = {
  tablePagination: new TablePaginate('comment'),
  pageInfo: {
    currentPage: 1,
  },

  /*
   * 初始化 table, pagination, comments dialog
   */
  init: function (page) {
    this.tablePagination.buildTable([
      {
        text: Label.commentContentLabel,
        index: 'content',
        minWidth: 200,
      }, {
        text: Label.authorLabel,
        index: 'title',
        width: 200,
      }, {
        text: Label.dateLabel,
        index: 'date',
        width: 148,
      }, {
        text: '操作',
        index: 'actions',
        width: 184,
      }])
    this.tablePagination.initPagination()
    this.getList(page)
  },

  /*
   * 根据当前页码获取列表
   * @pagNum 当前页码
   */
  getList: function (pageNum) {
    var that = this
    $('#loadMsg').text(Label.loadingLabel)

    $.ajax({
      url: Label.servePath + '/console/comments/' + pageNum + '/' +
      Label.PAGE_SIZE + '/' + Label.WINDOW_SIZE + (function () {
        var kw = that.tablePagination.keywordQuery()
        var filterNode = typeof document !== 'undefined' && document.getElementById ? document.getElementById('commentStatusFilter') : null
        var st = filterNode && filterNode.value ? filterNode.value : 'all'
        if (!st || st === 'all') return kw
        return (kw ? kw + '&' : '?') + 'status=' + encodeURIComponent(st)
      })(),
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        that.pageInfo.currentPage = pageNum
        var badgeNode = typeof document !== 'undefined' && document.getElementById ? document.getElementById('commentPendingBadge') : null
        if (badgeNode) {
          var pCnt = Number(result.pendingCount || 0)
          badgeNode.textContent = pCnt > 0 ? ('当前有 ' + pCnt + ' 条待审核评论') : '暂无待审核评论'
        }
        var comments = result.comments,
          commentsData = []
        var escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]))
        for (var i = 0; i < comments.length; i++) {
          var type = 'Article'
          var cStatus = Number(comments[i].commentStatus || 0)
          var statusTag = cStatus === 1
            ? '<span class="console-totp-badge" data-state="paused" style="margin-right:6px;">待审核</span>'
            : (cStatus === 2 ? '<span class="console-totp-badge" data-state="unbound" style="margin-right:6px;">已驳回</span>' : '')
          commentsData[i] = {}
          commentsData[i].content = (statusTag ? '<div style="margin-bottom:4px;">' + statusTag + '</div>' : '') +
            '<div class="vditor-reset">' +
            comments[i].commentContent +
            '</div><span class=\'table-tag\'> on &nbsp;&nbsp;</span><a href=\'' +
            escape(Label.servePath + comments[i].commentSharpURL) +
            '\' target=\'_blank\' rel=\'noopener\'>' + escape(comments[i].commentTitle) +
            '</a>'

          var modActions = ''
          if (result.canModerate !== false && !comments[i].commentDeleted) {
            if (cStatus !== 0) {
              modActions += '<a class="console-row-action" href="#" data-comment-id="' + escape(comments[i].oId) + '" onclick="admin.commentList.moderate(this.dataset.commentId, 0); return false;">通过</a> '
            }
            if (cStatus !== 2) {
              modActions += '<a class="console-row-action" href="#" data-comment-id="' + escape(comments[i].oId) + '" onclick="admin.commentList.moderate(this.dataset.commentId, 2); return false;">驳回</a> '
            }
          }

          commentsData[i].actions = '<a class=\'console-row-action\' href="#" data-comment-id="' + escape(comments[i].oId) +
            '" onclick="admin.commentList.del(this.dataset.commentId, \'Article\'); return false;">' + Label.removeLabel +
            '</a>'

          if (!comments[i].commentDeleted) commentsData[i].actions = modActions + '<a class="console-row-action" href="#" data-comment-id="' + escape(comments[i].oId) + '" onclick="admin.commentList.edit(this.dataset.commentId); return false;">编辑</a> ' + commentsData[i].actions
          commentsData[i].actions += ' <a class="console-row-action" href="#" data-comment-id="' + escape(comments[i].oId) + '" onclick="admin.commentList.del(this.dataset.commentId, \'Article\', true); return false;">删除讨论</a>'

          var avatar = comments[i].commentThumbnailURL || ''
          if (!/^(?:https?:\/\/|\/(?!\/))/i.test(avatar)) avatar = ''
          commentsData[i].title = '<span class=\'console-comment-author\'><img class=\'small-head\' alt=\'\' data-avatar-seed=\'' +
            escape(comments[i].commentName) + '\' src=\'' + escape(avatar) + '\'/>'
          if (!/^https?:\/\//i.test(comments[i].commentURL) || 'http://' === comments[i].commentURL) {
            commentsData[i].title += '<span>' + escape(comments[i].commentName) + '</span>'
          } else {
            commentsData[i].title += '<a href=\'' + escape(comments[i].commentURL) +
              '\' target=\'_blank\' rel=\'noopener\' class=\'no-underline\'>' +
              escape(comments[i].commentName) +
              '</a>'
          }
          commentsData[i].title += '</span>'
          var email = String(comments[i].commentEmail || '').trim()
          if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
            commentsData[i].title += '<a class=\'console-comment-email\' title=\'' + escape(email) + '\' href=\'mailto:' + encodeURIComponent(email) + '\'>' + escape(email) + '</a>'
          }

          var address = comments[i].commentClientIP || ''
          if (address) {
            commentsData[i].title += '<span class="console-comment-address">' + escape(address) + (comments[i].commentRegion ? '<br>' + escape(comments[i].commentRegion) : '') + '</span>'
            commentsData[i].actions += ' <a class="console-row-action" href="#" data-comment-id="' + escape(comments[i].oId) + '" onclick="admin.commentList.ban(this.dataset.commentId); return false;">封禁 IP</a>'
          }
          commentsData[i].date = $.bowknot.getDate(comments[i].commentTime, 2)
        }

        that.tablePagination.updateTablePagination(commentsData, pageNum,
          result.pagination)
        Util.bindCommentAvatars(document.getElementById('commentTable'))

        Util.parseMarkdown()
        $('#loadMsg').text('')
      },
    })
  },

  /*
   * 删除评论
   * @id 评论 id
   * @type 评论类型：文章
   */
  edit: function (id) {
    $.ajax({url: Label.servePath + '/article/comments/' + encodeURIComponent(id), type: 'GET', cache: false,
      success: function (result) {
        if (!result.sc) { $('#tipMsg').text(result.msg); return }
        var dialog = document.createElement('dialog'), form = document.createElement('form'), title = document.createElement('h3'), area = document.createElement('textarea'), error = document.createElement('p'), actions = document.createElement('div'), cancel = document.createElement('button'), save = document.createElement('button')
        dialog.className = 'console-comment-edit'; title.textContent = '编辑评论'
        area.value = result.commentContent; area.maxLength = 500; area.setAttribute('aria-label', '评论内容'); area.rows = 8
        error.setAttribute('role', 'alert'); actions.className = 'console-comment-edit-actions'
        cancel.type = 'button'; cancel.textContent = '取消'; cancel.addEventListener('click', function () { dialog.close() })
        save.type = 'submit'; save.textContent = '保存修改'
        form.addEventListener('submit', function (event) {
          event.preventDefault()
          if (area.value.trim().length < 2 || area.value.length > 500) { error.textContent = '评论需要 2 到 500 个字符。'; return }
          save.disabled = true
          $.ajax({url: Label.servePath + '/article/comments/' + encodeURIComponent(id), type: 'PUT', contentType: 'application/json', data: JSON.stringify({commentContent: area.value}),
            complete: function () { save.disabled = false },
            success: function (saved) { if (saved.sc) { dialog.close(); admin.commentList.getList(admin.commentList.pageInfo.currentPage) } else error.textContent = saved.msg || '保存失败。' },
            error: function (xhr) { error.textContent = xhr.responseJSON?.msg || '保存失败，请重试。' }
          })
        })
        actions.append(cancel, save); form.append(title, area, error, actions); dialog.append(form)
        dialog.addEventListener('close', function () { dialog.remove() }); document.body.append(dialog); dialog.showModal(); area.focus()
      }, error: function (xhr) { $('#tipMsg').text(xhr.responseJSON?.msg || '无法读取评论。') }
    })
  },
  moderate: function (id, status) {
    $('#loadMsg').text(Label.loadingLabel)
    $.ajax({
      url: Label.servePath + '/console/comment/' + encodeURIComponent(id) + '/moderate',
      type: 'PUT',
      contentType: 'application/json',
      data: JSON.stringify({ status: status }),
      complete: function () { $('#loadMsg').text('') },
      success: function (result) {
        $('#tipMsg').text(result.msg || (result.sc ? '已更新审核状态。' : '操作失败。'))
        if (result.sc) {
          admin.commentList.getList(admin.commentList.pageInfo.currentPage)
        }
      },
      error: function (xhr) {
        $('#tipMsg').text(xhr.responseJSON?.msg || '审核操作失败，请重试。')
      },
    })
  },
  ban: function (id) {
    Util.chooseBanDuration().then(function (duration) {
      if (duration == null) return
      $.ajax({url: Label.servePath + '/console/ip-bans', type: 'POST', contentType: 'application/json',
        data: JSON.stringify({commentId: id, reason: '从评论封禁', durationSeconds: duration}),
        success: function (result) { $('#tipMsg').text(result.msg || (result.sc ? '已封禁。' : '封禁失败。')) },
        error: function (xhr) { $('#tipMsg').text(xhr.responseJSON?.msg || '封禁失败，请重试。') }
      })
    })
  },
  del: function (id, type, thread) {
    if (confirm(thread ? '删除这条评论所属的整个讨论（主评论及全部回复）？此操作无法撤销。' : '仅删除这条评论的内容？下级回复会保留。')) {
      $('#loadMsg').text(Label.loadingLabel)

      $.ajax({
        url: Label.servePath + '/console/' + type.toLowerCase() +
        '/comment/' + id + (thread ? '?scope=thread' : ''),
        type: 'DELETE',
        cache: false,
        success: function (result, textStatus) {
          $('#tipMsg').text(result.msg)
          if (!result.sc) {
            $('#loadMsg').text('')
            return
          }

          admin.commentList.getList(admin.commentList.pageInfo.currentPage)

          $('#loadMsg').text('')
        },
      })
    }
  },
}

/*
 * 注册到 admin 进行管理 
 */
admin.register['comment-list'] = {
  'obj': admin.commentList,
  'init': admin.commentList.init,
  'refresh': admin.commentList.getList,
}
