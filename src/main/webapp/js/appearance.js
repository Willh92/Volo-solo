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
/* Shared appearance preference for the blog, authentication pages and console. */
(function () {
  'use strict'
  if (window.BoloTheme) { window.BoloTheme.refresh(); return }
  var key = 'bolo.appearance.mode'
  var modes = {auto: '按时间自动', system: '跟随系统', light: '浅色', dark: '深色'}
  var media = window.matchMedia('(prefers-color-scheme: dark)')
  var mode = 'auto'
  try { mode = localStorage.getItem(key) || 'auto' } catch (error) { /* Private storage may be disabled. */ }
  if (!modes[mode]) mode = 'auto'
  var resolved
  var accents = ['forest', 'ocean', 'teal', 'amber', 'plum', 'rose']
  var accentMeta = document.querySelector('meta[name="bolo-accent"]')
  var accent = accentMeta && accents.includes(accentMeta.content) ? accentMeta.content : 'forest'
  var root = document.documentElement
  root.dataset.boloPage = /\/admin-index\.do$/.test(location.pathname) ? 'console' : /\/(?:start|root)\/?$/.test(location.pathname) ? 'auth' : 'public'
  var initialSkin = document.querySelector('meta[name="bolo-skin"]')
  root.dataset.boloSkin = initialSkin ? initialSkin.content : ''
  var icons = {
    light: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>',
    dark: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.7 13A8.8 8.8 0 0 1 11 3.3 8.8 8.8 0 1 0 20.7 13Z"/></svg>',
    auto: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2"/></svg>',
    system: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/></svg>',
  }

  function triggerIcon (value) {
    // Font Awesome 4.7 (SIL OFL 1.1): match the filled glyphs of the native search tools.
    if (['bolo-sakura', 'bolo-pinghsu'].includes(root.dataset.boloSkin) && !document.body?.classList.contains('utility-page')) {
      var dark = value === 'dark'
      var path = dark ? 'M1262 233q-54 -9 -110 -9q-182 0 -337 90t-245 245t-90 337q0 192 104 357q-201 -60 -328.5 -229t-127.5 -384q0 -130 51 -248.5t136.5 -204t204 -136.5t248.5 -51q144 0 273.5 61.5t220.5 171.5zM1465 318q-94 -203 -283.5 -324.5t-413.5 -121.5q-156 0 -298 61 t-245 164t-164 245t-61 298q0 153 57.5 292.5t156 241.5t235.5 164.5t290 68.5q44 2 61 -39q18 -41 -15 -72q-86 -78 -131.5 -181.5t-45.5 -218.5q0 -148 73 -273t198 -198t273 -73q118 0 228 51q41 18 72 -13q14 -14 17.5 -34t-4.5 -38z' : 'M1472 640q0 117 -45.5 223.5t-123 184t-184 123t-223.5 45.5t-223.5 -45.5t-184 -123t-123 -184t-45.5 -223.5t45.5 -223.5t123 -184t184 -123t223.5 -45.5t223.5 45.5t184 123t123 184t45.5 223.5zM1748 363q-4 -15 -20 -20l-292 -96v-306q0 -16 -13 -26q-15 -10 -29 -4 l-292 94l-180 -248q-10 -13 -26 -13t-26 13l-180 248l-292 -94q-14 -6 -29 4q-13 10 -13 26v306l-292 96q-16 5 -20 20q-5 17 4 29l180 248l-180 248q-9 13 -4 29q4 15 20 20l292 96v306q0 16 13 26q15 10 29 4l292 -94l180 248q9 12 26 12t26 -12l180 -248l292 94 q14 6 29 -4q13 -10 13 -26v-306l292 -96q16 -5 20 -20q5 -16 -4 -29l-180 -248l180 -248q9 -12 4 -29z'
      return '<svg class="appearance-icon appearance-icon--glyph" viewBox="0 0 1792 1792" aria-hidden="true"><path transform="translate(' + (dark ? 128 : 0) + ' 1536) scale(1 -1)" d="' + path + '"/></svg>'
    }
    return icons[value]
  }

  function resolve () {
    if (mode === 'light' || mode === 'dark') return mode
    if (mode === 'system') return media.matches ? 'dark' : 'light'
    var hour = new Date().getHours()
    return hour >= 7 && hour < 19 ? 'light' : 'dark'
  }

  function apply () {
    var next = resolve()
    var changed = resolved !== next
    resolved = next
    root.dataset.boloTheme = resolved
    root.dataset.theme = resolved // Butterfly and comment integrations use this attribute.
    root.dataset.boloThemeMode = mode
    root.dataset.boloAccent = accent
    root.style.colorScheme = resolved
    document.querySelectorAll('img[src*="/github-stats/"]').forEach(function (image) {
      var url = new URL(image.src, location.href)
      if (url.origin === location.origin && url.searchParams.get('theme') !== resolved) {
        url.searchParams.set('theme', resolved)
        image.src = url.href
      }
    })
    var meta = document.querySelector('meta[name="theme-color"]')
    if (meta) meta.content = resolved === 'dark' ? '#1b2021' : '#f6f7f3'
    document.querySelectorAll('[data-bolo-theme-toggle]').forEach(function (button) {
      button.title = '外观：' + modes[mode] + '（当前' + modes[resolved] + '）'
      button.setAttribute('aria-label', button.title)
      var symbol = button.querySelector('.appearance-icon') || button.querySelector('svg')
      if (symbol) symbol.outerHTML = triggerIcon(resolved)
      button.querySelector('span').textContent = modes[mode]
    })
    document.querySelectorAll('[data-bolo-mode]').forEach(function (button) {
      button.setAttribute('aria-checked', String(button.dataset.boloMode === mode))
    })
    if (changed) {
      var editors = window.admin && window.admin.editors ? Object.values(window.admin.editors).map(function (wrapper) { return wrapper && wrapper.editor }) : []
      if (window.vditor) editors.push(window.vditor)
      editors.forEach(function (editor) {
        if (editor && typeof editor.setTheme === 'function') editor.setTheme(resolved === 'dark' ? 'dark' : 'classic', resolved, resolved === 'dark' ? 'github-dark' : window.Label && Label.hljsStyle || 'github')
      })
    }
    if (changed) window.dispatchEvent(new CustomEvent('bolo:theme-change', {detail: {theme: resolved, mode: mode}}))
  }

  function close (focus) {
    document.querySelectorAll('.bolo-appearance').forEach(function (widget) {
      var menu = widget._appearanceMenu || widget.querySelector('[role="menu"]')
      var wasOpen = !menu.hidden
      menu.hidden = true
      if (menu.parentElement !== widget) widget.appendChild(menu)
      var trigger = widget.querySelector('[data-bolo-theme-toggle]')
      trigger.setAttribute('aria-expanded', 'false')
      if (focus && wasOpen) trigger.focus()
    })
  }

  function syncNativeControls () {
    var skin = root.dataset.boloSkin
    if (skin !== 'bolo-emiya' && skin !== 'bolo-yilia') return
    var name = skin === 'bolo-emiya' ? 'jump' : 'upbounce'
    var selector = (skin === 'bolo-emiya' ? '.toTop img' : '.icon-goup') + ', .bolo-appearance--floating [data-bolo-theme-toggle], .mobile-reading-toc .reading-toc-panel:not([open])'
    requestAnimationFrame(function () {
      var controls = document.querySelectorAll(selector)
      var animations = Array.from(controls).map(function (element) {
        return element.getAnimations?.().find(function (animation) { return animation.animationName === name })
      }).filter(Boolean)
      var start = animations.find(function (animation) { return typeof animation.startTime === 'number' })?.startTime
      if (typeof start === 'number') animations.forEach(function (animation) { animation.startTime = start })
    })
  }

  function headerSlot () {
    var slots = Array.from(document.querySelectorAll('[data-bolo-theme-slot]'))
    return slots.find(function (slot) {
      if (!slot.getClientRects) return true
      if (!slot.getClientRects().length || window.getComputedStyle(slot).visibility === 'hidden') return false
      var rect = slot.getBoundingClientRect()
      return rect.right >= 0 && rect.left <= window.innerWidth
    }) || slots.find(function (slot) { return slot.hasAttribute('data-bolo-theme-persistent') }) || null
  }

  function moveHeaderControl () {
    var slot = headerSlot()
    var widget = document.querySelector('.bolo-appearance')
    if (!widget || !widget.classList) return
    if (slot) {
      if (widget.parentElement !== slot) { close(false); slot.appendChild(widget) }
      widget.classList.remove('bolo-appearance--floating', 'bolo-appearance--native')
      widget.classList.toggle('bolo-appearance--header', !!slot.classList?.contains('skin-theme-slot'))
    } else if (widget.classList.contains('bolo-appearance--header')) {
      document.body.appendChild(widget)
      widget.classList.remove('bolo-appearance--header')
      widget.classList.add('bolo-appearance--floating')
    }
  }

  function positionHeaderMenu (widget) {
    var toggle = widget.querySelector('[data-bolo-theme-toggle]')
    var menu = widget._appearanceMenu || widget.querySelector('[role="menu"]')
    widget._appearanceMenu = menu
    // Legacy headers and drawers often clip descendants or establish a transformed
    // containing block. Keep the open menu in the document overlay, outside them.
    menu.id = 'voloAppearanceMenu'
    document.body.appendChild(menu)
    var rect = toggle.getBoundingClientRect()
    var width = Math.min(250, window.innerWidth - 24)
    menu.style.width = width + 'px'
    menu.style.left = Math.max(12, Math.min(window.innerWidth - width - 12, rect.right - width)) + 'px'
    var height = menu.getBoundingClientRect().height
    var top = Math.max(12, rect.bottom + 8 + height <= window.innerHeight - 12 ? rect.bottom + 8 : rect.top - height - 8)
    menu.style.top = top + 'px'
  }

  function mount () {
    if (!document.body) return
    root.dataset.boloPage = document.body.classList.contains('bolo-console') ? 'console' : document.body.classList.contains('auth-page') ? 'auth' : 'public'
    var skin = document.querySelector('meta[name="bolo-skin"]')
    root.dataset.boloSkin = skin ? skin.content : ''
    accentMeta = document.querySelector('meta[name="bolo-accent"]')
    if (accentMeta && accents.includes(accentMeta.content)) accent = accentMeta.content
    if (!document.querySelector('.bolo-appearance')) {
      var slot = headerSlot()
      var native = !slot && root.dataset.boloSkin === 'bolo-butterfly' ? document.getElementById('darkmode') : null
      var widget = document.createElement('div')
      widget.id = 'voloAppearance'
      widget.className = 'bolo-appearance' + (native ? ' bolo-appearance--native' : slot?.classList?.contains('skin-theme-slot') ? ' bolo-appearance--header' : slot ? '' : ' bolo-appearance--floating')
      widget.innerHTML = '<button type="button" data-bolo-theme-toggle aria-haspopup="menu" aria-expanded="false">' + triggerIcon(resolved) + '<span>按时间自动</span></button>' +
        '<div role="menu" aria-label="外观模式" hidden>' + Object.keys(modes).map(function (value) {
          return '<button type="button" role="menuitemradio" data-bolo-mode="' + value + '" aria-checked="false"><span>' + icons[value] + modes[value] + '</span><small>' + (value === 'auto' ? '07:00–19:00 浅色' : value === 'system' ? '使用设备设置' : '始终使用' + modes[value]) + '</small></button>'
        }).join('') + '</div>'
      if (native) {
        native.parentNode.insertBefore(widget, native)
        var trigger = widget.querySelector('[data-bolo-theme-toggle]')
        native.innerHTML = trigger.innerHTML
        native.setAttribute('data-bolo-theme-toggle', '')
        native.setAttribute('aria-haspopup', 'menu')
        native.setAttribute('aria-expanded', 'false')
        trigger.replaceWith(native)
      } else (slot || document.body).appendChild(widget)
    }
    moveHeaderControl()
    apply()
    syncNativeControls()
  }

  function set (value) {
    if (!modes[value]) return
    mode = value
    try { localStorage.setItem(key, mode) } catch (error) { /* Still works for this page. */ }
    apply()
  }

  window.BoloTheme = {set: set, refresh: mount, apply: apply, closeMenu: function () { close(false) }, syncControls: syncNativeControls, current: function () { return resolved }, mode: function () { return mode },
    accent: function () { return accent }, setAccent: function (value) {
      if (!accents.includes(value)) return
      accent = value
      if (accentMeta) accentMeta.content = value
      try { localStorage.setItem('bolo.appearance.accent', value) } catch (error) { }
      apply()
    },
  }
  apply() // Resolve before styles load to avoid a bright flash at night.
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, {once: true})
  else mount()
  window.addEventListener('bolo:after-navigate', mount)
  window.addEventListener('bolo:before-navigate', function () { close(false) })
  window.addEventListener('resize', function () { close(false); moveHeaderControl() })
  window.addEventListener('scroll', function () { close(false) }, {passive: true})
  document.addEventListener('transitionend', function (event) { if (event.propertyName === 'transform' || event.propertyName === 'left') moveHeaderControl() })
  document.addEventListener('pjax:complete', mount)
  document.addEventListener('visibilitychange', function () { if (!document.hidden) apply() })
  window.addEventListener('storage', function (event) {
    if (event.key === key) { mode = modes[event.newValue] ? event.newValue : 'auto'; apply() }
    if (event.key === 'bolo.appearance.accent' && accents.includes(event.newValue)) {
      accent = event.newValue
      if (accentMeta) accentMeta.content = accent
      apply()
    }
  })
  media.addEventListener('change', function () { if (mode === 'system') apply() })
  setInterval(function () { if (mode === 'auto') apply() }, 30000)
  // Older skin scripts may set data-theme once during startup. Keep one source of truth.
  new MutationObserver(function () { if (root.dataset.theme !== resolved) root.dataset.theme = resolved }).observe(root, {attributes: true, attributeFilter: ['data-theme']})
  document.addEventListener('click', function (event) {
    var option = event.target.closest('[data-bolo-mode]')
    if (option) { set(option.dataset.boloMode); close(true); return }
    var toggle = event.target.closest('[data-bolo-theme-toggle]')
    if (toggle) {
      event.preventDefault()
      event.stopImmediatePropagation() // An adopted native control must not also run its old toggle handler.
      var open = toggle.getAttribute('aria-expanded') !== 'true'
      if (open && window.BoloTOC) BoloTOC.close()
      close(false)
      toggle.setAttribute('aria-expanded', String(open))
      var widget = toggle.parentElement
      var menu = widget._appearanceMenu || widget.querySelector('[role="menu"]')
      menu.hidden = !open
      if (open) { positionHeaderMenu(widget); menu.querySelector('[aria-checked="true"]').focus({preventScroll: true}) }
      return
    }
    if (event.target.closest('#darkmode')) {
      event.preventDefault(); event.stopImmediatePropagation()
      set(resolved === 'dark' ? 'light' : 'dark')
      return
    }
    if (!event.target.closest('.bolo-appearance')) close(false)
  }, true)
  document.addEventListener('keydown', function (event) {
    var menu = event.target.closest('#voloAppearanceMenu, .bolo-appearance [role="menu"]')
    if (event.key === 'Escape') {
      close(!!menu)
      if (menu) { event.preventDefault(); event.stopImmediatePropagation() }
    }
    if (menu && ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault()
      var items = Array.from(menu.querySelectorAll('button'))
      var index = items.indexOf(document.activeElement)
      index = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
      items[index].focus()
    }
  })
})()
