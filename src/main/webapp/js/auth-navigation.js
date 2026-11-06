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
/* Shared PJAX navigation for every skin, login page and management console. */
(function () {
  'use strict'
  if (window.BoloNavigation) return

  var rendered = new URL(window.location.href)
  var generation = 0
  var pending
  var cleanups = []
  var heldJQueries = []
  var scriptTypes = /^(?:application|text)\/(?:java|ecma)script$/i
  var persistentAttribute = 'data-bolo-navigation'
  var loadedPersistentScripts = new Set()
  var onceScriptPaths = new Set()
  var persistentExecutionDepth = 0

  function normalizeScriptPath (src) {
    if (!src) return ''
    try { return new URL(src, window.location.href).pathname } catch (e) { return src }
  }

  function scanDocumentPersistentScripts () {
    if (typeof document.querySelectorAll !== 'function') return
    Array.from(document.querySelectorAll('script[' + persistentAttribute + ']')).forEach(function (s) {
      if (!s.src) return
      var path = normalizeScriptPath(s.src)
      var mode = typeof s.getAttribute === 'function' ? s.getAttribute(persistentAttribute) : ''
      if (mode === 'once') onceScriptPaths.add(path)
      else loadedPersistentScripts.add(path)
    })
  }
  scanDocumentPersistentScripts()

  var pageCleanups = []
  var nativeTimeout = window.setTimeout.bind(window)
  var nativeClearTimeout = window.clearTimeout.bind(window)

  function isPersistentScript () {
    return persistentExecutionDepth > 0 || Boolean(document.currentScript && document.currentScript.hasAttribute(persistentAttribute))
  }

  function runPersistent (fn) {
    persistentExecutionDepth++
    try {
      return fn()
    } finally {
      persistentExecutionDepth--
    }
  }

  // Legacy decorations remain active on their page. Release their timers and global
  // listeners when replacing that page, so a second visit does not duplicate them.
  ;[['setTimeout', 'clearTimeout'], ['setInterval', 'clearInterval'], ['requestAnimationFrame', 'cancelAnimationFrame']].forEach(function (names) {
    var schedule = window[names[0]].bind(window)
    var cancel = window[names[1]].bind(window)
    var handles = new Set()
    window[names[0]] = function (callback) {
      if (isPersistentScript()) {
        if (typeof callback === 'function') {
          var persistentArgs = Array.from(arguments)
          persistentArgs[0] = function () {
            var self = this, callArgs = arguments
            return runPersistent(function () { return callback.apply(self, callArgs) })
          }
          return schedule.apply(window, persistentArgs)
        }
        return schedule.apply(window, arguments)
      }
      var args = Array.from(arguments)
      if (typeof callback === 'function' && names[0] !== 'setInterval') {
        args[0] = function () { handles.delete(handle); return callback.apply(this, arguments) }
      }
      var handle = schedule.apply(window, args)
      handles.add(handle)
      return handle
    }
    window[names[1]] = function (handle) { handles.delete(handle); return cancel(handle) }
    pageCleanups.push(function () { handles.forEach(cancel); handles.clear() })
  })

  function trackPageListeners (target) {
    var add = target.addEventListener.bind(target)
    var remove = target.removeEventListener.bind(target)
    var listeners = []
    target.addEventListener = function (type, listener, options) {
      if (listener && !isPersistentScript()) {
        listeners.push([type, listener, options])
        // A full document load has already fired these events after a PJAX visit.
        if ((type === 'DOMContentLoaded' || (target === window && type === 'load')) && document.readyState === 'complete') {
          window.setTimeout(function () {
            if (typeof listener === 'function') listener.call(target, new Event(type))
            else if (listener.handleEvent) listener.handleEvent(new Event(type))
          }, 0)
          return
        }
      }
      return add(type, listener, options)
    }
    pageCleanups.push(function () {
      listeners.splice(0).forEach(function (entry) { remove.apply(target, entry) })
    })
  }

  var requests = new Set()
  var sendRequest = XMLHttpRequest.prototype.send
  XMLHttpRequest.prototype.send = function () {
    var request = this
    requests.add(request)
    request.addEventListener('loadend', function () { requests.delete(request) }, {once: true})
    return sendRequest.apply(request, arguments)
  }
  pageCleanups.push(function (context) {
    var live2dRequests = context && context.keepKanban && window.VoloLive2D && window.VoloLive2D.requests
    requests.forEach(function (request) {
      if (live2dRequests && live2dRequests.has(request)) return
      request.abort()
    })
    if (live2dRequests) {
      Array.from(requests).forEach(function (request) {
        if (!live2dRequests.has(request)) requests.delete(request)
      })
    } else {
      requests.clear()
    }
  })

  function isAuthPage (url) {
    var paths = document.documentElement.dataset
    return url.pathname === paths.boloAdminUrl || url.pathname === paths.boloLoginUrl || /\/(?:start|root|logout|admin-index\.do)\/?$/.test(url.pathname)
  }

  function pageKey (url) { return url.pathname + url.search }

  function preserveSkinPreview (destination) {
    var skin = new URL(location.href).searchParams.get('skin')
    if (skin && skin === document.documentElement.dataset.boloSkin && pageLink(destination) && !isAuthPage(destination) && !destination.searchParams.has('skin')) {
      destination.searchParams.set('skin', skin)
    }
    return destination
  }

  function cleanupPage (context) {
    cleanups.splice(0).forEach(function (cleanup) {
      try { cleanup(context) } catch (error) { console.warn('Page cleanup failed', error) }
    })
    pageCleanups.forEach(function (cleanup) { cleanup(context) })
    if (window.admin && window.admin.editors) {
      Object.values(window.admin.editors).forEach(function (wrapper) {
        var editor = wrapper && (wrapper.editor || wrapper)
        try { if (editor && typeof editor.destroy === 'function') editor.destroy() } catch (error) { console.warn('Editor cleanup failed', error) }
      })
      window.admin.editors = {}
    }
    try { if (window.vditor && typeof window.vditor.destroy === 'function') window.vditor.destroy() } catch (error) { console.warn('Comment editor cleanup failed', error) }
    window.vditor = null
    if (window.jQuery) {
      window.jQuery(document).off()
      window.jQuery(window).off()
      window.jQuery(document.body).empty()
    }
    window.onbeforeunload = null
    window.onhashchange = null
    window.onload = null
    window.onscroll = null
    window.onresize = null
  }

  function stylesheet (source) {
    var existing = Array.from(document.head.querySelectorAll('link[rel="stylesheet"]')).find(function (link) {
      return link.href === source.href
    })
    if (existing) return Promise.resolve(existing)
    return new Promise(function (resolve, reject) {
      var link = document.importNode(source, true)
      var timer = nativeTimeout(function () { reject(new Error('Stylesheet timed out: ' + link.href)) }, 12000)
      link.onload = function () { nativeClearTimeout(timer); resolve(link) }
      link.onerror = function () { nativeClearTimeout(timer); reject(new Error('Stylesheet failed: ' + link.href)) }
      document.head.appendChild(link)
    })
  }

  function isSingletonVendorScript (scriptPath) {
    return /\/(?:sweetalert|mdui|jquery\.fancybox|jquery\.cookie|lazyload(?:\.iife)?|typed|swiper|TweenMax)(?:\.min)?\.js$/i.test(scriptPath)
  }

  function shouldSkipPersistentBodyScript (source, scriptPath, navMode) {
    if (!source.src) return false
    if (/\/plugins\/kanbanniang\/loader(?:\.min)?\.js$/i.test(scriptPath)) {
      return Boolean(window.VoloKanban && typeof window.VoloKanban.isAlive === 'function' && window.VoloKanban.isAlive())
    }
    if (navMode === 'once') {
      if (onceScriptPaths.has(scriptPath)) return true
      onceScriptPaths.add(scriptPath)
      return false
    }
    if (/\/js\/lib\/(?:compress\/pjax\.min|jquery\/jquery\.min)\.js$/i.test(scriptPath)) {
      return typeof window.jQuery === 'function'
    }
    if (/\/sweetalert(?:\.min)?\.js$/i.test(scriptPath)) {
      return typeof window.swal === 'function'
    }
    if (/\/mdui(?:\.min)?\.js$/i.test(scriptPath)) {
      return typeof window.mdui === 'object' && window.mdui !== null
    }
    if (/\/jquery\.fancybox(?:\.min)?\.js$/i.test(scriptPath)) {
      return Boolean(window.jQuery && window.jQuery.fn && window.jQuery.fn.fancybox)
    }
    if (/\/jquery\.cookie(?:\.min)?\.js$/i.test(scriptPath)) {
      return Boolean(window.jQuery && window.jQuery.cookie)
    }
    if (/\/lazyload(?:\.iife)?(?:\.min)?\.js$/i.test(scriptPath)) {
      return typeof window.LazyLoad === 'function'
    }
    if (/\/typed(?:\.min)?\.js$/i.test(scriptPath)) {
      return typeof window.Typed === 'function'
    }
    if (/\/swiper(?:\.min)?\.js$/i.test(scriptPath)) {
      return typeof window.Swiper === 'function'
    }
    if (/\/TweenMax(?:\.min)?\.js$/i.test(scriptPath)) {
      return typeof window.TweenMax === 'function'
    }
    if (/\/js\/common(?:\.min)?\.js$/i.test(scriptPath)) {
      return Boolean(window.Util && window.Cookie)
    }
    if (/\/js\/blog-enhancements(?:\.min)?\.js$/i.test(scriptPath)) {
      return Boolean(loadedPersistentScripts.has(scriptPath) || window.VoloSearch)
    }
    return false
  }

  function execute (source) {
    var hasNavAttr = source.hasAttribute(persistentAttribute)
    var navMode = hasNavAttr && typeof source.getAttribute === 'function' ? source.getAttribute(persistentAttribute) : ''
    var scriptPath = normalizeScriptPath(source.src)
    if (shouldSkipPersistentBodyScript(source, scriptPath, navMode)) {
      return Promise.resolve()
    }
    var isPersistentHead = hasNavAttr && navMode !== 'once'
    var preserveInjectedStyles = Boolean(source.src && (isPersistentHead || navMode === 'once' || isSingletonVendorScript(scriptPath)))
    if (isPersistentHead) {
      if (!source.src) return Promise.resolve()
      if (loadedPersistentScripts.has(scriptPath)) return Promise.resolve()
      loadedPersistentScripts.add(scriptPath)
    }
    return new Promise(function (resolve, reject) {
      var script = document.createElement('script')
      var timer
      var headChildrenBefore = preserveInjectedStyles ? new Set(Array.from(document.head.children)) : null
      Array.from(source.attributes).forEach(function (attribute) {
        if (attribute.name !== 'async' && attribute.name !== 'defer') script.setAttribute(attribute.name, attribute.value)
      })
      script.async = false
      if (source.src) {
        timer = nativeTimeout(function () { script.remove(); reject(new Error('Script timed out: ' + source.src)) }, 15000)
        script.onload = function () {
          nativeClearTimeout(timer)
          if (headChildrenBefore) {
            Array.from(document.head.children).forEach(function (element) {
              if (!headChildrenBefore.has(element) && element.tagName && element.tagName.toUpperCase() === 'STYLE' && typeof element.setAttribute === 'function') {
                element.setAttribute(persistentAttribute, '')
              }
            })
          }
          // A newly loaded jQuery must wait until the page's Label and modules are ready.
          if (window.jQuery && !heldJQueries.includes(window.jQuery) && !window.jQuery.isReady) {
            window.jQuery.holdReady(true)
            heldJQueries.push(window.jQuery)
          }
          resolve()
        }
        script.onerror = function () { nativeClearTimeout(timer); reject(new Error('Script failed: ' + script.src)) }
      } else {
        script.textContent = source.textContent
      }
      ;(isPersistentHead ? document.head : document.body).appendChild(script)
      if (!source.src) resolve()
    })
  }

  async function navigate (href, options) {
    options = options || {}
    var destination = new URL(href, window.location.href)
    if (destination.origin !== window.location.origin) throw new Error('PJAX requires a same-origin URL')
    if (!options.pop) preserveSkinPreview(destination)
    var articleEditor = window.admin && window.admin.editors && window.admin.editors.articleEditor
    if (articleEditor && window.admin.article && window.admin.article.isConfirm) {
      try {
        var content = articleEditor.getContent()
        if (content.trim() && content !== window.admin.article.content && !window.confirm(window.Label && Label.editorLeaveLabel || '文章尚未保存，确认离开？')) {
          if (options.pop) history.pushState(history.state, '', rendered.href)
          return
        }
      } catch (error) { /* An editor still initializing has no unsaved content. */ }
    }
    var id = ++generation
    if (pending) pending.abort()
    pending = new AbortController()
    document.documentElement.setAttribute('data-bolo-loading', '')
    try {
      // Ask for the complete shell; theme-specific #pjax fragments cannot replace the console shell.
      var response = await fetch(destination.href, {
        credentials: 'same-origin', signal: pending.signal, headers: {'X-Bolo-PJAX': 'true'},
      })
      var errorPage = [401, 403, 404, 410, 429, 500].includes(response.status)
      if ((!response.ok && !errorPage) || !(response.headers.get('Content-Type') || '').includes('text/html')) {
        throw new Error('Cannot load page: ' + response.status)
      }
      var finalURL = new URL(response.url)
      if (finalURL.origin !== window.location.origin) throw new Error('Cross-origin redirect')
      if (!response.redirected) finalURL.hash = destination.hash
      var page = new DOMParser().parseFromString(await response.text(), 'text/html')
      var scripts = Array.from(page.querySelectorAll('script')).filter(function (script) {
        return !script.type || scriptTypes.test(script.type) || script.type === 'module'
      })
      var oldStyles = Array.from(document.head.querySelectorAll('link[rel="stylesheet"]'))
      var styles = await Promise.all(Array.from(page.head.querySelectorAll('link[rel="stylesheet"]')).map(stylesheet))
      if (id !== generation) return
      scanDocumentPersistentScripts()
      if (!options.pop) {
        history.replaceState(Object.assign({}, history.state, {boloAuthPjax: true, scrollY: window.scrollY}), '', location.href)
        history[options.replace ? 'replaceState' : 'pushState']({boloAuthPjax: true, scrollY: 0}, '', finalURL.href)
      }
      window.dispatchEvent(new CustomEvent('bolo:before-navigate'))

      var isIncomingPublic = page.documentElement.hasAttribute('data-bolo-page') && typeof page.documentElement.getAttribute === 'function'
        ? page.documentElement.getAttribute('data-bolo-page') === 'public'
        : !isAuthPage(finalURL)
      var liveKanban = typeof document.getElementById === 'function' ? document.getElementById('boloKanBanNiangContainer') : null
      if (!liveKanban && typeof document.querySelector === 'function') {
        liveKanban = document.querySelector('.solo-kanbanniang')
      }
      var incomingKanbans = typeof page.querySelectorAll === 'function'
        ? Array.from(page.querySelectorAll('#boloKanBanNiangContainer, .solo-kanbanniang'))
        : []
      var keepKanban = Boolean(
        liveKanban &&
        window.VoloKanban &&
        typeof window.VoloKanban.isAlive === 'function' &&
        window.VoloKanban.isAlive() &&
        isIncomingPublic &&
        incomingKanbans.length > 0
      )
      if (keepKanban && typeof liveKanban.remove === 'function') {
        liveKanban.remove()
      }

      cleanupPage({keepKanban: keepKanban})
      oldStyles.forEach(function (style) { if (!styles.includes(style)) style.remove() })
      for (var styleIndex = 1; styleIndex < styles.length; styleIndex++) {
        var prevStyle = styles[styleIndex - 1]
        var nextStyle = styles[styleIndex]
        if (typeof prevStyle.compareDocumentPosition === 'function' && !(prevStyle.compareDocumentPosition(nextStyle) & 4)) {
          document.head.appendChild(nextStyle)
        }
      }
      Array.from(document.head.children).forEach(function (element) {
        if (element.hasAttribute(persistentAttribute) || element.matches('link[rel="stylesheet"]')) return
        if (element.tagName && element.tagName.toUpperCase() === 'STYLE' && typeof element.textContent === 'string' && element.textContent.indexOf('.swal-') !== -1) {
          if (typeof element.setAttribute === 'function') element.setAttribute(persistentAttribute, '')
          return
        }
        element.remove()
      })
      Array.from(page.head.children).forEach(function (element) {
        if (element.tagName === 'SCRIPT' || element.matches('link[rel="stylesheet"]')) return
        document.head.appendChild(document.importNode(element, true))
      })
      scripts.forEach(function (script) { script.remove() })
      if (keepKanban) {
        incomingKanbans.forEach(function (placeholder) {
          if (typeof placeholder.remove === 'function') placeholder.remove()
        })
      }
      Array.from(document.body.attributes).forEach(function (attribute) { document.body.removeAttribute(attribute.name) })
      Array.from(page.body.attributes).forEach(function (attribute) { document.body.setAttribute(attribute.name, attribute.value) })
      document.body.replaceChildren.apply(document.body, Array.from(page.body.childNodes).map(function (node) {
        return document.importNode(node, true)
      }))
      if (keepKanban && typeof document.body.appendChild === 'function') {
        document.body.appendChild(liveKanban)
      }
      document.documentElement.lang = page.documentElement.lang || 'zh-CN'
      document.documentElement.className = page.documentElement.className
      ;['data-bolo-admin-url', 'data-bolo-login-url', 'data-bolo-admin-pages', 'data-bolo-page', 'data-bolo-skin'].forEach(function (name) {
        if (page.documentElement.hasAttribute(name)) document.documentElement.setAttribute(name, page.documentElement.getAttribute(name))
        else document.documentElement.removeAttribute(name)
      })
      document.title = page.title
      if (window.BoloTheme) window.BoloTheme.refresh()
      rendered = finalURL
      heldJQueries = []
      var deferredReadyCallbacks = []
      var restoreJQueryReady = null
      if (window.jQuery && window.jQuery.fn && window.jQuery.isReady) {
        var existingJQuery = window.jQuery
        var originalFnReady = existingJQuery.fn.ready
        existingJQuery.fn.ready = function (fn) {
          if (typeof fn === 'function') deferredReadyCallbacks.push(fn)
          return this
        }
        restoreJQueryReady = function () {
          if (existingJQuery.fn && existingJQuery.fn.ready !== originalFnReady) {
            existingJQuery.fn.ready = originalFnReady
          }
        }
      }
      try {
        for (var script of scripts) {
          if (id !== generation) return
          await execute(script)
        }
      } finally {
        if (restoreJQueryReady) restoreJQueryReady()
      }
      heldJQueries.forEach(function (jquery) { jquery.holdReady(false) })
      heldJQueries = []
      deferredReadyCallbacks.splice(0).forEach(function (fn) {
        try { fn.call(document, window.jQuery) } catch (error) { console.error(error) }
      })
      if (window.Util && typeof window.Util._bindContactDisclosure === 'function') {
        window.Util._bindContactDisclosure()
      }
      if (typeof window.onload === 'function') window.onload.call(window, new Event('load'))
      var fragment
      try { fragment = finalURL.hash ? document.getElementById(decodeURIComponent(finalURL.hash.slice(1))) : null } catch (error) { fragment = null }
      if (!options.pop && fragment) fragment.scrollIntoView({block: 'start'})
      else window.scrollTo(0, options.pop && history.state ? history.state.scrollY || 0 : 0)
      window.dispatchEvent(new CustomEvent('bolo:after-navigate', {detail: {url: finalURL.href}}))
    } catch (error) {
      if (error.name !== 'AbortError' && id === generation) {
        console.warn('PJAX fallback', error)
        window.location.assign(destination.href)
      }
    } finally {
      if (id === generation) document.documentElement.removeAttribute('data-bolo-loading')
    }
  }

  window.BoloNavigation = {
    navigate: navigate,
    publicPages: true,
    // Shared runtime downloads keep their timeout after page cleanup.
    setTimeout: nativeTimeout,
    clearTimeout: nativeClearTimeout,
    runPersistent: runPersistent,
    onLeave: function (cleanup) { cleanups.push(cleanup) },
  }
  function pageLink (destination) {
    if (!/^https?:$/.test(destination.protocol) || destination.origin !== window.location.origin) return false
    if (isAuthPage(destination)) return true
    if (/\/(?:console|oauth|plugins|PBC|import|export|upload|image|images|js|scss)(?:\/|$)/i.test(destination.pathname)) return false
    return !/\.[a-z\d]{1,8}$/i.test(destination.pathname) || /\.html?$/i.test(destination.pathname)
  }
  document.addEventListener('click', function (event) {
    if (event.defaultPrevented) return
    var anchor = event.target.closest('a[href]')
    if (!anchor || anchor.hasAttribute('download') || anchor.hasAttribute('data-no-pjax') || anchor.hasAttribute('data-volo-search-trigger') || anchor.hasAttribute('data-volo-auth-trigger') || anchor.hasAttribute('data-volo-reader-logout')) return
    var destination = new URL(anchor.href, window.location.href)
    preserveSkinPreview(destination)
    if (destination.href !== anchor.href) anchor.href = destination.href
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
    if (destination.origin === location.origin && /\/search\/?$/.test(destination.pathname)) {
      if (!destination.searchParams.has('keyword') && window.VoloSearch && window.VoloSiteConfig && window.VoloSiteConfig.enableInstantSearch !== false) {
        event.preventDefault()
        event.stopImmediatePropagation()
        window.VoloSearch.open()
        return
      }
      anchor.target = '_self'
    }
    if (anchor.target && anchor.target !== '_self') return
    if (!pageLink(destination)) return
    if (pageKey(destination) === pageKey(new URL(location.href))) return
    event.preventDefault()
    event.stopImmediatePropagation()
    navigate(destination.href)
  }, true)
  window.addEventListener('popstate', function (event) {
    var destination = new URL(location.href)
    if (pageKey(destination) === pageKey(rendered)) return
    if (!(event.state && event.state.boloAuthPjax) && !isAuthPage(rendered) && !isAuthPage(destination)) return
    event.stopImmediatePropagation()
    navigate(destination.href, {pop: true})
  }, true)
  window.addEventListener('hashchange', function () {
    var current = new URL(location.href)
    if (pageKey(current) === pageKey(rendered)) rendered = current
  })
  document.addEventListener('submit', function (event) {
    var form = event.target
    if (!(form instanceof HTMLFormElement) || form.hasAttribute('data-no-pjax') || form.method.toLowerCase() !== 'get') return
    var destination = new URL(form.action || location.href, location.href)
    if (destination.origin === location.origin && /\/search\/?$/.test(destination.pathname)) form.target = '_self'
    if (form.target && form.target !== '_self') return
    if (!pageLink(destination) || !/\/search\/?$/.test(destination.pathname)) return
    destination.search = new URLSearchParams(new FormData(form)).toString()
    event.preventDefault()
    event.stopImmediatePropagation()
    navigate(destination.href)
  }, true)
  var progress = document.createElement('style')
  progress.setAttribute(persistentAttribute, '')
  progress.textContent = 'html[data-bolo-loading]::before{content:"";position:fixed;inset:0 auto auto 0;width:75%;height:3px;background:var(--bolo-accent,#236b53);z-index:2147483647;animation:bolo-progress 1.2s ease-in-out infinite}@keyframes bolo-progress{0%{transform:translateX(-100%)}100%{transform:translateX(140%)}}@media(prefers-reduced-motion:reduce){html[data-bolo-loading]::before{animation:none;width:100%}}'
  document.head.appendChild(progress)
  // Install after the router's permanent handlers, before the page's own scripts.
  trackPageListeners(window)
  trackPageListeners(document)
})()
