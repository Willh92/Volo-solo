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

var DEFAULT_USITE = {
  usiteUserId: '',
  usiteResume: '',
  usiteWeiBo: '',
  usiteQQMusic: '',
  usiteStackOverflow: '',
  usiteDribbble: '',
  usiteGitHub: '',
  usiteMedium: '',
  usiteTwitter: '',
  usiteQQ: '',
  usiteLinkedIn: '',
  usiteSteam: '',
  oId: '',
  usiteInstagram: '',
  usiteCodePen: '',
  usiteWYMusic: '',
  usiteWeChat: '',
  usiteZhiHu: '',
  usiteBehance: '',
  usiteTelegram: '',
  usiteFacebook: ''
};
var usiteJSON = Object.assign({}, DEFAULT_USITE);

/* usite 相关操作 */
admin.usite = {
  locale: '',
  /*
   * 初始化
   */
  init: function () {
    $('#loadMsg').text('');
    this.loaded = false;
    $('#tabsPanel_usite .console-form-actions button').prop('disabled', true);
    $.ajax({
      url: Label.servePath + '/admin/usite/get',
      type: 'GET',
      cache: false,
      timeout: 30000,
      success: function (data) {
        if (data.code !== 200) { admin.showNotice('无法读取联系方式，请重试。', 'error'); return; }
        if (!data.msg) {
          usiteJSON = Object.assign({}, DEFAULT_USITE);
        } else {
          try {
            usiteJSON = Object.assign({}, DEFAULT_USITE, $.parseJSON(data.msg));
          } catch (e) {
            usiteJSON = Object.assign({}, DEFAULT_USITE);
          }
        }

        admin.usite.loaded = true;
        for (var i in usiteJSON) {
          if (i !== 'usiteUserId' && i !== 'oId') {
            $('#' + i).val(usiteJSON[i]);
          }
        }
      },
      error: function (xhr, status) { admin.handleRequestError(xhr, status); },
      complete: function () { $('#tabsPanel_usite .console-form-actions button').prop('disabled', !admin.usite.loaded); }
    })
  },
  /*
   * @description 参数校验
   */
  validate: function () {
    return true
  },
  /*
   * @description 更新
   */
  update: function () {
    if (this.saving || this.loaded === false) return;
    for (var i in usiteJSON) {
      if (i === 'usiteUserId' || i === 'oId') {
        usiteJSON[i] = usiteJSON[i] || '';
        continue;
      }
      let value = $('#' + i).val();
      if (value === undefined || value === null) {
        value = '';
      }
      usiteJSON[i] = String(value).trim();
    }
    let newResult = JSON.stringify(usiteJSON);

    const buttons = $('#tabsPanel_usite .console-form-actions button');
    admin.usite.saving = true;
    buttons.prop('disabled', true);
    $('#loadMsg').text('正在保存…');
    admin.showNotice('正在保存联系方式…');
    $.ajax({
      url: Label.servePath + '/admin/usite/set',
      type: 'POST',
      timeout: 30000,
      global: false,
      contentType: 'application/json',
      dataType: 'json',
      data: newResult,
      success: function (data) {
        if (data && data.code === 200 && data.data && typeof data.data === 'object') {
          usiteJSON = Object.assign({}, DEFAULT_USITE, data.data);
          for (var k in usiteJSON) {
            if (k !== 'usiteUserId' && k !== 'oId') {
              $('#' + k).val(usiteJSON[k]);
            }
          }
        }
        admin.showNotice(data.msg || (data.code === 200 ? '联系方式已保存。' : '联系方式保存失败，请检查后重试。'), data.code === 200 ? 'info' : 'error');
      },
      error: function (xhr, reason) { admin.handleRequestError(xhr, reason); },
      complete: function () { admin.usite.saving = false; buttons.prop('disabled', false); $('#loadMsg').text(''); }
    });
  }
}

/*
 * 注册到 admin 进行管理
 */
admin.register['usite'] = {
  'obj': admin.usite,
  'init': admin.usite.init,
  'refresh': function () {
    admin.clearTip()
    this.init()
  },
}
