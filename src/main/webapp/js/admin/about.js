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
admin.about = {
  _cleanup: null,
  init: function () {
    var self = admin.about
    if (this && typeof this._cleanup === 'function') this._cleanup()
    if (self && typeof self._cleanup === 'function') self._cleanup()
    admin.clearTip()
    var node = document.getElementById('projectSyncStatus')
    if (!node) return
    var controller = new AbortController()
    var timer
    var disposed = false
    var target = this || self
    var cleanup = function () {
      if (disposed) return
      disposed = true
      controller.abort()
      clearTimeout(timer)
      if (self && self._cleanup === cleanup) self._cleanup = null
      if (target && target._cleanup === cleanup) target._cleanup = null
    }
    if (self) self._cleanup = cleanup
    if (target) target._cleanup = cleanup
    if (window.BoloNavigation) BoloNavigation.onLeave(cleanup)
    function text (id, value) { var element = document.getElementById(id); if (element) element.textContent = value || '—' }
    function date (value) {
      if (typeof Util !== 'undefined' && Util && typeof Util.formatDate === 'function') {
        return Util.formatDate(value, 1)
      }
      if (value === null || value === undefined || typeof value === 'boolean') return '—'
      if (typeof value === 'string') {
        value = value.trim()
        if (!value) return '—'
        if (/^\d+$/.test(value)) value = Number(value)
      }
      var parsed = value instanceof Date ? value : new Date(value)
      var time = parsed.getTime()
      if (!isFinite(time) || time < 0) return '—'
      var pad = function (n) { return (n < 10 ? '0' : '') + n }
      return parsed.getFullYear() + '-' + pad(parsed.getMonth() + 1) + '-' + pad(parsed.getDate()) +
        ' ' + pad(parsed.getHours()) + ':' + pad(parsed.getMinutes()) + ':' + pad(parsed.getSeconds())
    }
    async function load (remaining) {
      if (disposed || !node.isConnected) return
      try {
        var response = await fetch(Label.servePath + '/console/project-info', {credentials: 'same-origin', signal: controller.signal})
        if (!response.ok) throw new Error('GitHub information is unavailable')
        var info = await response.json()
        if (disposed || !node.isConnected) return
        if (info.available) {
          text('projectDescription', info.description || 'Volo — 从 Bolo 分支开发的 Java 博客系统。')
          text('projectStars', String(info.stars)); text('projectForks', String(info.forks)); text('projectIssues', String(info.issues))
          text('projectLanguage', info.language); text('projectBranch', info.branch); text('projectLicense', info.license); text('projectPushed', date(info.pushedAt))
          if (info.release) {
            text('projectRelease', info.release.name || info.release.tag)
            var tag = /^v?(\d+)\.(\d+)(?:\.(\d+))?$/.exec(info.release.tag)
            var current = String(boloVersion).match(/\d+/g) || []
            var newer = false
            if (tag) for (var i = 0; i < 3; i++) { var delta = Number(tag[i + 1] || 0) - Number(current[i] || 0); if (delta) { newer = delta > 0; break } }
            text('updateCheck', newer ? '仓库有新版本，可查看发布说明后更新。' : 'GitHub 最新正式发布：' + info.release.tag)
            var link = new URL(info.release.url)
            if (link.origin === 'https://github.com' && link.pathname.startsWith('/Willh92/Volo-solo/releases/')) document.getElementById('projectReleaseLink').href = link.href
          } else {
            text('projectRelease', '仓库尚未发布正式 Release')
            text('updateCheck', '当前运行版本为 ' + boloVersion + '。可在 GitHub 查看项目动态。')
          }
          text('projectSyncStatus', (info.stale ? '正在显示上次同步的数据 · ' : '已从 GitHub 同步 · ') + date(info.syncedAt))
        } else if (!info.loading) {
          text('projectDescription', 'Volo — 从 Bolo 分支开发的 Java 博客系统。')
          text('projectRelease', '发布信息暂不可用')
          text('projectSyncStatus', '暂时无法连接 GitHub，可通过仓库链接查看。')
        }
        if (!disposed && info.loading && remaining > 0) timer = setTimeout(function () { load(remaining - 1) }, 1000)
        else if (!info.available && info.loading) text('projectSyncStatus', '读取耗时较长，可稍后重新打开关于页。')
      } catch (error) {
        if (error.name !== 'AbortError') text('projectSyncStatus', '项目信息暂不可用，可通过仓库链接查看。')
      }
    }
    load(7)
  },
}
admin.register.about = {obj: admin.about, init: admin.about.init, refresh: admin.about.init}
