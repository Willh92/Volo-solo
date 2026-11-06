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
 * @fileoverview util and every page should be used.
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

if (typeof window !== 'undefined') {
  window.NProgress = window.NProgress || {
    start: function () {},
    done: function () {},
    configure: function () {},
    inc: function () {},
  }
}

/**
 * @description Util
 * @static
 */
var Util = {
  formatDate: function (value, mode) {
    if (value === null || value === undefined || typeof value === 'boolean') return '—'
    if (typeof value === 'string') {
      value = value.trim()
      if (!value) return '—'
      if (/^\d+$/.test(value)) value = Number(value)
    }
    var d = value instanceof Date ? value : new Date(value)
    var time = d.getTime()
    if (!Number.isFinite(time) || time < 0) return '—'
    var pad = function (n) { return (n < 10 ? '0' : '') + n }
    var ymd = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    if (mode === 1) {
      return ymd + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
    }
    if (mode === 2) {
      return ymd + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
    }
    return ymd
  },
  chooseBanDuration: function () {
    if (typeof swal !== 'function') return Promise.resolve(null)
    var label = document.createElement('label'), caption = document.createElement('span'), select = document.createElement('select')
    label.className = 'volo-ban-duration'; caption.textContent = '封禁时长'
    ;[[0,'永久'],[1,'1 天'],[3,'3 天'],[7,'7 天'],[30,'30 天']].forEach(function (item) {
      var option = document.createElement('option'); option.value = String(item[0] * 86400); option.textContent = item[1]; select.append(option)
    })
    select.value = '0'
    label.append(caption, select)
    return swal({title:'封禁评论来源',text:'封禁此评论的来源 IP。可在后台“安全 → IP 封禁”中提前解除。',content:label,icon:'warning',buttons:{cancel:'取消',confirm:'封禁'}})
      .then(function (confirmed) { return confirmed ? Number(select.value) : null })
  },
  generatedAvatar: function (seed) {
    var name = String(seed || '').normalize('NFKC').trim(), text = name.toLowerCase(), hash = 2166136261
    for (var i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619) >>> 0
    var color = 'hsl(' + (hash % 360) + ',38%,34%)'
    var words = name.replace(/([\p{Ll}\p{N}])(\p{Lu})/gu, '$1 $2').match(/[\p{L}\p{N}]+/gu) || []
    var first = Array.from(words[0] || '')
    var initials = !first.length ? '访' : /\p{Script=Han}/u.test(first[0]) ? first[0]
      : words.length > 1 ? first[0] + Array.from(words[1])[0] : first.slice(0, 2).join('')
    initials = Array.from(initials.toUpperCase()).slice(0, 2).join('')
    var escaped = initials.replace(/[&<>"']/g, character => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&apos;'}[character]))
    return 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><circle cx="24" cy="24" r="24" fill="' + color + '"/><text x="24" y="24" dy=".35em" text-anchor="middle" fill="#fff" font-family="Segoe UI,Microsoft YaHei,Arial,sans-serif" font-size="' + (Array.from(initials).length > 1 ? 19 : 23) + '" font-weight="500">' + escaped + '</text></svg>')
  },
  bindCommentAvatars: function (root) {
    if (!root) return
    root.querySelectorAll('img[data-avatar-seed]').forEach(function (image) {
      function fallback () {
        image.onerror = null
        image.src = Util.generatedAvatar(image.getAttribute('data-avatar-seed'))
      }
      image.onerror = fallback
      var source = image.getAttribute('src') || ''
      if (!source || /\/images\/volo-mark\.svg(?:[?#]|$)/.test(source) || (image.complete && image.naturalWidth === 0)) fallback()
    })
  },
  _syncVditorGlobals: function (candidate) {
    const owner = window.BoloEditorCore || (window.BoloEditorCore = {})
    const editable = fn => typeof fn === 'function' && typeof fn.prototype?.getValue === 'function'
    const copyStatics = (source, target) => {
      if (!source || (typeof source !== 'function' && typeof source !== 'object') || !target) return
      Object.getOwnPropertyNames(source).forEach(key => {
        if (key !== 'length' && key !== 'name' && key !== 'prototype' &&
          typeof source[key] === 'function' && typeof target[key] !== 'function') {
          target[key] = source[key]
        }
      })
    }
    const current = candidate || globalThis.Vditor
    if (typeof current === 'function') {
      if (editable(current)) {
        owner.Editor = current
      } else {
        owner.renderMethods = owner.renderMethods || {}
        copyStatics(current, owner.renderMethods)
      }
    }
    if (editable(owner.Editor)) {
      if (owner.renderMethods) copyStatics(owner.renderMethods, owner.Editor)
      if (typeof globalThis.Vditor === 'function' && globalThis.Vditor !== owner.Editor) {
        owner.renderMethods = owner.renderMethods || {}
        copyStatics(globalThis.Vditor, owner.renderMethods)
        copyStatics(globalThis.Vditor, owner.Editor)
      }
      if (!editable(globalThis.Vditor)) {
        globalThis.Vditor = owner.Editor
      }
    }
    return editable(globalThis.Vditor)
  },
  // Keep the editing runtime on the application's origin, even when a separate
  // static CDN is configured for skin assets. Render-only method.min.js is insufficient.
  loadVditor: function (callback) {
    const owner = window.BoloEditorCore || (window.BoloEditorCore = {})
    const editable = candidate => typeof candidate === 'function' && typeof candidate.prototype?.getValue === 'function'
    // A skin can reload method.min.js during PJAX and replace the global with its render-only API.
    Util._syncVditorGlobals()
    if (!editable(globalThis.Vditor) && editable(owner.Editor)) globalThis.Vditor = owner.Editor
    const complete = () => editable(globalThis.Vditor)
    if (complete()) {
      Util._syncVditorGlobals(globalThis.Vditor)
      return callback ? Promise.resolve().then(() => {
        Util._syncVditorGlobals()
        if (!editable(globalThis.Vditor) && editable(owner.Editor)) globalThis.Vditor = owner.Editor
        return callback()
      }) : Promise.resolve()
    }
    if (!owner.vditorLibrary) {
      owner.vditorLibrary = new Promise((resolve, reject) => {
        const script = document.createElement('script')
        const schedule = window.BoloNavigation?.setTimeout || setTimeout
        const cancel = window.BoloNavigation?.clearTimeout || clearTimeout
        const timeout = schedule(() => finish(new Error('Comment editor load timed out')), 20000)
        function finish (error) {
          cancel(timeout)
          script.onload = script.onerror = null
          if (error) { script.remove(); reject(error) } else { Util._syncVditorGlobals(Vditor); resolve() }
        }
        script.src = Label.servePath + '/js/lib/vditor/dist/index.min.js'
        script.setAttribute('data-bolo-navigation', '')
        script.async = true
        script.onload = () => finish(complete() ? null : new Error('Comment editor unavailable'))
        script.onerror = () => finish(new Error('Comment editor load failed'))
        document.head.appendChild(script)
      }).then(() => {
        owner.vditorLibrary = null
        Util._syncVditorGlobals()
        if (!editable(globalThis.Vditor) && editable(owner.Editor)) globalThis.Vditor = owner.Editor
      }, error => { owner.vditorLibrary = null; throw error })
    }
    return callback ? owner.vditorLibrary.then(() => {
      Util._syncVditorGlobals()
      if (!editable(globalThis.Vditor) && editable(owner.Editor)) globalThis.Vditor = owner.Editor
      return callback()
    }) : owner.vditorLibrary
  },
  loadVditorRuntime: function () {
    const languages = ['de_DE', 'en_US', 'es_ES', 'fr_FR', 'ja_JP', 'ko_KR', 'pt_BR', 'ru_RU', 'sv_SE', 'vi_VN', 'zh_CN', 'zh_TW']
    const requested = Label.localeString || Label.langLabel
    const language = languages.includes(requested) ? requested : 'zh_CN'
    const assets = window.BoloEditorAssets || (window.BoloEditorAssets = new Map())
    const translations = window.BoloEditorLanguages || (window.BoloEditorLanguages = new Map())
    const load = (part, id, ready, complete) => {
      const url = Label.servePath + '/js/lib/vditor/dist/js/' + part
      if (ready()) {
        if (!document.getElementById(id)) {
          const marker = document.createElement('script')
          marker.id = id
          marker.setAttribute('data-bolo-navigation', '')
          document.head.appendChild(marker)
        }
        return Promise.resolve()
      }
      if (assets.has(url)) return assets.get(url)
      const promise = new Promise((resolve, reject) => {
        const script = document.createElement('script')
        const schedule = window.BoloNavigation?.setTimeout || setTimeout
        const cancel = window.BoloNavigation?.clearTimeout || clearTimeout
        const timer = schedule(() => finish(new Error('Editor runtime load timed out')), 20000)
        function finish (error) {
          cancel(timer)
          script.onload = script.onerror = null
          if (error) { script.remove(); reject(error); return }
          complete?.()
          if (!ready()) { script.remove(); reject(new Error('Editor runtime unavailable')); return }
          script.id = id
          resolve()
        }
        script.src = url
        script.async = true
        script.setAttribute('data-bolo-navigation', '')
        script.onload = () => finish()
        script.onerror = () => finish(new Error('Editor runtime load failed'))
        document.head.appendChild(script)
      }).catch(error => { assets.delete(url); throw error })
      assets.set(url, promise)
      return promise
    }
    // The constructor, parser and language load in parallel and are shared by every editor.
    return Promise.all([
      this.loadVditor(),
      load('lute/lute.min.js', 'vditorLuteScript', () => typeof window.Lute?.New === 'function'),
      load('i18n/' + language + '.js', 'vditorI18nScript' + language,
        () => translations.has(language),
        () => { if (window.VditorI18n) translations.set(language, window.VditorI18n) }),
    ]).then(() => {
      const owner = window.BoloEditorCore || (window.BoloEditorCore = {})
      const editable = candidate => typeof candidate === 'function' && typeof candidate.prototype?.getValue === 'function'
      Util._syncVditorGlobals()
      if (!editable(globalThis.Vditor) && editable(owner.Editor)) globalThis.Vditor = owner.Editor
      return {i18n: translations.get(language)}
    })
  },
  isArticlePage: function (href) {
    var isArticle = true
    if (href.indexOf(Label.servePath + '/tags/') > -1) {
      isArticle = false
    }
    if (href.indexOf(Label.servePath + '/tags.html') > -1) {
      isArticle = false
    }
    if (href.indexOf(Label.servePath + '/category/') > -1) {
      isArticle = false
    }
    if (href.indexOf(Label.servePath + '/archives.html') > -1) {
      isArticle = false
    }
    if (href.indexOf(Label.servePath + '/archives/') > -1) {
      isArticle = false
    }
    if (href.indexOf(Label.servePath + '/links.html') > -1) {
      isArticle = false
    }
    if (href === Label.servePath) {
      isArticle = false
    }
    if (/^[0-9]*$/.test(href.replace(Label.servePath + '/', ''))) {
      isArticle = false
    }
    return isArticle
  },
  /**
   * 初始化 Pjax
   * @param cb 除文章外的其他页面加载回调
   */
  initPjax: function (cb) {
    if (window.BoloNavigation && window.BoloNavigation.publicPages) return
    if (typeof $ === 'function' && typeof $.pjax === 'function' && $('#pjax').length === 1) {
      $.pjax({
        selector: 'a',
        container: '#pjax',
        show: '',
        cache: false,
        storage: true,
        titleSuffix: '',
        filter: function (href, element) {
          if (!href) {
            return true
          }
          if (element.getAttribute('target') === '_blank') {
            return true
          }
          if (href === Label.servePath + '/rss.xml' ||
            href.indexOf(Label.servePath + '/start') === 0 ||
            href.indexOf(Label.servePath + '/logout') === 0 ||
            href.indexOf(Label.servePath + '/admin-index.do') > -1) {
            return true
          }
          // 目录
          if (href.indexOf('#') === 0) {
            return true
          }
          // 自定义导航
          if (element.href.indexOf(Label.servePath) > -1) {
            return false
          }
          return true
        },
        callback: function () {
          Util.parseMarkdown()
          cb && cb()
        },
      })
      if (window.NProgress) {
        window.NProgress.configure({showSpinner: false})
      }
      $('#pjax').bind('pjax.start', function () {
        if (window.NProgress) window.NProgress.start()
      })
      $('#pjax').bind('pjax.end', function () {
        window.scroll(window.scrollX, 0)
        if (window.NProgress) window.NProgress.done()
      })
    }
  },
  /**
   * 图片预览
   */
  previewImg: function () {
    var $body = $('body')
    if (typeof $body.off === 'function') {
      $body.off('click.boloPreviewImg', '.vditor-reset img')
    }
    $body.on('click.boloPreviewImg', '.vditor-reset img', function () {
      if ($(this).hasClass('prevent') || this.closest('a[href], .volo-bookshelf, .volo-lock-card')) {
        return
      }
      window.open(this.src)
    })
  },
  /**
   * 异步添加 css
   * @param url css 文件访问地址
   * @param id css 文件标示
   */
  addStyle: function (url, id) {
    if (!document.getElementById(id)) {
      var styleElement = document.createElement('link')
      styleElement.id = id
      styleElement.setAttribute('rel', 'stylesheet')
      styleElement.setAttribute('type', 'text/css')
      styleElement.setAttribute('href', url)
      document.getElementsByTagName('head')[0].appendChild(styleElement)
    }
  },
  /**
   * 异步添加 js（非阻塞 Promise 加载器，跨 PJAX 持久化缓存）
   * @param path js 文件访问地址
   * @param id js 文件标示
   * @param callback 加载完成后的可选回调
   * @returns {Promise<void>}
   */
  addScript: function (path, id, callback) {
    var promises = window._voloScriptPromises = window._voloScriptPromises || {}
    var loaded = window._voloLoadedScripts = window._voloLoadedScripts || {}
    var pendingCallbacks = window._voloScriptCallbacks = window._voloScriptCallbacks || {}
    var key = id || path

    if (loaded[key]) {
      if (typeof callback === 'function') {
        callback()
      }
      return promises[key] || (promises[key] = Promise.resolve())
    }

    if (promises[key]) {
      if (typeof callback === 'function') {
        ;(pendingCallbacks[key] || (pendingCallbacks[key] = [])).push(callback)
      }
      return promises[key]
    }

    if (id && typeof document.getElementById === 'function' && document.getElementById(id)) {
      loaded[key] = true
      promises[key] = Promise.resolve()
      if (typeof callback === 'function') {
        callback()
      }
      return promises[key]
    }

    pendingCallbacks[key] = typeof callback === 'function' ? [callback] : []
    var promise = new Promise(function (resolve, reject) {
      var script = document.createElement('script')
      script.async = true
      script.src = path
      if (id) {
        script.id = id
      }
      if (typeof script.setAttribute === 'function') {
        script.setAttribute('data-bolo-navigation', 'persist')
      }
      script.onload = function () {
        script.onload = script.onerror = null
        loaded[key] = true
        var list = pendingCallbacks[key] ? pendingCallbacks[key].splice(0) : []
        delete pendingCallbacks[key]
        resolve()
        for (var i = 0; i < list.length; i++) {
          try {
            list[i]()
          } catch (err) {
            if (typeof console !== 'undefined' && console.error) console.error(err)
          }
        }
      }
      script.onerror = function () {
        script.onload = script.onerror = null
        delete promises[key]
        delete loaded[key]
        delete pendingCallbacks[key]
        if (typeof script.remove === 'function') {
          script.remove()
        } else if (script.parentNode) {
          script.parentNode.removeChild(script)
        }
        reject(new Error('Script load failed: ' + path))
      }
      var head = document.head || (typeof document.getElementsByTagName === 'function' && document.getElementsByTagName('head')[0])
      if (head) {
        head.appendChild(script)
      }
    })
    promises[key] = promise
    return promise
  },
  _withVditorMethod: function (requiredMethod, render) {
    Util._syncVditorGlobals()
    if (typeof Vditor === 'function' && typeof Vditor[requiredMethod] === 'function') {
      render()
      return
    }
    var loadMethod = function () {
      Util._syncVditorGlobals()
      if (typeof Vditor === 'function' && typeof Vditor[requiredMethod] === 'function') {
        render()
        return
      }
      var loading = Util.addScript(
        Label.servePath + '/js/lib/vditor/dist/method.min.js',
        'vditorPreviewScript',
        function () {
          Util._syncVditorGlobals()
          if (window.BoloEditorCore && window.BoloEditorCore.Editor &&
            window.BoloEditorCore.Editor.prototype &&
            typeof window.BoloEditorCore.Editor.prototype.getValue === 'function') {
            window.Vditor = window.BoloEditorCore.Editor
          }
          render()
        })
      if (loading && typeof loading.catch === 'function') {
        loading.catch(function () {})
      }
    }
    var owner = typeof window !== 'undefined' && window.BoloEditorCore
    if (owner && owner.vditorLibrary && typeof owner.vditorLibrary.then === 'function') {
      owner.vditorLibrary.then(loadMethod, loadMethod)
      return
    }
    loadMethod()
  },
  /*
  * @description 解析语法高亮
  */
  parseLanguage: function () {
    var render = function () {
      if (typeof Vditor !== 'function' || typeof Vditor.highlightRender !== 'function') {
        return
      }
      Vditor.highlightRender({
        style: Label.hljsStyle,
        enable: !Label.luteAvailable,
      }, document, Label.servePath + '/js/lib/vditor')
    }
    Util._withVditorMethod('highlightRender', render)
  },
  copyCodeText: function (text) {
    function fallback () {
      var previous = document.activeElement, field = document.createElement('textarea')
      field.value = text
      field.readOnly = true
      field.tabIndex = -1
      field.setAttribute('autocomplete', 'off')
      field.setAttribute('aria-hidden', 'true')
      field.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;padding:0;border:0;opacity:0;font-size:16px;'
      try {
        document.body.appendChild(field)
        field.focus({preventScroll: true})
        field.select()
        field.setSelectionRange(0, text.length)
        return document.execCommand('copy') === true
      } catch (error) { return false }
      finally {
        field.remove()
        previous?.focus?.({preventScroll: true})
      }
    }
    if (window.navigator?.clipboard?.writeText) {
      try { return Promise.resolve(window.navigator.clipboard.writeText(text)).then(() => true, fallback) }
      catch (error) { return Promise.resolve(fallback()) }
    }
    return Promise.resolve(fallback())
  },
  prepareCodeCopyMenu: function (code, menu) {
    var field = menu.querySelector('textarea'), button = menu.querySelector('span[aria-label]')
    if (!field || !button) return
    var text = field.value, label = window.VditorI18n?.copy || '复制'
    // Persistent clipboard textareas can be mistaken for form controls during session restore.
    field.remove()
    button.removeAttribute('onclick')
    button.removeAttribute('onmouseover')
    button.setAttribute('role', 'button')
    button.tabIndex = 0
    button.onmouseover = function () { button.setAttribute('aria-label', label) }
    button.onclick = function (event) {
      event.preventDefault()
      event.stopPropagation()
      Util.copyCodeText(text).then(function (copied) {
        button.setAttribute('aria-label', copied ? (window.VditorI18n?.copied || '已复制') : '复制失败，请手动选择')
      })
    }
    button.onkeydown = function (event) { if (event.key === 'Enter' || event.key === ' ') button.onclick(event) }
  },
  /**
   * 按需加载数学公式、流程图、代码复制、五线谱、多媒体、图表
   * @returns {undefined}
   */
  parseMarkdown: function () {
    var selector = '.vditor-reset, pre code, [class*="language-"]'
    var $markdownElements = typeof $ === 'function' ? $(selector) : null
    var hasJqueryFilter = Boolean($markdownElements && typeof $markdownElements.filter === 'function')
    var markdownNodes = !hasJqueryFilter && typeof document !== 'undefined' && typeof document.querySelectorAll === 'function'
      ? Array.prototype.slice.call(document.querySelectorAll(selector))
      : null
    if (hasJqueryFilter ? $markdownElements.length === 0 : (markdownNodes && markdownNodes.length === 0)) {
      return
    }
    var render = function () {
      if (typeof Vditor !== 'function' || typeof Vditor.codeRender !== 'function' || !document.body) {
        return
      }
      if (hasJqueryFilter) {
        $markdownElements = $markdownElements.filter(function () {
          return document.body && (typeof document.body.contains !== 'function' || document.body.contains(this))
        })
        if ($markdownElements.length === 0) {
          return
        }
      } else if (markdownNodes) {
        markdownNodes = markdownNodes.filter(function (el) {
          return document.body && (typeof document.body.contains !== 'function' || document.body.contains(el))
        })
        if (markdownNodes.length === 0) {
          return
        }
      }
      Vditor.highlightRender({
        enable: !Label.luteAvailable,
        lineNumber: Label.showCodeBlockLn,
        style: Label.hljsStyle,
      }, document, Label.servePath + '/js/lib/vditor')

      var cdn = Label.servePath + '/js/lib/vditor'
      Vditor.codeRender(document.body, {renderMenu: Util.prepareCodeCopyMenu})
      Vditor.graphvizRender(document.body, cdn)
      Vditor.mathRender(document.body, { cdn: cdn })
      Vditor.abcRender(document.body, cdn)
      Vditor.chartRender(document.body, cdn, 'classic')
      Vditor.mindmapRender(document.body, cdn, 'classic')
      Vditor.mediaRender(document.body)
      Vditor.mermaidRender(document.body, cdn, 'classic')
      Vditor.flowchartRender(document.body, cdn)
    }
    Util._withVditorMethod('codeRender', render)
  },
  /**
   * @description IE6/7，跳转到 kill-browser 页面
   */
  killIE: function (ieVersion) {
    var addKillPanel = function () {
      if (Cookie.readCookie('showKill') === '') {
        try {
          var left = ($(window).width() - 781) / 2,
            top1 = ($(window).height() - 680) / 2
          var killIEHTML = '<div style=\'display: block; height: 100%; width: 100%; position: fixed; background-color: rgb(0, 0, 0); opacity: 0.6;filter: alpha(opacity=60); top: 0px;z-index:110\'></div>'
            + '<iframe style=\'left:' + left + 'px;z-index:120;top: ' + top1 +
            'px; position: fixed; border: 0px none; width: 781px; height: 680px;\' src=\'' +
            Label.servePath + '/kill-browser\'></iframe>'
          $('body').append(killIEHTML)
        } catch (e) {
          var left = 10,
            top1 = 0
          var killIEHTML = '<div style=\'display: block; height: 100%; width: 100%; position: fixed; background-color: rgb(0, 0, 0); opacity: 0.6;filter: alpha(opacity=60); top: 0px;z-index:110\'></div>'
            + '<iframe style=\'left:' + left + 'px;z-index:120;top: ' + top1 +
            'px; position: fixed; border: 0px none; width: 781px; height: 680px;\' src=\'' +
            Label.servePath + '/kill-browser\'></iframe>'
          document.body.innerHTML = document.body.innerHTML + killIEHTML
        }
      }
    }

    var ua = navigator.userAgent.split('MSIE')[1]
    if (ua) {
      if (!ieVersion) {
        ieVersion = 7
      }
      if (parseFloat(ua.split(';')) <= ieVersion) {
        addKillPanel()
      }
    }
  },
  /**
   * @description 切换到手机版
   * @param {String} skin 切换前的皮肤名称
   */
  switchMobile: function (skin) {
    Cookie.createCookie('btouch_switch_toggle', skin, 365)
    setTimeout(function () {
      location.reload()
    }, 1250)
  },
  /**
   * @description topbar 相关事件
   */
  setTopBar: function () {
    var $top = $('#top')
    if ($top.length === 1) {
      var $showTop = $('#showTop')
      $showTop.click(function () {
        $top.slideDown()
        $showTop.hide()
      })
      $('#hideTop').click(function () {
        $top.slideUp()
        $showTop.show()
      })
    }
  },
  /**
   * @description 回到顶部
   */
  goTop: function () {
    $('html, body').animate({scrollTop: 0}, 800)
  },
  /**
   * @description 回到底部
   */
  goBottom: function (bottom) {
    if (!bottom) {
      bottom = 0
    }
    $('html, body').
      animate({scrollTop: $(document).height() - $(window).height() - bottom},
        800)
  },
  _bindContactDisclosure: function () {
    if (window.voloContactDisclosureBound || typeof document.addEventListener !== 'function') return
    window.voloContactDisclosureBound = true
    var closeContactOutside = function (event) {
      document.querySelectorAll('.user__site--account[open]').forEach(function (contact) {
        if (!contact.contains(event.target)) contact.open = false
      })
    }
    var closeContactWithEscape = function (event) {
      if (event.key !== 'Escape') return
      document.querySelectorAll('.user__site--account[open]').forEach(function (contact) {
        contact.open = false
        contact.querySelector('summary').focus()
      })
    }
    document.addEventListener('click', closeContactOutside)
    document.addEventListener('keydown', closeContactWithEscape)
    window.BoloNavigation?.onLeave(function () {
      document.removeEventListener('click', closeContactOutside)
      document.removeEventListener('keydown', closeContactWithEscape)
      window.voloContactDisclosureBound = false
    })
  },
  /**
   * @description 页面初始化执行的函数
   */
  init: function () {
    Util._bindContactDisclosure()
    Util.killIE()
    Util.parseMarkdown()
    Util.initSW()
    Util.previewImg()
    Util.initDebugInfo()
  },
  /**
   * 调试区域文案
   */
  initDebugInfo: function () {
    /* console.log(
      '%cVolo%c\n  🎸一个从 Bolo 分支开发的博客系统。' + Label.version + ' © ' +
      (new Date).getFullYear(),
      'font-size:96px;color:#3b3e43', 'font-size:12px;color:rgba(0,0,0,0.38);') */
  },
  /**
   * @description 注册 Service Work
   */
  initSW: function () {
    if (navigator.serviceWorker) {
      navigator.serviceWorker.register('/sw.js', {scope: '/'})
    }
  },
  /**
   * @description 替换侧边栏表情为图片
   * @param {Dom} comments 评论内容元素
   */
  replaceSideEm: function (comments) {
    for (var i = 0; i < comments.length; i++) {
      var $comment = $(comments[i])
      $comment.html($comment.html())
    }
  },
  /**
   * @description 根据 tags，穿件云效果
   * @param {String} [id] tags 根元素 id，默认为 tags
   */
  buildTags: function (id) {
    id = id || 'tags'
    // 根据引用次数添加样式，产生云效果
    var classes = ['tags1', 'tags2', 'tags3', 'tags4', 'tags5'],
      bList = $('#' + id + ' b').get()
    var max = parseInt($('#' + id + ' b').last().text())
    var distance = Math.ceil(max / classes.length)
    for (var i = 0; i < bList.length; i++) {
      var num = parseInt(bList[i].innerHTML)
      // 算出当前 tag 数目所在的区间，加上 class
      for (var j = 0; j < classes.length; j++) {
        if (num > j * distance && num <= (j + 1) * distance) {
          bList[i].parentNode.className = classes[j]
          break
        }
      }
    }

    // 按字母或者中文拼音进行排序
    $('#' + id).html($('#' + id + ' li').get().sort(function (a, b) {
      var valA = $(a).find('span').text().toLowerCase()
      var valB = $(b).find('span').text().toLowerCase()
      // 对中英文排序的处理
      return valA.localeCompare(valB)
    }))
  },
  /**
   * @description 时间戳转化为时间格式
   * @param {String} time 时间
   * @param {String} format 格式化后日期格式
   * @returns {String} 格式化后的时间
   */
  toDate: function (time, format) {
    var dateTime = new Date(time)
    var o = {
      'M+': dateTime.getMonth() + 1, //month
      'd+': dateTime.getDate(), //day
      'H+': dateTime.getHours(), //hour
      'm+': dateTime.getMinutes(), //minute
      's+': dateTime.getSeconds(), //second
      'q+': Math.floor((dateTime.getMonth() + 3) / 3), //quarter
      'S': dateTime.getMilliseconds(), //millisecond
    }

    if (/(y+)/.test(format)) {
      format = format.replace(RegExp.$1,
        (dateTime.getFullYear() + '').substr(4 - RegExp.$1.length))
    }

    for (var k in o) {
      if (new RegExp('(' + k + ')').test(format)) {
        format = format.replace(RegExp.$1,
          RegExp.$1.length == 1 ? o[k] : ('00' + o[k]).substr(
            ('' + o[k]).length))
      }
    }
    return format
  },
}
Util._bindContactDisclosure()
if (!Cookie) {
  /**
   * @description Cookie 相关操作
   * @static
   */
  var Cookie = {
    /**
     * @description 读取 cookie
     * @param {String} name cookie key
     * @returns {String} 对应 key 的值，如 key 不存在则返回 ""
     */
    readCookie: function (name) {
      var nameEQ = name + '='
      var ca = document.cookie.split(';')
      for (var i = 0; i < ca.length; i++) {
        var c = ca[i]
        while (c.charAt(0) == ' ')
          c = c.substring(1, c.length)
        if (c.indexOf(nameEQ) == 0)
          return decodeURIComponent(c.substring(nameEQ.length, c.length))
      }
      return ''
    },
    /**
     * @description 清除 Cookie
     * @param {String} name 清除 key 为 name 的该条 Cookie
     */
    eraseCookie: function (name) {
      this.createCookie(name, '', -1)
    },
    /**
     * @description 创建 Cookie
     * @param {String} name 每条 Cookie 唯一的 key
     * @param {String} value 每条 Cookie 对应的值，将被 UTF-8 编码
     * @param {Int} days Cookie 保存时间
     */
    createCookie: function (name, value, days) {
      var expires = ''
      if (days) {
        var date = new Date()
        date.setTime(date.getTime() + (days * 24 * 60 * 60 * 1000))
        expires = '; expires=' + date.toGMTString()
      }
      document.cookie = name + '=' + encodeURIComponent(value) + expires +
        '; path=/'
    },
  }
}
