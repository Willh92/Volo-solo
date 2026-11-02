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
  locale: '',
  editorMode: '',
  loaded: false,
  /*
   * 初始化
   */
  init: function () {
    this.loaded = false
    $('#tabPreferencePanel .console-form-actions button').prop('disabled', true)
    $('#tabPreference').tabs()

    $.ajax({
      url: Label.servePath + '/console/preference/',
      type: 'GET',
      cache: false,
      timeout: 30000,
      success: function (result, textStatus) {
        if (!result || !result.sc || !result.preference) {
          admin.showNotice(result && result.msg || '无法读取偏好设置，请重新加载后重试。', 'error')
          $('#loadMsg').text('')
          return
        }

        var preference = result.preference

        var model = preference.kanbanniangSelector || ''
        $('#kanbanniangSelector option').not(':first').remove()
        if (model) $('#kanbanniangSelector').append($('<option>').val(model).text(model))
        $('#kanbanniangSelector').val(model)
        $.ajax({
          url: Label.servePath + '/plugins/kanbanniang/assets/list',
          type: 'GET',
          timeout: 30000,
          success: function (res) {
            if (!res || res.sc === false || typeof res.msg !== 'string') return;
            var kanbanniangList = res.msg.split(';');
            var selected = $('#kanbanniangSelector').val();
            $('#kanbanniangSelector option').not(':first').remove();
            for (var i = 0; i < kanbanniangList.length; i++) {
              if (kanbanniangList[i]) $('#kanbanniangSelector').append($('<option>').val(kanbanniangList[i]).text(kanbanniangList[i]));
            }
            if (selected && kanbanniangList.indexOf(selected) < 0) $('#kanbanniangSelector').append($('<option>').val(selected).text(selected));
            $('#kanbanniangSelector').val(selected);
          }
        });

        if (preference.interactive === "" || preference.interactive === undefined) {
          $("#interactiveSwitch").val("on")
        } else {
          $("#interactiveSwitch").val(preference.interactive)
        }
        $('#showLoginEntry').val(preference.showLoginEntry === 'on' ? 'on' : 'off')

        if (preference.adminActiveSentToMailbox === "" || preference.adminActiveSentToMailbox === undefined) {
          $("#adminActiveSentToMailbox").val("on")
        } else {
          $("#adminActiveSentToMailbox").val(preference.adminActiveSentToMailbox)
        }

        $('#sendKey').val(preference.sendKey)
        $('#replyRemind').val(preference.replyRemind)
        $('#sourceTC').text(preference.tuChuangConfig)
        sltd = $('#sourceTC').text().split('<<>>')[0]
        $('#tcS').val(sltd)
        $('#hacpaiUser').val(preference.hacpaiUser)
        $('#b3logKey').val(preference.b3logKey)
        $('#fishpiKey').val(preference.fishKey)
        ;['smtpHost','smtpPort','smtpSecurity','smtpAuth','smtpFrom'].forEach(function (key) { $('#' + key).val(preference[key]) })
        $('#mailPassword').attr('placeholder', preference.smtpPasswordSet ? '已设置，留空保留原密码' : '密码或授权码')
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
        admin.githubRepositories.init()

        ;['enableAutoFlushGitHub', 'enableAutoFlushGitHubProfile', 'showCodeBlockLn', 'enableArticleUpdateHint',
          'allowVisitDraftViaPermalink', 'commentable', 'syncGitHub', 'welfareLuteService'].forEach(function (key) {
          $('#' + key).prop('checked', preference[key] === 'true')
        })

        $("input[name='editorMode']").prop('checked', false)
        $("input[name='editorMode'][value='" + preference.editorMode + "']").prop('checked', true)
        admin.preference.editorMode = preference.editorMode

        admin.preference.locale = preference.localeString

        // sign
        var signs = JSON.parse(preference.signs)
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

        admin.preference.loaded = true
        $('#loadMsg').text('')
      },
      error: function (xhr, reason) { admin.handleRequestError(xhr, reason) },
      complete: function () { $('#tabPreferencePanel .console-form-actions button').prop('disabled', !admin.preference.loaded) },
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
    }
    return true
  },
  /*
   * @description 更新
   */
  update: function () {
    if (this.saving || this.loaded === false) return
    if (!admin.preference.validate()) {
      return
    }

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
        'interactive': $("#interactiveSwitch").val(),
        'showLoginEntry': $('#showLoginEntry').val() === 'on' ? 'on' : 'off',
        'kanbanniangSelector': $('#kanbanniangSelector').val(),
        'replyRemind': $('#replyRemind').val(),
        'tuChuangConfig': $('#sourceTC').text(),
        'hacpaiUser': $('#hacpaiUser').val(),
        'b3logKey': $('#b3logKey').val(),
        'fishKey': $('#fishpiKey').val(),
        'mailBox': '',
        'smtpHost': $('#smtpHost').val().trim(),
        'smtpPort': $('#smtpPort').val(),
        'smtpSecurity': $('#smtpSecurity').val(),
        'smtpAuth': $('#smtpAuth').val(),
        'smtpFrom': $('#smtpFrom').val().trim(),
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
        'showCodeBlockLn': $('#showCodeBlockLn').prop('checked'),
        'commentable': $('#commentable').prop('checked'),
        'customVars': $('#customVars').val(),
        'githubPAT': $('#githubPAT').val(),
        'maxArchive': $('#maxArchive').val(),
        'editorMode': $("input[name='editorMode']:checked").val(),
      },
    }

    if (window.location.hash === '#tools/preference/myGitHub') {
      var githubDisplay = admin.githubRepositories.payload()
      if (githubDisplay) requestJSONObject.githubDisplay = githubDisplay
    }

    var buttons = $('#tabPreferencePanel .console-form-actions button')
    admin.preference.saving = true
    buttons.prop('disabled', true)
    $('#loadMsg').text('正在保存…')
    admin.showNotice('正在保存设置…')
    $.ajax({
      url: Label.servePath + '/console/preference/',
      type: 'PUT',
      cache: false,
      timeout: 30000,
      global: false,
      contentType: 'application/json',
      data: JSON.stringify(requestJSONObject),
      success: function (result) {
        if (!result.sc) { admin.showNotice(result.msg || '设置保存失败，请检查后重试。', 'error'); return }
        window.location.reload()
      },
      error: function (xhr, reason) { admin.handleRequestError(xhr, reason) },
      complete: function () { admin.preference.saving = false; buttons.prop('disabled', false); $('#loadMsg').text('') },
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
