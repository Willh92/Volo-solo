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
    clearInterval(this.pollTimer);
    const pollTimer = setInterval(function () { if (admin.logs.page === 1) admin.others.getLog(); }, 15000);
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
    $("#tipMsg").text("");

    $.ajax({
      url: Label.servePath + "/console/export/sql",
      type: "GET",
      cache: false,
      success: function (result, textStatus) {
        // AJAX 下载文件的话这里会发两次请求，用 sc 来判断是否是文件，如果没有 sc 说明文件可以下载（实际上就是 result）
        if (!result.sc) {
          // 再发一次请求进行正式下载
          window.location = Label.servePath + "/console/export/sql";
        } else {
          $("#tipMsg").text(result.msg);
        }
      }
    });
  },
  /*
 * @description 导出数据为 JSON 文件
 */
  exportJSON: function () {
    $("#tipMsg").text("");

    $.ajax({
      url: Label.servePath + "/console/export/json",
      type: "GET",
      cache: false,
      success: function (result, textStatus) {
        // AJAX 下载文件的话这里会发两次请求，用 sc 来判断是否是文件，如果没有 sc 说明文件可以下载（实际上就是 result）
        if (!result.sc) {
          // 再发一次请求进行正式下载
          window.location = Label.servePath + "/console/export/json";
        } else {
          $("#tipMsg").text(result.msg);
        }
      }
    });
  },
  /*
  * @description 导出数据为 Hexo Markdown 文件
  */
  exportHexo: function () {
    $("#tipMsg").text("");

    $.ajax({
      url: Label.servePath + "/console/export/hexo",
      type: "GET",
      cache: false,
      success: function (result, textStatus) {
        // AJAX 下载文件的话这里会发两次请求，用 sc 来判断是否是文件，如果没有 sc 说明文件可以下载（实际上就是 result）
        if (!result.sc) {
          // 再发一次请求进行正式下载
          window.location = Label.servePath + "/console/export/hexo";
        } else {
          $("#tipMsg").text(result.msg);
        }
      }
    });
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
