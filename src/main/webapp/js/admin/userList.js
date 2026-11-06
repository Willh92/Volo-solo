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
 * user list for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* user-list 相关操作 */
admin.userList = {
  tablePagination: new TablePaginate('user'),
  pageInfo: {
    currentCount: 1,
    pageCount: 1,
    currentPage: 1,
  },
  userInfo: {
    'oId': '',
    'userRole': '',
  },
  /*
   * 初始化 table, pagination
   */
  init: function (page) {
    this.tablePagination.buildTable([
      {
        text: Label.userNameLabel,
        index: 'userName',
        minWidth: 140,
      }, {
        text: '验证邮箱',
        index: 'userEmail',
        width: 200,
      }, {
        text: Label.roleLabel,
        index: 'isAdmin',
        width: 96,
      }, {
        text: '状态',
        index: 'statusText',
        width: 84,
      }, {
        text: '操作',
        index: 'actions',
        width: 216,
      }])

    this.tablePagination.initPagination()
    this.getList(page)

    $('#userUpdate').dialog({
      title: $('#userUpdate').data('title'),
      width: 700,
      height: 450,
      'modal': true,
      'hideFooter': true,
    })
  },
  /*
   * 根据当前页码获取列表
   * @pagNum 当前页码
   */
  getList: function (pageNum) {
    $('#loadMsg').text(Label.loadingLabel)
    $('#tipMsg').text('')
    this.pageInfo.currentPage = pageNum
    var that = this

    var roleFilter = $('#userRoleFilter').val() || 'all'
    var keywordFilter = $.trim($('#userKeywordFilter').val() || '')
    $.ajax({
      url: Label.servePath + '/console/users/' + pageNum + '/' +
        Label.PAGE_SIZE + '/' + Label.WINDOW_SIZE +
        '?role=' + encodeURIComponent(roleFilter) +
        (keywordFilter ? '&keyword=' + encodeURIComponent(keywordFilter) : ''),
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        var users = Array.isArray(result.users) ? result.users : []
        var userData = []
        var pageInfo = result.pagination || {paginationPageCount: 1}
        admin.userList.pageInfo.currentCount = users.length
        admin.userList.pageInfo.pageCount = pageInfo.paginationPageCount || 1
        if (users.length < 1) {
          that.tablePagination.updateTablePagination([], pageNum, pageInfo)
          $('#loadMsg').text('')
          return
        }

        var escapeHtml = (typeof Util !== 'undefined' && Util.escapeHtml) || function (v) {
          return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]
          })
        }
        var encodeQuote = (typeof Util !== 'undefined' && Util.encodeSingleQuote) || function (v) {
          return encodeURIComponent(String(v == null ? '' : v)).replace(/'/g, '%27')
        }
        for (var i = 0; i < users.length; i++) {
          var safeId = encodeQuote(users[i].oId)
          var safeRole = encodeQuote(users[i].userRole)
          var encodedName = encodeQuote(users[i].userName)
          var emailVal = users[i].userEmail ? escapeHtml(users[i].userEmail) : '<span style="opacity:.55">—</span>'
          if (users[i].userEmail && users[i].userEmailVerified) {
            emailVal += ' <span class="console-badge" style="font-size:11px;padding:1px 6px;border-radius:99px;background:color-mix(in srgb, var(--bolo-accent) 14%, transparent);color:var(--bolo-accent);">已验证</span>'
          }
          var disabled = Number(users[i].userStatus || 0) === 1
          userData[i] = {}
          userData[i].userName = escapeHtml(typeof Util !== 'undefined' && Util.htmlDecode ? Util.htmlDecode(users[i].userName) : users[i].userName)
          userData[i].userEmail = emailVal
          userData[i].statusText = disabled
            ? '<span style="color:var(--bolo-danger);font-weight:600;">已禁用</span>'
            : '<span style="color:var(--bolo-accent);">正常</span>'

          if ('adminRole' === users[i].userRole) {
            userData[i].isAdmin = '&nbsp;' + Label.administratorLabel
            userData[i].actions = '<a class=\'console-row-action\' href=\'javascript:void(0)\' onclick="admin.userList.get(\'' +
              safeId + '\', \'' + safeRole + '\')">' +
              Label.updateLabel + '</a>'
          } else {
            var toggleLabel = disabled ? '解禁' : '禁用'
            var nextStatus = disabled ? 0 : 1
            userData[i].actions = '<a class=\'console-row-action\' href=\'javascript:void(0)\' onclick="admin.userList.get(\'' +
              safeId + '\', \'' + safeRole + '\')">' +
              Label.updateLabel + '</a> ' +
              '<a href=\'javascript:void(0)\' onclick="admin.userList.toggleStatus(\'' +
              safeId + '\', ' + nextStatus + ')">' + toggleLabel + '</a> ' +
              '<a href=\'javascript:void(0)\' onclick="admin.userList.del(\'' +
              safeId + '\', \'' + encodedName +
              '\')">' + Label.removeLabel + '</a> ' +
              '<a href=\'javascript:void(0)\' onclick="admin.userList.changeRole(\'' +
              safeId + '\')">' + Label.changeRoleLabel + '</a>'
            if ('defaultRole' === users[i].userRole) {
              userData[i].isAdmin = Label.commonUserLabel
            } else {
              userData[i].isAdmin = '注册读者'
            }
          }

        }
        that.tablePagination.updateTablePagination(userData, pageNum, result.pagination)
        $('#loadMsg').text('')
      },
    })
  },
  /*
   * 获取用户
   * @id 用户 id
   */
  get: function (id, userRole) {
    $('#loadMsg').text(Label.loadingLabel)
    $('#tipMsg').text('')
    $('#userNameUpdate,#userURLUpdate,#userAvatarUpdate,#userB3KeyUpdate').val('')
    $('#userNameUpdate').data('userInfo', null)
    $('#userUpdate').dialog('open')

    $.ajax({
      url: Label.servePath + '/console/user/' + id,
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        $('#userURLUpdate').val(result.user.userURL)
        $('#userAvatarUpdate').val(result.user.userAvatar)
        // $('#userB3KeyUpdate').val(result.user.userB3Key)
        $('#userB3KeyUpdate').val('')
        $('#userNameUpdate').val(result.user.userName).data('userInfo', {
          'oId': id,
          'userRole': userRole,
        })

        $('#loadMsg').text('')
      },
    })
  },
  /*
   * 更新用户
   */
  update: function () {
    if (this.validate('Update')) {
      $('#loadMsg').text(Label.loadingLabel)
      $('#tipMsg').text('')

      var userInfo = $('#userNameUpdate').data('userInfo')
      if (!userInfo || !userInfo.oId) {
        $('#loadMsg').text('')
        return
      }
      var requestJSONObject = {
        'userName': $('#userNameUpdate').val(),
        'oId': userInfo.oId,
        'userURL': $('#userURLUpdate').val(),
        'userRole': userInfo.userRole,
        'userAvatar': $('#userAvatarUpdate').val(),
        'userB3Key': $('#userB3KeyUpdate').val(),
      }

      $.ajax({
        url: Label.servePath + '/console/user/',
        type: 'PUT',
        cache: false,
        data: JSON.stringify(requestJSONObject),
        success: function (result, textStatus) {
          $('#tipMsg').text(result.msg)
          if (!result.sc) {
            $('#loadMsg').text('')
            return
          }

          $('#userUpdate').dialog('close')
          admin.userList.getList(admin.userList.pageInfo.currentPage)

          $('#loadMsg').text('')
        },
      })
    }
  },
  /*
   * 删除用户
   * @id 用户 id
   * @userName 用户名称
   */
  del: function (id, userName) {
    var isDelete = confirm(Label.confirmRemoveLabel + Label.userLabel + '"' +
      Util.htmlDecode(decodeURIComponent(userName)) + '"?')
    if (isDelete) {
      $('#loadMsg').text(Label.loadingLabel)
      $('#tipMsg').text('')

      $.ajax({
        url: Label.servePath + '/console/user/' + id,
        type: 'DELETE',
        cache: false,
        success: function (result, textStatus) {
          $('#tipMsg').text(result.msg)
          if (!result.sc) {
            $('#loadMsg').text('')
            return
          }

          var pageNum = admin.userList.pageInfo.currentPage
          if (admin.userList.pageInfo.currentCount === 1 &&
            admin.userList.pageInfo.pageCount !== 1 &&
            admin.userList.pageInfo.currentPage ===
            admin.userList.pageInfo.pageCount) {
            admin.userList.pageInfo.pageCount--
            pageNum = admin.userList.pageInfo.pageCount
          }
          var hashList = window.location.hash.split('/')
          if (pageNum !== parseInt(hashList[hashList.length - 1])) {
            admin.setHashByPage(pageNum)
          }
          admin.userList.getList(pageNum)

          $('#loadMsg').text('')
        },
      })
    }
  },
  /**
   * 修改角色
   * @param id
   */
  toggleStatus: function (id, nextStatus) {
    $('#tipMsg').text('')
    $.ajax({
      url: Label.servePath + '/console/user/' + id + '/status',
      type: 'PUT',
      cache: false,
      data: JSON.stringify({ userStatus: nextStatus }),
      success: function (result) {
        $('#tipMsg').text(result.msg || '')
        if (result.sc) {
          admin.userList.getList(admin.userList.pageInfo.currentPage)
        }
      },
    })
  },
  changeRole: function (id) {
    $('#tipMsg').text('')
    $.ajax({
      url: Label.servePath + '/console/changeRole/' + id,
      type: 'POST',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        var pageNum = admin.userList.pageInfo.currentPage
        if (admin.userList.pageInfo.currentCount === 1 &&
          admin.userList.pageInfo.pageCount !== 1 &&
          admin.userList.pageInfo.currentPage ===
          admin.userList.pageInfo.pageCount) {
          admin.userList.pageInfo.pageCount--
          pageNum = admin.userList.pageInfo.pageCount
        }
        var hashList = window.location.hash.split('/')
        if (pageNum !== parseInt(hashList[hashList.length - 1])) {
          admin.setHashByPage(pageNum)
        }
        admin.userList.getList(pageNum)

        $('#loadMsg').text('')
      },
    })
  },
  /*
   * 验证字段
   * @status 更新或者添加时进行验证
   */
  validate: function (status) {
    if (!status) {
      status = ''
    }
    var userName = $('#userName' + status).val().replace(/(^\s*)|(\s*$)/g, '')
    if (2 > userName.length || userName.length > 20) {
      $('#tipMsg').text(Label.nameTooLongLabel)
      $('#userName' + status).focus()
    } else {
      return true
    }

    return false
  },
}

/*
 * 注册到 admin 进行管理 
 */
admin.register['user-list'] = {
  'obj': admin.userList,
  'init': admin.userList.init,
  'refresh': admin.userList.getList,
}
