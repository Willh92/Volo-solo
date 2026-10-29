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
(function () {
  'use strict'
  if (!document.body.classList.contains('bolo-console')) return
  var palette = {forest: ['森林绿', '#246b51'], ocean: ['海蓝', '#2d6583'], teal: ['青绿', '#286c70'], amber: ['琥珀', '#855c27'], plum: ['紫藤', '#70517d'], rose: ['玫瑰', '#87525f']}
  var host = document.getElementById('consoleAccent')
  if (host && window.Label && Label.userRole === 'adminRole') {
    host.innerHTML = '<details class="console-accent"><summary aria-label="选择站点强调色" title="站点强调色"><i class="console-accent-dot" aria-hidden="true"></i></summary><div class="console-accent-panel"><p>站点强调色</p><div class="console-accent-swatches">' + Object.keys(palette).map(function (name) {
      return '<button type="button" data-accent="' + name + '" style="background:' + palette[name][1] + '" aria-label="' + palette[name][0] + '" title="' + palette[name][0] + '" aria-pressed="' + (BoloTheme.accent() === name) + '"></button>'
    }).join('') + '</div><small role="status">用于登录页、后台及 Studio 前台皮肤</small></div></details>'
    var saving = false
    host.addEventListener('click', async function (event) {
      var swatch = event.target.closest('[data-accent]')
      if (!swatch || saving) return
      saving = true
      var status = host.querySelector('[role="status"]')
      status.textContent = '正在保存…'
      try {
        var response = await fetch(Label.servePath + '/console/appearance', {method: 'PUT', credentials: 'same-origin',
          headers: {'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest'}, body: JSON.stringify({accent: swatch.dataset.accent})})
        var result = await response.json()
        if (!response.ok || !result.sc) throw new Error(result.msg || '保存失败，请重试。')
        BoloTheme.setAccent(result.accent)
        charts()
        host.querySelectorAll('[data-accent]').forEach(function (item) { item.setAttribute('aria-pressed', String(item.dataset.accent === result.accent)) })
        status.textContent = '已保存，默认皮肤前后台同步生效'
      } catch (error) { status.textContent = error.message || '保存失败，请重试。' }
      finally { saving = false }
    })
  }
  function charts () {
    if (!window.Chart) return
    var color = getComputedStyle(document.body).getPropertyValue('--bolo-muted').trim()
    var styles = getComputedStyle(document.body)
    var line = styles.getPropertyValue('--bolo-line').trim()
    var surface = styles.getPropertyValue('--bolo-surface').trim()
    var accent = styles.getPropertyValue('--bolo-accent').trim()
    var palette = ['#638e7c', '#7694a0', '#ad956d', '#9383a2', '#ad828c']
    Chart.defaults.global.defaultFontColor = color
    Object.values(Chart.instances || {}).forEach(function (chart) {
      if (chart.options.legend) chart.options.legend.labels.fontColor = color
      if (chart.options.title) chart.options.title.fontColor = color
      Object.values(chart.options.scales || {}).forEach(function (axes) {
        if (!Array.isArray(axes)) return
        axes.forEach(function (axis) {
          if (axis.gridLines) { axis.gridLines.color = line; axis.gridLines.zeroLineColor = line }
          if (axis.ticks) axis.ticks.fontColor = color
        })
      })
      chart.data.datasets.forEach(function (data) {
        if (chart.config.type === 'line') {
          data.borderColor = accent; data.pointBackgroundColor = accent; data.pointBorderColor = surface
        } else { data.backgroundColor = palette; data.borderColor = surface; data.borderWidth = 1 }
      })
      chart.update(0)
    })
  }
  function appearance () {
    charts()
    if (window.admin && admin.editors && window.BoloTheme) {
      Object.values(admin.editors).forEach(function (wrapper) {
        if (wrapper && wrapper.editor && typeof wrapper.editor.setTheme === 'function') {
          wrapper.editor.setTheme(BoloTheme.current() === 'dark' ? 'dark' : 'classic')
        }
      })
    }
  }
  charts()
  window.addEventListener('bolo:theme-change', appearance)
  window.addEventListener('bolo:console-charts-ready', charts)
  var closeMenu = function (event) {
    if (event.key === 'Escape') admin.toggleMenu(false)
    if (host && (event.key === 'Escape' || event.type === 'click' && !host.contains(event.target))) {
      var selector = host.querySelector('details')
      if (selector) selector.open = false
    }
    if (event.type === 'click' && event.target.closest('#tabs a[href^="#"]') && window.matchMedia('(max-width: 768px)').matches) admin.toggleMenu(false)
  }
  document.addEventListener('keydown', closeMenu)
  document.addEventListener('click', closeMenu)
  if (window.BoloNavigation) BoloNavigation.onLeave(function () {
    window.removeEventListener('bolo:theme-change', appearance)
    window.removeEventListener('bolo:console-charts-ready', charts)
    document.removeEventListener('keydown', closeMenu)
    document.removeEventListener('click', closeMenu)
    if (window.Chart) Object.values(Chart.instances || {}).forEach(function (chart) { chart.destroy() })
  })
})()
