/* SPDX-License-Identifier: AGPL-3.0-or-later */
admin.logs = {
  page: 1,
  through: null,
  requests: {},
  render: function (rows, target) {
    var escape = function (value) { return $('<span>').text(String(value == null ? '' : value)).html() }
    var markup = rows.map(function (entry) {
      var level = ['INFO','WARN','ERROR','FATAL','DEBUG','TRACE'].includes(entry.level) ? entry.level.toLowerCase() : 'other'
      var details = entry.throwable
      return '<tbody><tr><td><span class="console-log-level console-log-level--' + level + '">' + escape(entry.level) + '</span></td>' +
        '<td><time class="console-log-time">' + escape(String(entry.date || '').slice(0,19)) + '</time></td>' +
        '<td><span class="console-log-source">' + escape(entry.name + ':' + entry.lineNumber) + '</span><p>' + escape(entry.message) + '</p>' +
        (details ? '<details><summary>' + escape(details.class + ': ' + details.message) + '</summary><pre>' + (details.stackTrace || []).map(escape).join('\n') + '</pre></details>' : '') + '</td></tr></tbody>'
    }).join('')
    $('#' + target).html(markup || '<tbody><tr><td>暂无日志。</td></tr></tbody>')
  },
  load: function (recent, refresh) {
    var target = recent ? 'recentLogs' : 'logList', host = document.getElementById(target), that = this
    if (!host || this.requests[target]) return
    if (recent ? window.location.hash !== '#main' : window.location.hash !== '#tools/others/log') return
    if (refresh) { this.page = 1; this.through = null }
    var data = recent ? {} : {page: this.page, pageSize: 30}
    if (!recent && this.page > 1 && this.through) data.through = this.through
    this.requests[target] = true; host.setAttribute('aria-busy', 'true')
    $.ajax({url: Label.servePath + '/admin/logs', cache: false, timeout: 10000, dataType: 'json', data: data,
      success: function (result) {
        if (!host.isConnected || !result || !Array.isArray(result.result)) return
        that.render(recent ? result.result.slice(-15).reverse() : result.result, target)
        if (!recent) {
          that.page = result.page; that.through = result.through
          $('#memFree').text((result.freeMemNow / (1024 * 1024)).toFixed(2) + ' MB')
          $('#now').text(new Date().toLocaleTimeString())
          $('#logRetention').text('共 ' + result.total + ' 条 · ' + (result.persistent ? '本地日志按容量轮换，保留范围内可分页查看。' : '日志文件不可用，当前显示最近 100 条内存记录。'))
          admin.pagination.render('logPagination', result.page, result.pageCount, function (page) { that.page = page; that.load(false) })
        }
      },
      complete: function () { that.requests[target] = false; host.removeAttribute('aria-busy') }
    })
  }
}
