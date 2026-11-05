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
 * others for admin.
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* others 相关操作 */
admin.others = {
  maintenance: function (button, path) {
    if (button.disabled) return
    var text = button.textContent
    button.disabled = true
    button.textContent = '处理中…'
    $.ajax({url: Label.servePath + path, type: 'POST', timeout: 90000,
      success: function (result) { $('#tipMsg').text(result.msg || (result.sc === false ? '操作未完成，请检查配置。' : '操作已完成。')) },
      error: function (xhr) { $('#tipMsg').text(xhr.responseJSON?.msg || '操作未完成，请检查配置和网络后重试。') },
      complete: function () { button.disabled = false; button.textContent = text }
    })
  },
  clearMailCache: function (button) {
    swal({title:'清除邮箱缓存？', text:'旧评论将无法继续收到邮件提醒，评论内容会保留。', icon:'warning', buttons:{cancel:'取消',confirm:'清除'}}).then(function (confirmed) {
      if (confirmed) admin.others.maintenance(button, '/prop/mail/clear')
    })
  },
  /*
   * @description 初始化
   */
  init: function () {
    $("#tabOthers").tabs();
    $('#loadMsg').text('');

    admin.logs.page = 1; admin.logs.through = null;
    admin.others.getLog();
    if (typeof window !== 'undefined' && window.location && window.location.hash) {
      if (window.location.hash.indexOf('#tools/others/media') === 0) admin.others.loadMediaLibrary();
      else if (window.location.hash.indexOf('#tools/others/backups') === 0) admin.others.loadLocalBackups();
      else if (window.location.hash.indexOf('#tools/others/webhooks') === 0) admin.others.loadWebhooks();
    }
    this._bindBatchDropzone();
    clearInterval(this.pollTimer);
    const pollTimer = setInterval(function () {
      if (typeof document !== 'undefined' && document.hidden) return;
      if (admin.logs.page === 1) admin.others.getLog();
    }, 15000);
    this.pollTimer = pollTimer;
    if (window.BoloNavigation) window.BoloNavigation.onLeave(function () { clearInterval(pollTimer); });
  },

  getLog: function (refresh) { admin.logs.load(false, refresh === true) },

  /*
   * @description 移除未使用的存档
   */
  removeUnusedArchives: function () {
    $("#tipMsg").text("");

    $.ajax({
      url: Label.servePath + "/console/archive/unused",
      type: "DELETE",
      cache: false,
      success: function (result, textStatus) {
        $("#tipMsg").text(result.msg);
      }
    });
  },
  /*
   * @description 移除未使用的标签
   */
  removeUnusedTags: function () {
    $("#tipMsg").text("");

    $.ajax({
      url: Label.servePath + "/console/tag/unused",
      type: "DELETE",
      cache: false,
      success: function (result, textStatus) {
        $("#tipMsg").text(result.msg);
      }
    });
  },
  /*
   * @description 导出数据为 SQL 文件
   */
  exportSQL: function () {
    $("#tipMsg").text("正在准备 SQL 导出归档，浏览器将直接开始下载…");
    window.location.href = Label.servePath + "/console/export/sql";
  },
  /*
 * @description 导出数据为 JSON 文件
 */
  exportJSON: function () {
    $("#tipMsg").text("正在准备 JSON 导出归档，浏览器将直接开始下载…");
    window.location.href = Label.servePath + "/console/export/json";
  },
  /*
  * @description 导出数据为 Hexo Markdown 文件
  */
  exportHexo: function () {
    $("#tipMsg").text("正在准备 Markdown 导出归档，浏览器将直接开始下载…");
    window.location.href = Label.servePath + "/console/export/hexo";
  },
  /*
   * 获取未使用的标签。
   * XXX: Not used this function yet.
   */
  getUnusedTags: function () {
    $.ajax({
      url: Label.servePath + "/console/tag/unused",
      type: "GET",
      cache: false,
      success: function (result, textStatus) {
        $("#tipMsg").text(result.msg);
        if (!result.sc) {
          $("#loadMsg").text("");
          return;
        }

        var unusedTags = result.unusedTags;
        if (0 === unusedTags.length) {
          return;
        }
      }
    });
  },
  /*
   * 提交链滴图床转换请求。
   * XXX: Not used this function yet.
   */
  pbstart: function () {
    var button = $("#tabOthersPanel_move button");
    if (button.prop("disabled")) return;
    button.prop("disabled", true);
    $.ajax({
      url: Label.servePath + "/PBC/run",
      type: "POST",
      success: function (result) {
        $("#tipMsg").text(result.msg);
        $("#status").text("运行中");
      },
      error: function (xhr) {
        $("#tipMsg").text(xhr.responseJSON && xhr.responseJSON.msg || xhr.status + ": " + xhr.statusText);
      },
      complete: function () { button.prop("disabled", false); }
    });
  },

  _escape: function (v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
    });
  },

  _fmtBytes: function (bytes) {
    var n = Number(bytes || 0);
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(2) + ' MB';
  },

  _orphanSet: null,

  loadMediaLibrary: function () {
    var that = this;
    var q = $('#mediaSearchInput').val() || '';
    $.ajax({
      url: Label.servePath + '/console/media/local?q=' + encodeURIComponent(q),
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!res || !res.sc) return;
        if (!res.configured) {
          $('#mediaLibrarySummary').text(res.msg || '当前未启用本地图床。');
          $('#mediaLibraryGrid').html('');
          return;
        }
        $('#mediaLibrarySummary').text('目录：' + (res.dir || '') + ' · 共 ' + Number(res.totalCount || 0) + ' 张（' + that._fmtBytes(res.totalBytes) + '）');
        var items = res.items || [];
        if (!items.length) {
          $('#mediaLibraryGrid').html('<p class="console-form-help">暂无匹配的本地图片。</p>');
          return;
        }
        var html = '';
        for (var i = 0; i < items.length; i++) {
          var it = items[i];
          var isOrphan = Boolean(that._orphanSet && that._orphanSet[it.name]);
          if (that._orphanSet && !isOrphan && $('#deleteAllOrphansBtn').data('filterOnly')) continue;
          var safeName = that._escape(it.name);
          var safeUrl = that._escape(it.url);
          var safeMd = that._escape(it.markdown);
          html += '<div class="console-media-card">' +
            '<div class="console-media-card__thumb">' +
            '  <img src="' + safeUrl + '" alt="' + safeName + '" loading="lazy"/>' +
            '</div>' +
            '<div class="console-media-card__name">' + safeName + (isOrphan ? ' <span class="console-media-card__orphan">[未引用]</span>' : '') + '</div>' +
            '<div class="console-media-card__meta">' + that._fmtBytes(it.size) + '</div>' +
            '<div class="console-media-card__actions">' +
            '  <button type="button" data-md="' + safeMd + '" onclick="admin.others.copyMediaMarkdown(this)">复制 MD</button>' +
            '  <button type="button" data-name="' + safeName + '" onclick="admin.others.deleteMediaItem(this)">删除</button>' +
            '</div>' +
            '</div>';
        }
        $('#mediaLibraryGrid').html(html);
      }
    });
  },

  copyMediaMarkdown: function (btn) {
    var md = btn.getAttribute('data-md') || '';
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(md).then(function () { $('#tipMsg').text('已复制 Markdown 引用：' + md); });
    } else {
      window.prompt('复制 Markdown 引用：', md);
    }
  },

  scanOrphanMedia: function (btn) {
    var that = this;
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    $.ajax({
      url: Label.servePath + '/console/media/local/scan-orphans',
      type: 'POST',
      contentType: 'application/json',
      data: '{}',
      success: function (res) {
        $('#tipMsg').text(res.msg || '扫描完成');
        var map = {};
        var arr = (res && res.orphanNames) || [];
        for (var i = 0; i < arr.length; i++) map[arr[i]] = true;
        that._orphanSet = map;
        that._orphanList = arr;
        if (arr.length > 0) {
          $('#deleteAllOrphansBtn').removeClass('fn__none').text('一键清理 ' + arr.length + ' 张孤儿图片');
        } else {
          $('#deleteAllOrphansBtn').addClass('fn__none');
        }
        that.loadMediaLibrary();
      },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  deleteMediaItem: function (btn) {
    var that = this;
    var name = btn.getAttribute('data-name') || '';
    if (!name || !window.confirm('确认永久删除本地图片 "' + name + '" 吗？')) return;
    $.ajax({
      url: Label.servePath + '/console/media/local/delete',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ name: name }),
      success: function (res) {
        $('#tipMsg').text(res.msg || '已删除');
        if (that._orphanSet) delete that._orphanSet[name];
        that.loadMediaLibrary();
      },
      error: function (xhr) { $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '删除失败'); }
    });
  },

  deleteAllOrphans: function (btn) {
    var that = this;
    var list = that._orphanList || [];
    if (!list.length || !window.confirm('确认一次性清理全部 ' + list.length + ' 张未被引用的孤儿图片吗？')) return;
    if (btn) btn.disabled = true;
    $.ajax({
      url: Label.servePath + '/console/media/local/delete',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ names: list }),
      success: function (res) {
        $('#tipMsg').text(res.msg || '已清理孤儿图片');
        that._orphanSet = null;
        that._orphanList = [];
        $('#deleteAllOrphansBtn').addClass('fn__none');
        that.loadMediaLibrary();
      },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  loadLocalBackups: function () {
    var that = this;
    this.loadRemoteBackupConfig();
    $.ajax({
      url: Label.servePath + '/console/backups/local',
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!res || !res.sc) return;
        var cfg = res.config || {};
        $('#localBackupAutoInput').prop('checked', Boolean(cfg.localBackupAuto));
        $('#localBackupKeepCountInput').val(cfg.localBackupKeepCount || 7);
        $('#localBackupDirInput').val(cfg.localBackupDir || 'backups');
        that._renderBackupsTable(res.items || []);
      }
    });
  },

  _renderBackupsTable: function (items) {
    var that = this;
    if (!items.length) {
      $('#localBackupsTable').html('<tbody><tr><td colspan="4" class="console-simple-table__empty">当前目录下暂无归档文件。</td></tr></tbody>');
      return;
    }
    var html = '<thead><tr><th>归档文件名</th><th>大小</th><th>生成时间</th><th class="is-right">操作</th></tr></thead><tbody>';
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var safeName = that._escape(it.name);
      var dt = it.lastModified ? $.bowknot.getDate(it.lastModified, 1) : '-';
      var dlUrl = Label.servePath + '/console/backups/local/download?name=' + encodeURIComponent(it.name);
      html += '<tr>' +
        '<td style="font-family:monospace;">' + safeName + '</td>' +
        '<td>' + that._fmtBytes(it.size) + '</td>' +
        '<td>' + that._escape(dt) + '</td>' +
        '<td class="is-right"><div class="console-row-actions is-right">' +
        '  <a href="javascript:void(0)" data-name="' + safeName + '" onclick="admin.others.inspectLocalBackup(this)">预览恢复</a>' +
        '  <a href="' + that._escape(dlUrl) + '" data-no-pjax="true">下载 ZIP</a>' +
        '  <a href="javascript:void(0)" data-name="' + safeName + '" onclick="admin.others.deleteLocalBackup(this)">删除</a>' +
        '</div></td></tr>';
    }
    html += '</tbody>';
    $('#localBackupsTable').html(html);
  },

  saveLocalBackupConfig: function (btn) {
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    var payload = {
      localBackupAuto: $('#localBackupAutoInput').prop('checked'),
      localBackupKeepCount: parseInt($('#localBackupKeepCountInput').val() || '7', 10),
      localBackupDir: $('#localBackupDirInput').val() || 'backups'
    };
    $.ajax({
      url: Label.servePath + '/console/backups/local/config',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(payload),
      success: function (res) {
        $('#tipMsg').text(res.msg || '已保存');
        admin.others.loadLocalBackups();
      },
      error: function (xhr) { $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '保存失败'); },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  runLocalBackupNow: function (btn) {
    var that = this;
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    $.ajax({
      url: Label.servePath + '/console/backups/local/run',
      type: 'POST',
      contentType: 'application/json',
      data: '{}',
      success: function (res) {
        $('#tipMsg').text(res.msg || '归档已完成');
        that._renderBackupsTable(res.items || []);
      },
      error: function (xhr) { $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '归档失败'); },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  deleteLocalBackup: function (el) {
    var that = this;
    var name = el.getAttribute('data-name') || '';
    if (!name || !window.confirm('确认删除归档 "' + name + '" 吗？')) return;
    $.ajax({
      url: Label.servePath + '/console/backups/local/delete',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ name: name }),
      success: function (res) {
        $('#tipMsg').text(res.msg || '已删除');
        that._renderBackupsTable(res.items || []);
      },
      error: function (xhr) { $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '删除失败'); }
    });
  },

  _webhooksCache: [],

  loadWebhooks: function () {
    var that = this;
    $.ajax({
      url: Label.servePath + '/console/webhooks',
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!res || !res.sc) return;
        that._webhooksCache = res.webhooks || [];
        that._renderWebhooksTable(that._webhooksCache);
      }
    });
  },

  _renderWebhooksTable: function (list) {
    var that = this;
    if (!list.length) {
      $('#webhooksTable').html('<tbody><tr><td colspan="5" class="console-simple-table__empty">暂未配置任何 Webhook 推送通道。</td></tr></tbody>');
      return;
    }
    var html = '<thead><tr><th>名称</th><th>平台</th><th>订阅事件</th><th>密钥/状态</th><th class="is-right">操作</th></tr></thead><tbody>';
    for (var i = 0; i < list.length; i++) {
      var w = list[i];
      var safeId = that._escape(w.id);
      var evs = (w.events || []).join(', ');
      html += '<tr>' +
        '<td style="font-weight:600;">' + that._escape(w.name) + '</td>' +
        '<td>' + that._escape(w.provider) + '</td>' +
        '<td style="font-size:12px;">' + that._escape(evs) + '</td>' +
        '<td style="font-size:12px;">' + (w.enabled ? '● 启用' : '○ 停用') + (w.secretSet ? ' · 已配密钥' : '') + '</td>' +
        '<td class="is-right"><div class="console-row-actions is-right">' +
        '  <a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.others.testWebhookItem(this)">测试推送</a>' +
        '  <a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.others.editWebhookItem(this)">编辑</a>' +
        '  <a href="javascript:void(0)" data-id="' + safeId + '" onclick="admin.others.deleteWebhookItem(this)">删除</a>' +
        '</div></td></tr>';
    }
    html += '</tbody>';
    $('#webhooksTable').html(html);
  },

  resetWebhookForm: function () {
    $('#webhookEditId').val('');
    $('#webhookName').val('');
    $('#webhookProvider').val('feishu');
    $('#webhookUrl').val('');
    $('#webhookSecret').val('');
    $('#webhookChatId').val('');
    $('#webhookEnabled').prop('checked', true);
    $('input[name="webhookEvent"]').prop('checked', true);
  },

  editWebhookItem: function (el) {
    var id = el.getAttribute('data-id') || '';
    var list = this._webhooksCache || [];
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === id) {
        var w = list[i];
        $('#webhookEditId').val(w.id);
        $('#webhookName').val(w.name || '');
        $('#webhookProvider').val(w.provider || 'feishu');
        $('#webhookUrl').val(w.url || '');
        $('#webhookSecret').val('');
        $('#webhookChatId').val(w.chatId || '');
        $('#webhookEnabled').prop('checked', Boolean(w.enabled));
        var set = {};
        var evs = w.events || [];
        for (var j = 0; j < evs.length; j++) set[evs[j]] = true;
        $('input[name="webhookEvent"]').each(function () {
          $(this).prop('checked', Boolean(set[$(this).val()]));
        });
        break;
      }
    }
  },

  saveWebhook: function (btn) {
    var that = this;
    if (btn && btn.disabled) return;
    var evs = [];
    $('input[name="webhookEvent"]:checked').each(function () { evs.push($(this).val()); });
    var payload = {
      id: $('#webhookEditId').val() || '',
      name: $('#webhookName').val() || '',
      provider: $('#webhookProvider').val() || 'feishu',
      url: $('#webhookUrl').val() || '',
      secret: $('#webhookSecret').val() || '',
      chatId: $('#webhookChatId').val() || '',
      enabled: $('#webhookEnabled').prop('checked'),
      events: evs
    };
    if (btn) btn.disabled = true;
    $.ajax({
      url: Label.servePath + '/console/webhooks',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(payload),
      success: function (res) {
        $('#tipMsg').text(res.msg || '已保存');
        that.resetWebhookForm();
        that._webhooksCache = res.webhooks || [];
        that._renderWebhooksTable(that._webhooksCache);
      },
      error: function (xhr) { $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '保存失败'); },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  deleteWebhookItem: function (el) {
    var that = this;
    var id = el.getAttribute('data-id') || '';
    if (!id || !window.confirm('确认删除该 Webhook 推送通道吗？')) return;
    $.ajax({
      url: Label.servePath + '/console/webhooks/' + encodeURIComponent(id),
      type: 'DELETE',
      success: function (res) {
        $('#tipMsg').text(res.msg || '已删除');
        that._webhooksCache = res.webhooks || [];
        that._renderWebhooksTable(that._webhooksCache);
      },
      error: function (xhr) { $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '删除失败'); }
    });
  },

  testWebhookItem: function (el) {
    var id = el.getAttribute('data-id') || '';
    if (!id) return;
    $('#tipMsg').text('正在发送测试推送…');
    $.ajax({
      url: Label.servePath + '/console/webhooks/' + encodeURIComponent(id) + '/test',
      type: 'POST',
      contentType: 'application/json',
      data: '{}',
      success: function (res) { $('#tipMsg').text(res.msg || '测试推送成功'); },
      error: function (xhr) { $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '测试推送失败'); }
    });
  },

  _pendingRestoreSource: null,

  _renderRestoreInspection: function (res, sourceDesc) {
    var counts = (res && (res.counts || res.tableCounts || (res.summary && res.summary.counts))) || {};
    var keys = Object.keys(counts);
    var badges = [];
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      badges.push('<span class="console-stat-pill"><code>' + this._escape(k) + '</code> <strong>' + Number(counts[k] || 0) + '</strong></span>');
    }
    var mdCount = Number((res && (res.markdownCount || (res.summary && res.summary.markdownCount))) || 0);
    if (mdCount > 0) {
      badges.push('<span class="console-stat-pill">Markdown 文稿 <strong>' + mdCount + '</strong></span>');
    }
    var header = '<div style="margin-bottom:8px;"><strong>待恢复归档：</strong><code>' + this._escape(sourceDesc) + '</code></div>';
    var body = badges.length
      ? ('<div class="console-stat-pill-row">' + badges.join('') + '</div>')
      : '<div class="console-form-help">归档校验通过，已识别标准 JSON 数据结构。</div>';
    $('#backupRestoreSummaryMeta').html(header + body);
    $('#backupRestoreOverwriteAuth').prop('checked', false);
    $('#backupRestoreConfirmBox').show();
  },

  inspectLocalBackup: function (el) {
    var that = this;
    var name = el && el.getAttribute ? (el.getAttribute('data-name') || '') : '';
    if (!name) return;
    $('#tipMsg').text('正在校验归档结构…');
    $.ajax({
      url: Label.servePath + '/console/backups/local/inspect',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ fileName: name, name: name }),
      success: function (res) {
        if (!res || res.sc === false) {
          $('#tipMsg').text((res && res.msg) || '备份包校验未通过');
          return;
        }
        that._pendingRestoreSource = { type: 'local', fileName: name };
        that._renderRestoreInspection(res, name);
        $('#tipMsg').text(res.msg || '备份包校验通过，请确认恢复选项。');
      },
      error: function (xhr) {
        $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '备份包校验失败');
      }
    });
  },

  inspectUploadedBackup: function (btn) {
    var that = this;
    var input = typeof document !== 'undefined' ? document.getElementById('restoreZipUploadInput') : null;
    var file = input && input.files && input.files[0];
    if (!file) {
      $('#tipMsg').text('请先选择需要恢复的备份 .zip 文件。');
      return;
    }
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    var fd = new FormData();
    fd.append('file', file);
    $.ajax({
      url: Label.servePath + '/console/backups/local/inspect',
      type: 'POST',
      data: fd,
      processData: false,
      contentType: false,
      timeout: 60000,
      success: function (res) {
        if (!res || res.sc === false) {
          $('#tipMsg').text((res && res.msg) || '外部备份包校验未通过');
          return;
        }
        that._pendingRestoreSource = { type: 'upload', file: file };
        that._renderRestoreInspection(res, file.name);
        $('#tipMsg').text(res.msg || '外部备份包校验通过，请确认恢复选项。');
      },
      error: function (xhr) {
        $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '外部备份包解析失败');
      },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  cancelBackupRestore: function () {
    this._pendingRestoreSource = null;
    $('#backupRestoreConfirmBox').hide();
  },

  executeBackupRestore: function (btn) {
    var that = this;
    var src = this._pendingRestoreSource;
    if (!src) return;
    var overwriteAuth = $('#backupRestoreOverwriteAuth').prop('checked');
    var warnText = overwriteAuth
      ? '您勾选了“强制覆盖管理员凭据”，恢复完成后可能需要使用备份包中的密码/密钥重新登录。确认立即恢复整站数据吗？'
      : '执行恢复前系统会自动保存一份 pre-restore 本地备份，并保留当前管理员登录凭据。确认立即恢复整站数据吗？';
    if (!window.confirm(warnText)) return;
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    $('#tipMsg').text('正在生成安全快照并恢复整站数据，请勿关闭页面…');
    var ajaxOpts = {
      url: Label.servePath + '/console/backups/local/restore',
      type: 'POST',
      timeout: 120000,
      success: function (res) {
        if (!res || res.sc === false) {
          $('#tipMsg').text((res && res.msg) || '恢复未完成');
          return;
        }
        that.cancelBackupRestore();
        that.loadLocalBackups();
        $('#tipMsg').text(res.msg || '整站数据恢复已完成！');
      },
      error: function (xhr) {
        $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '恢复过程发生异常，已保留恢复前快照');
      },
      complete: function () { if (btn) btn.disabled = false; }
    };
    if (src.type === 'local') {
      ajaxOpts.contentType = 'application/json';
      ajaxOpts.data = JSON.stringify({ fileName: src.fileName, name: src.fileName, overwriteAuth: overwriteAuth });
    } else {
      var fd = new FormData();
      fd.append('file', src.file);
      fd.append('overwriteAuth', String(overwriteAuth));
      ajaxOpts.data = fd;
      ajaxOpts.processData = false;
      ajaxOpts.contentType = false;
    }
    $.ajax(ajaxOpts);
  },

  onRemoteProtocolChange: function () {
    var proto = $('#remoteBackupProtocol').val() || 'webdav';
    if (proto === 's3') {
      $('#remoteWebdavFields').hide();
      $('#remoteS3Fields').show();
    } else {
      $('#remoteS3Fields').hide();
      $('#remoteWebdavFields').show();
    }
  },

  loadRemoteBackupConfig: function () {
    var that = this;
    $.ajax({
      url: Label.servePath + '/console/backups/remote',
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!res || !res.sc) return;
        var cfg = res.config || res.remoteBackup || res;
        $('#remoteBackupEnabled').val(String(cfg.enabled) === 'true' ? 'true' : 'false');
        $('#remoteBackupProtocol').val(cfg.protocol === 's3' ? 's3' : 'webdav');
        $('#remoteBackupKeepCount').val(cfg.keepCount || 14);
        var resolvedPrefix = cfg.prefix !== undefined ? cfg.prefix : (cfg.protocol === 's3' ? (cfg.s3Prefix || cfg.remoteDir) : (cfg.remoteDir || cfg.s3Prefix));
        $('#remoteBackupPrefix').val(resolvedPrefix !== undefined ? resolvedPrefix : 'volo-backups');
        $('#remoteWebdavUrl').val(cfg.webdavUrl || '');
        $('#remoteWebdavUsername').val(cfg.webdavUser || cfg.webdavUsername || '');
        $('#remoteWebdavPassword').val('').attr('placeholder', (cfg.webdavPasswordSet || cfg.passwordSet) ? '已设置，留空保留原密码' : '应用密码');
        $('#remoteS3Endpoint').val(cfg.s3Endpoint || '');
        $('#remoteS3Bucket').val(cfg.s3Bucket || '');
        $('#remoteS3Region').val(cfg.s3Region || 'auto');
        $('#remoteS3PathStyle').val(String(cfg.s3PathStyle) === 'true' ? 'true' : 'false');
        $('#remoteS3AccessKey').val(cfg.s3AccessKey || '');
        $('#remoteS3SecretKey').val('').attr('placeholder', (cfg.s3SecretKeySet || cfg.secretKeySet) ? '已设置，留空保留原密钥' : 'SecretAccessKey');
        that.onRemoteProtocolChange();
      }
    });
  },

  _collectRemoteBackupPayload: function () {
    return {
      enabled: $('#remoteBackupEnabled').val() === 'true',
      protocol: $('#remoteBackupProtocol').val() === 's3' ? 's3' : 'webdav',
      keepCount: parseInt($('#remoteBackupKeepCount').val() || '14', 10) || 14,
      prefix: $.trim($('#remoteBackupPrefix').val() || ''),
      remoteDir: $.trim($('#remoteBackupPrefix').val() || ''),
      s3Prefix: $.trim($('#remoteBackupPrefix').val() || ''),
      webdavUrl: $.trim($('#remoteWebdavUrl').val() || ''),
      webdavUser: $.trim($('#remoteWebdavUsername').val() || ''),
      webdavUsername: $.trim($('#remoteWebdavUsername').val() || ''),
      webdavPassword: $('#remoteWebdavPassword').val() || '',
      s3Endpoint: $.trim($('#remoteS3Endpoint').val() || ''),
      s3Bucket: $.trim($('#remoteS3Bucket').val() || ''),
      s3Region: $.trim($('#remoteS3Region').val() || 'auto'),
      s3PathStyle: $('#remoteS3PathStyle').val() === 'true',
      s3AccessKey: $.trim($('#remoteS3AccessKey').val() || ''),
      s3SecretKey: $('#remoteS3SecretKey').val() || ''
    };
  },

  saveRemoteBackupConfig: function (btn) {
    var that = this;
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    var payload = this._collectRemoteBackupPayload();
    $.ajax({
      url: Label.servePath + '/console/backups/remote/config',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify(payload),
      success: function (res) {
        $('#tipMsg').text((res && res.msg) || '异地备份同步配置已保存');
        that.loadRemoteBackupConfig();
      },
      error: function (xhr) {
        $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '异地备份配置保存失败');
      },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  testRemoteBackup: function (btn) {
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    $('#tipMsg').text('正在测试远端存储连通性…');
    $.ajax({
      url: Label.servePath + '/console/backups/remote/test',
      type: 'POST',
      contentType: 'application/json',
      timeout: 30000,
      data: JSON.stringify(this._collectRemoteBackupPayload()),
      success: function (res) {
        $('#tipMsg').text((res && res.msg) || '远端存储连接正常');
      },
      error: function (xhr) {
        $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '远端存储连接测试失败');
      },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  syncRemoteBackupNow: function (btn) {
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    $('#tipMsg').text('正在上传最新备份归档至远端存储…');
    $.ajax({
      url: Label.servePath + '/console/backups/remote/sync',
      type: 'POST',
      contentType: 'application/json',
      timeout: 120000,
      data: '{}',
      success: function (res) {
        $('#tipMsg').text((res && res.msg) || '最新归档已同步至远端存储');
      },
      error: function (xhr) {
        $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '远端同步未完成');
      },
      complete: function () { if (btn) btn.disabled = false; }
    });
  },

  _batchFiles: [],

  _bindBatchDropzone: function () {
    if (typeof document === 'undefined' || !document.getElementById) return;
    var zone = document.getElementById('batchImportDropzone');
    if (!zone || zone._voloBound) return;
    zone._voloBound = true;
    var that = this;
    zone.addEventListener('dragover', function (e) {
      e.preventDefault();
      zone.classList.add('is-dragover');
    });
    zone.addEventListener('dragleave', function () {
      zone.classList.remove('is-dragover');
    });
    zone.addEventListener('drop', function (e) {
      e.preventDefault();
      zone.classList.remove('is-dragover');
      if (e.dataTransfer && e.dataTransfer.files) {
        that.onBatchFilesSelected(e.dataTransfer.files);
      }
    });
  },

  onBatchFilesSelected: function (fileList) {
    var accepted = [];
    for (var i = 0; i < (fileList ? fileList.length : 0); i++) {
      var f = fileList[i];
      if (f && /\.(md|markdown|zip)$/i.test(f.name || '')) {
        accepted.push(f);
      }
    }
    this._batchFiles = accepted;
    var summary = $('#batchImportFileSummary');
    if (!accepted.length) {
      summary.text('未识别到 .md 或 .zip 文件，请重新选择。').show();
      return;
    }
    var totalBytes = 0;
    var names = [];
    for (var j = 0; j < accepted.length; j++) {
      totalBytes += Number(accepted[j].size || 0);
      if (j < 5) names.push(accepted[j].name);
    }
    var extra = accepted.length > 5 ? (' 等共 ' + accepted.length + ' 个文件') : ('（共 ' + accepted.length + ' 个文件）');
    summary.text('已就绪：' + names.join('、') + extra + ' · 总大小 ' + this._fmtBytes(totalBytes)).show();
    $('#batchImportResultBox').hide();
  },

  clearBatchImport: function () {
    this._batchFiles = [];
    var input = typeof document !== 'undefined' ? document.getElementById('batchImportFileInput') : null;
    if (input) input.value = '';
    $('#batchImportFileSummary').hide().text('');
    $('#batchImportResultBox').hide().html('');
  },

  submitBatchImport: function (btn) {
    var that = this;
    if (!this._batchFiles || !this._batchFiles.length) {
      $('#tipMsg').text('请先拖入或选择至少一个 .md 或 .zip 文件。');
      return;
    }
    if (btn && btn.disabled) return;
    if (btn) btn.disabled = true;
    var fd = new FormData();
    for (var i = 0; i < this._batchFiles.length; i++) {
      fd.append('files', this._batchFiles[i]);
    }
    var pubStatus = $('#batchImportPublishStatus').val() || 'published';
    var dupMode = $('#batchImportDuplicateMode').val() || 'skip';
    fd.append('publishStatus', pubStatus);
    fd.append('publishImmediately', String(pubStatus === 'published'));
    fd.append('duplicateMode', dupMode);
    fd.append('skipDuplicatePermalink', String(dupMode !== 'copy'));
    $('#tipMsg').text('正在批量解析并导入文稿…');
    $.ajax({
      url: Label.servePath + '/console/import/batch',
      type: 'POST',
      data: fd,
      processData: false,
      contentType: false,
      timeout: 120000,
      success: function (res) {
        if (!res || res.sc === false) {
          $('#tipMsg').text((res && res.msg) || '批量导入失败');
          return;
        }
        var imported = Number(res.imported !== undefined ? res.imported : (res.importedCount || 0));
        var skipped = Number(res.skipped !== undefined ? res.skipped : (res.skippedCount || 0));
        var failed = Number(res.failed !== undefined ? res.failed : (res.failedCount || 0));
        var html = '<div class="console-stat-pill-row">' +
          '<span class="console-stat-pill">成功导入 <strong>' + imported + '</strong> 篇</span>' +
          '<span class="console-stat-pill">重复跳过 <strong>' + skipped + '</strong> 篇</span>' +
          '<span class="console-stat-pill">异常失败 <strong>' + failed + '</strong> 篇</span>' +
          '</div>';
        var errs = res.errors || [];
        if (errs.length) {
          html += '<ul style="margin:8px 0 0 18px;font-size:12px;">';
          for (var k = 0; k < Math.min(errs.length, 10); k++) {
            html += '<li>' + that._escape(errs[k]) + '</li>';
          }
          html += '</ul>';
        }
        $('#batchImportResultBox').html(html).show();
        $('#tipMsg').text(res.msg || ('批量导入完成：成功 ' + imported + ' 篇'));
      },
      error: function (xhr) {
        $('#tipMsg').text((xhr.responseJSON && xhr.responseJSON.msg) || '批量导入请求失败');
      },
      complete: function () { if (btn) btn.disabled = false; }
    });
  }
};

/*
 * 注册到 admin 进行管理 
 */
admin.register.others = {
  "obj": admin.others,
  "init": admin.others.init,
  "refresh": function () {
    admin.clearTip();
  }
};