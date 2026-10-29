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
 * preference for admin.
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* preference 相关操作 */
admin.preference = {
  loadGeoAccess: function () {
    var mode = document.getElementById('geoAccessMode')
    if (!mode) return
    $.ajax({url: Label.servePath + '/console/geo-access', type: 'GET', cache: false,
      success: function (result) {
        if (!mode.isConnected) return
        if (!result.sc) { $('#geoAccessMessage').text(result.msg); return }
        mode.value = result.mode
        $('#geoAccessCountries').val(result.countries)
        $('#geoAccessRegions').val(result.regions)
        $('#geoAccessUnknown').val(result.unknown)
        $('#geoAccessCurrent').text('当前连接位置：' + (result.currentRegion || result.currentCountryName) + (result.currentCountry ? '（' + result.currentCountry + '）' : '') + (result.enabled ? '' : '；服务器配置已暂停地区限制'))
      }, error: function (xhr) { if (mode.isConnected) $('#geoAccessMessage').text(xhr.responseJSON?.msg || '无法读取地区访问规则。') }
    })
  },
  saveGeoAccess: function () {
    var button = document.getElementById('geoAccessSave')
    if (!button || button.disabled) return
    button.disabled = true
    $.ajax({url: Label.servePath + '/console/geo-access', type: 'PUT', contentType: 'application/json',
      data: JSON.stringify({mode: $('#geoAccessMode').val(), countries: $('#geoAccessCountries').val(), regions: $('#geoAccessRegions').val(), unknown: $('#geoAccessUnknown').val()}),
      complete: function () { button.disabled = false },
      success: function (result) { $('#geoAccessMessage').text(result.msg || '地区规则已保存。'); if (result.sc) admin.preference.loadGeoAccess() },
      error: function (xhr) { $('#geoAccessMessage').text(xhr.responseJSON?.msg || '地区规则保存失败，请重试。') }
    })
  },
  ipBanPage: 1,
  ipBanRequest: 0,
  loadIpBans: function (page) {
    var list = document.getElementById('ipBanList')
    if (!list) return
    var that = this, revision = ++this.ipBanRequest
    var pager = document.getElementById('ipBanPagination'), total = document.getElementById('ipBanTotal')
    var query = document.getElementById('ipBanQuery')
    page = page || this.ipBanPage
    list.setAttribute('aria-busy', 'true')
    $.ajax({url: Label.servePath + '/console/ip-bans', type: 'GET', cache: false,
      data: {page: page, pageSize: 10, keyword: query ? query.value.trim() : ''},
      complete: function () { if (revision === that.ipBanRequest) list.removeAttribute('aria-busy') },
      success: function (result) {
        if (revision !== that.ipBanRequest || !list.isConnected) return
        list.replaceChildren()
        if (pager) pager.replaceChildren()
        if (!result.sc) { list.textContent = result.msg || '无法读取封禁列表。'; return }
        that.ipBanPage = result.page
        if (total) total.textContent = '共 ' + result.total + ' 条'
        if (!result.rules.length) {
          var empty = document.createElement('p'); empty.className = 'console-ip-ban-empty'
          empty.textContent = query?.value.trim() ? '没有匹配的封禁规则。' : '暂无封禁规则。'
          list.append(empty); return
        }
        var table = document.createElement('table'), head = document.createElement('thead'), heading = document.createElement('tr'), body = document.createElement('tbody')
        var labels = ['IP / 网段', '位置', '备注', '添加时间', '操作']
        table.className = 'console-ip-ban-table'
        labels.forEach(function (label) { var cell = document.createElement('th'); cell.scope = 'col'; cell.textContent = label; heading.append(cell) })
        head.append(heading)
        result.rules.forEach(function (rule) {
          var row = document.createElement('tr'), button = document.createElement('button')
          var date = new Date(rule.created)
          var values = [rule.range, rule.region || '位置未知', rule.reason || '—', Number.isFinite(date.getTime()) ? date.toLocaleString() : '—']
          values.forEach(function (value, index) { var cell = document.createElement('td'); cell.dataset.label = labels[index]; cell.textContent = value; row.append(cell) })
          var actions = document.createElement('td'); actions.dataset.label = labels[4]
          button.type = 'button'; button.textContent = '解除封禁'
          button.addEventListener('click', function () { admin.preference.removeIpBan(rule.range) })
          actions.append(button); row.append(actions); body.append(row)
        })
        table.append(head, body); list.append(table)
        if (pager && result.pageCount > 1) {
          function navigation(text, target, disabled) {
            var button = document.createElement('button'); button.type = 'button'; button.textContent = text; button.disabled = disabled
            button.addEventListener('click', function () { that.loadIpBans(target) }); pager.append(button)
          }
          navigation('上一页', result.page - 1, result.page <= 1)
          var status = document.createElement('span'); status.textContent = result.page + ' / ' + result.pageCount; pager.append(status)
          navigation('下一页', result.page + 1, result.page >= result.pageCount)
        }
      }, error: function (xhr) {
        if (revision !== that.ipBanRequest || !list.isConnected) return
        list.textContent = xhr.responseJSON?.msg || '无法读取封禁列表。'
        if (pager) pager.replaceChildren()
        if (total) total.textContent = ''
      }
    })
  },
  addIpBan: function () {
    $.ajax({url: Label.servePath + '/console/ip-bans', type: 'POST', contentType: 'application/json',
      data: JSON.stringify({range: $('#ipBanRange').val(), reason: $('#ipBanReason').val()}),
      success: function (result) { $('#ipBanMessage').text(result.msg); if (result.sc) { $('#ipBanRange').val(''); $('#ipBanReason').val(''); $('#ipBanQuery').val(''); admin.preference.loadIpBans(1) } },
      error: function (xhr) { $('#ipBanMessage').text(xhr.responseJSON?.msg || '封禁失败，请重试。') }
    })
  },
  removeIpBan: function (range) {
    if (!confirm('解除 ' + range + ' 的封禁？')) return
    $.ajax({url: Label.servePath + '/console/ip-bans/remove', type: 'POST', contentType: 'application/json', data: JSON.stringify({range: range}),
      success: function (result) { $('#ipBanMessage').text(result.msg); if (result.sc) admin.preference.loadIpBans() },
      error: function (xhr) { $('#ipBanMessage').text(xhr.responseJSON?.msg || '解除封禁失败，请重试。') }
    })
  },
  locale: '',
  editorMode: '',
  /*
   * 初始化
   */
  init: function () {
    $('#tabPreference').tabs()
    this.loadIpBans()
    this.loadGeoAccess()

    $.ajax({
      url: Label.servePath + '/console/preference/',
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        var preference = result.preference

        $.ajax({
          url: Label.servePath + '/plugins/kanbanniang/assets/list',
          type: 'GET',
          success: function (res) {
            if (!res || res.sc === false || typeof res.msg !== 'string') return;
            var kanbanniangList = res.msg.split(';');
            $('#kanbanniangSelector option').not(':first').remove();
            for (var i = 0; i < kanbanniangList.length; i++) {
              if (kanbanniangList[i]) $('#kanbanniangSelector').append($('<option>').val(kanbanniangList[i]).text(kanbanniangList[i]));
            }
            $('#kanbanniangSelector').val(kanbanniangList.indexOf(preference.kanbanniangSelector) >= 0 ? preference.kanbanniangSelector : '');
          }
        });

        if (preference.wafCurrentLimitTimes === "" || preference.wafCurrentLimitTimes === undefined) {
          $("#wafCurrentLimitTimes").val("180")
        } else {
          $("#wafCurrentLimitTimes").val(preference.wafCurrentLimitTimes)
        }

        if (preference.wafCurrentLimitSecond === "" || preference.wafCurrentLimitSecond === undefined) {
          $("#wafCurrentLimitSecond").val("180")
        } else {
          $("#wafCurrentLimitSecond").val(preference.wafCurrentLimitSecond)
        }

        if (preference.wafPower === "" || preference.wafPower === undefined) {
          $("#wafPower").val("on")
        } else {
          $("#wafPower").val(preference.wafPower)
        }

        if (preference.interactive === "" || preference.interactive === undefined) {
          $("#interactiveSwitch").val("on")
        } else {
          $("#interactiveSwitch").val(preference.interactive)
        }

        if (preference.adminActiveSentToMailbox === "" || preference.adminActiveSentToMailbox === undefined) {
          $("#adminActiveSentToMailbox").val("on")
        } else {
          $("#adminActiveSentToMailbox").val(preference.adminActiveSentToMailbox)
        }

        $('#sendKey').val(preference.sendKey)
        $('#spam').val(preference.spam)
        $('#commentFilterBuiltin').val(preference.commentFilterBuiltin === 'off' ? 'off' : 'on')
        $('#commentFilterAllow').val(preference.commentFilterAllow || '')
        $('#replyRemind').val(preference.replyRemind)
        $('#sourceTC').text(preference.tuChuangConfig)
        sltd = $('#sourceTC').text().split('<<>>')[0]
        $('#tcS').val(sltd)
        $('#hacpaiUser').val(preference.hacpaiUser)
        $('#b3logKey').val(preference.b3logKey)
        $('#fishpiKey').val(preference.fishKey)
        $('#mailBox').val(preference.mailBox)
        $('#mailUsername').val(preference.mailUsername)
        $('#mailPassword').val(preference.mailPassword)
        $('#metaKeywords').val(preference.metaKeywords)
        $('#metaDescription').val(preference.metaDescription)
        $('#blogTitle').val(preference.blogTitle)
        $('#blogSubtitle').val(preference.blogSubtitle)
        $('#mostCommentArticleDisplayCount').
          val(preference.mostCommentArticleDisplayCount)
        $('#mostViewArticleDisplayCount').
          val(preference.mostViewArticleDisplayCount)
        $('#recentCommentDisplayCount').
          val(preference.recentCommentDisplayCount)
        $('#mostUsedTagDisplayCount').val(preference.mostUsedTagDisplayCount)
        $('#articleListDisplayCount').val(preference.articleListDisplayCount)
        $('#articleListPaginationWindowSize').
          val(preference.articleListPaginationWindowSize)
        $('#localeString').val(preference.localeString)
        $('#timeZoneId').val(preference.timeZoneId)
        $('#noticeBoard').val(preference.noticeBoard)
        $('#footerContent').val(preference.footerContent)
        $('#htmlHead').val(preference.htmlHead)
        $('#relevantArticlesDisplayCount').
          val(preference.relevantArticlesDisplayCount)
        $('#randomArticlesDisplayCount').
          val(preference.randomArticlesDisplayCount)
        $('#customVars').val(preference.customVars)
        $('#githubPAT').val(preference.githubPAT)
        $('#maxArchive').val(preference.maxArchive)
        $('#myGitHubID').val(preference.myGitHubID)

        'true' === preference.enableAutoFlushGitHub ? $('#enableAutoFlushGitHub').attr('checked', 'checked') : $('#enableAutoFlushGitHub').removeAttr('checked')
        'true' === preference.enableAutoFlushGitHubProfile ? $('#enableAutoFlushGitHubProfile').attr('checked', 'checked') : $('#enableAutoFlushGitHubProfile').removeAttr('checked')
        'true' === preference.showCodeBlockLn ? $('#showCodeBlockLn').attr('checked', 'checked') : $('#showCodeBlockLn').removeAttr('checked')
        'true' === preference.enableArticleUpdateHint ? $('#enableArticleUpdateHint').attr('checked', 'checked') : $('#enableArticleUpdateHint').removeAttr('checked')
        'true' === preference.allowVisitDraftViaPermalink ? $('#allowVisitDraftViaPermalink').attr('checked', 'checked') : $('allowVisitDraftViaPermalink').removeAttr('checked')
        'true' === preference.commentable ? $('#commentable').attr('checked', 'checked') : $('commentable').removeAttr('checked')
        'true' === preference.syncGitHub ? $('#syncGitHub').attr('checked', 'checked') : $('syncGitHub').removeAttr('checked')
        'true' === preference.pullGitHub ? $('#pullGitHub').attr('checked', 'checked') : $('pullGitHub').removeAttr('checked')
        'true' === preference.welfareLuteService ? $('#welfareLuteService').attr('checked', 'checked') : $('#welfareLuteService').removeAttr('checked')

        $("input:radio[value='" + preference.editorMode + "']").attr('checked', 'true');
        admin.preference.editorMode = preference.editorMode

        admin.preference.locale = preference.localeString

        // sign
        var signs = eval('(' + preference.signs + ')')
        for (var j = 1; j < signs.length; j++) {
          $('#preferenceSign' + j).val(signs[j].signHTML)
        }

        $('#articleListDisplay').val(preference.articleListStyle)
        $('#hljsTheme').val(preference.hljsTheme)
        $('#feedOutputMode').val(preference.feedOutputMode)
        $('#feedOutputCnt').val(preference.feedOutputCnt)
        $('#imageUploadCompress').val(preference.imageUploadCompress)
        $('#thumbCompress').val(preference.thumbCompress)
        $('#faviconURL').val(preference.faviconURL)

        $('#loadMsg').text('')
      },
    })
  },
  /*
   * @description 参数校验
   */
  validate: function () {
    if (!/^\d+$/.test($('#mostUsedTagDisplayCount').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' +
          Label.indexTagDisplayCntLabel + '] ' +
          Label.nonNegativeIntegerOnlyLabel)
      $('#mostUsedTagDisplayCount').focus()
      return false
    } else if (!/^\d+$/.test($('#recentCommentDisplayCount').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' +
          Label.indexRecentCommentDisplayCntLabel + '] ' +
          Label.nonNegativeIntegerOnlyLabel)
      $('#recentCommentDisplayCount').focus()
      return false
    } else if (!/^\d+$/.test($('#mostCommentArticleDisplayCount').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' +
          Label.indexMostCommentArticleDisplayCntLabel + '] ' +
          Label.nonNegativeIntegerOnlyLabel)
      $('#mostCommentArticleDisplayCount').focus()
      return false
    } else if (!/^\d+$/.test($('#mostViewArticleDisplayCount').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' +
          Label.indexMostViewArticleDisplayCntLabel + '] ' +
          Label.nonNegativeIntegerOnlyLabel)
      $('#mostViewArticleDisplayCount').focus()
      return false
    } else if (!/^\d+$/.test($('#articleListDisplayCount').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' + Label.pageSizeLabel +
          '] ' + Label.nonNegativeIntegerOnlyLabel)
      $('#articleListDisplayCount').focus()
      return false
    } else if (!/^\d+$/.test($('#articleListPaginationWindowSize').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' + Label.windowSizeLabel +
          '] ' + Label.nonNegativeIntegerOnlyLabel)
      $('#articleListPaginationWindowSize').focus()
      return false
    } else if (!/^\d+$/.test($('#randomArticlesDisplayCount').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' +
          Label.randomArticlesDisplayCntLabel + '] ' +
          Label.nonNegativeIntegerOnlyLabel)
      $('#randomArticlesDisplayCount').focus()
      return false
    } else if (!/^\d+$/.test($('#relevantArticlesDisplayCount').val())) {
      $('#tipMsg').
        text('[' + Label.paramSettingsLabel + ' - ' +
          Label.relevantArticlesDisplayCntLabel + '] ' +
          Label.nonNegativeIntegerOnlyLabel)
      $('#relevantArticlesDisplayCount').focus()
      return false
    } else if (
      (!/^\d+$/.test($('#wafCurrentLimitTimes').val())) || $('#wafCurrentLimitTimes').val() < 2 || $('#wafCurrentLimitTimes').val() > 2147483647 ||
      (!/^\d+$/.test($('#wafCurrentLimitSecond').val())) || $('#wafCurrentLimitSecond').val() < 2 || $('#wafCurrentLimitSecond').val() > 2147483647
    ) {
      $('#tipMsg').text('访问频率次数与时间必须在 2-2147483647 之间!')
      return false
    }
    return true
  },
  /*
   * @description 更新
   */
  update: function () {
    if (!admin.preference.validate()) {
      return
    }

    noBtnSwal("请稍候", 0);
    $('#tipMsg').text('')
    $('#loadMsg').text('')
    var signs = [
      {
        'oId': 0,
        'signHTML': '',
      }, {
        'oId': 1,
        'signHTML': $('#preferenceSign1').val(),
      }, {
        'oId': 2,
        'signHTML': $('#preferenceSign2').val(),
      }, {
        'oId': 3,
        'signHTML': $('#preferenceSign3').val(),
      }]

    if ($("#interactiveSwitch").val() === null) {
      $("#interactiveSwitch").val("on");
    }

    var requestJSONObject = {
      'preference': {
        'enableAutoFlushGitHub': $("#enableAutoFlushGitHub").prop('checked'),
        'enableAutoFlushGitHubProfile': $("#enableAutoFlushGitHubProfile").prop('checked'),
        'welfareLuteService': $("#welfareLuteService").prop('checked'),
        'sendKey': $("#sendKey").val(),
        'myGitHubID': $("#myGitHubID").val(),
        'adminActiveSentToMailbox': $("#adminActiveSentToMailbox").val(),
        'wafCurrentLimitTimes': $("#wafCurrentLimitTimes").val(),
        'wafCurrentLimitSecond': $("#wafCurrentLimitSecond").val(),
        'wafPower': $("#wafPower").val(),
        'interactive': $("#interactiveSwitch").val(),
        'spam': $('#spam').val(),
        'commentFilterBuiltin': $('#commentFilterBuiltin').val(),
        'commentFilterAllow': $('#commentFilterAllow').val(),
        'kanbanniangSelector': $('#kanbanniangSelector').val(),
        'replyRemind': $('#replyRemind').val(),
        'tuChuangConfig': $('#sourceTC').text(),
        'hacpaiUser': $('#hacpaiUser').val(),
        'b3logKey': $('#b3logKey').val(),
        'fishKey': $('#fishpiKey').val(),
        'mailBox': $('#mailBox').val(),
        'mailUsername': $('#mailUsername').val(),
        'mailPassword': $('#mailPassword').val(),
        'metaKeywords': $('#metaKeywords').val(),
        'metaDescription': $('#metaDescription').val(),
        'blogTitle': $('#blogTitle').val(),
        'blogSubtitle': $('#blogSubtitle').val(),
        'mostCommentArticleDisplayCount': $('#mostCommentArticleDisplayCount').val(),
        'mostViewArticleDisplayCount': $('#mostViewArticleDisplayCount').val(),
        'recentCommentDisplayCount': $('#recentCommentDisplayCount').val(),
        'mostUsedTagDisplayCount': $('#mostUsedTagDisplayCount').val(),
        'articleListDisplayCount': $('#articleListDisplayCount').val(),
        'articleListPaginationWindowSize': $('#articleListPaginationWindowSize').val(),
        'localeString': $('#localeString').val(),
        'timeZoneId': $('#timeZoneId').val(),
        'noticeBoard': $('#noticeBoard').val(),
        'footerContent': $('#footerContent').val(),
        'htmlHead': $('#htmlHead').val(),
        'relevantArticlesDisplayCount': $('#relevantArticlesDisplayCount').val(),
        'randomArticlesDisplayCount': $('#randomArticlesDisplayCount').val(),
        'enableArticleUpdateHint': $('#enableArticleUpdateHint').prop('checked'),
        'signs': signs,
        'allowVisitDraftViaPermalink': $('#allowVisitDraftViaPermalink').prop('checked'),
        'articleListStyle': $('#articleListDisplay').val(),
        'hljsTheme': $('#hljsTheme').val(),
        'feedOutputMode': $('#feedOutputMode').val(),
        'feedOutputCnt': $('#feedOutputCnt').val(),
        'imageUploadCompress': $('#imageUploadCompress').val(),
        'thumbCompress': $('#thumbCompress').val(),
        'faviconURL': $('#faviconURL').val(),
        'syncGitHub': $('#syncGitHub').prop('checked'),
        'syncGitHubProfile': $('#syncGitHubProfile').prop('checked'),
        'showCodeBlockLn': $('#showCodeBlockLn').prop('checked'),
        'pullGitHub': $('#pullGitHub').prop('checked'),
        'commentable': $('#commentable').prop('checked'),
        'customVars': $('#customVars').val(),
        'githubPAT': $('#githubPAT').val(),
        'maxArchive': $('#maxArchive').val(),
        'editorMode': $("input[name='editorMode']:checked").val(),
      },
    }

    $.ajax({
      url: Label.servePath + '/console/preference/',
      type: 'PUT',
      cache: false,
      data: JSON.stringify(requestJSONObject),
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        if ($('#localeString').val() !== admin.preference.locale || $("input[name='editorMode']:checked").val() !== admin.preference.editorMode) {
          window.location.reload()
        }

        window.location.reload()
      },
    })
  },
}

/*
 * 注册到 admin 进行管理
 */
admin.register['preference'] = {
  'obj': admin.preference,
  'init': admin.preference.init,
  'refresh': function () {
    admin.clearTip()
  },
}
