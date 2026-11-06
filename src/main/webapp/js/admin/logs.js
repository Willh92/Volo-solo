/* SPDX-License-Identifier: AGPL-3.0-or-later */
admin.logs = {
  page: 1,
  through: null,
  requests: {},
  render: function (rows, target) {
    var escape = function (value) {
      return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
    }
    var formatDate = function (value) {
      if (value === null || value === undefined || typeof value === 'boolean') return '—'
      if (typeof value === 'string') {
        value = value.trim()
        if (!value) return '—'
        if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(value)) return value.slice(0, 19)
        if (/^\d+$/.test(value)) value = Number(value)
      }
      if (typeof Util !== 'undefined' && Util && typeof Util.formatDate === 'function') {
        return Util.formatDate(value, 1)
      }
      var d = value instanceof Date ? value : new Date(value)
      var time = d.getTime()
      if (!Number.isFinite(time) || time < 0) return '—'
      var pad = function (n) { return (n < 10 ? '0' : '') + n }
      return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
        ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
    }
    if (!Array.isArray(rows) || !rows.length) {
      $('#' + target).html('<tbody><tr><td colspan="3">暂无日志。</td></tr></tbody>')
      return
    }
    var markup = '<tbody>' + rows.map(function (entry) {
      var level = ['INFO','WARN','ERROR','FATAL','DEBUG','TRACE'].includes(entry.level) ? entry.level.toLowerCase() : 'other'
      var details = entry.throwable
      return '<tr><td><span class="console-log-level console-log-level--' + level + '">' + escape(entry.level) + '</span></td>' +
        '<td><time class="console-log-time">' + escape(formatDate(entry.date)) + '</time></td>' +
        '<td><span class="console-log-source">' + escape(entry.name + ':' + entry.lineNumber) + '</span><p>' + escape(entry.message) + '</p>' +
        (details ? '<details><summary>' + escape(details.class + ': ' + details.message) + '</summary><pre>' + (details.stackTrace || []).map(escape).join('\n') + '</pre></details>' : '') + '</td></tr>'
    }).join('') + '</tbody>'
    $('#' + target).html(markup)
  },
  load: function (recent, refresh) {
    var target = recent ? 'recentLogs' : 'logList', host = document.getElementById(target), that = this
    if (!host || this.requests[target]) return
    if (recent ? window.location.hash !== '#main' : window.location.hash !== '#tools/others/log') return
    if (refresh) { this.page = 1; this.through = null }
    var data = recent ? {} : {page: this.page, pageSize: 30}
    if (!recent && this.page > 1 && this.through) data.through = this.through
    this.requests[target] = true; host.setAttribute('aria-busy', 'true')
    $.ajax({url: Label.servePath + '/admin/logs' + (recent ? '?limit=15' : ''), cache: false, timeout: 10000, dataType: 'json', data: data,
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
