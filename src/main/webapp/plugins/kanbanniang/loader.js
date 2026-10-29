/*
 * Volo local Kanbanniang controller, adapted from B3log's MIT plugin.
 * Sources and model attribution: SOURCES.json, LICENSE and README.md.
 */
(function () {
  'use strict'
  if (window.VoloKanban) window.VoloKanban.destroy()
  var host = document.querySelector('.solo-kanbanniang')
  if (!host) return
  var alive = true, started = false, ready = false
  var messageTimer, closeTimer, loadTimer, chatTimer, drag
  var removers = []
  var requests = new AbortController()
  var tips = {chat: ['谢谢你来看望这个文字空间。']}
  var label = window.Label || {}
  var staticBase = (label.staticServePath || label.servePath || '').replace(/\/$/, '') + '/plugins/kanbanniang'
  var modelBase = (label.servePath || '').replace(/\/$/, '') + '/plugins/kanbanniang/assets/'
  var tip = host.querySelector('.solo-kanbanniang__tip')

  function get (key) { try { return window.sessionStorage.getItem(key) } catch (error) { return null } }
  function set (key, value) { try { window.sessionStorage.setItem(key, String(value)) } catch (error) {} }
  function listen (target, name, handler, options) {
    target.addEventListener(name, handler, options)
    removers.push(function () { target.removeEventListener(name, handler, options) })
  }
  function message (text, duration) {
    if (!alive || !ready || get('soloKanbanniang') === 'close') return
    if (Array.isArray(text)) text = text[Math.floor(Math.random() * text.length)]
    if (!text) return
    tip.textContent = String(text)
    tip.style.opacity = '1'
    clearTimeout(messageTimer)
    messageTimer = setTimeout(function () { tip.style.opacity = '0' }, duration || 4000)
  }
  function randomBackground () {
    host.style.backgroundImage = 'url("' + staticBase + '/assets/background/sakura' + Math.floor(Math.random() * 11) + '.gif")'
  }
  function pause () {
    ready = false
    started = false
    host.hidden = true
    host.style.display = 'none'
    delete host.dataset.ready
    clearTimeout(loadTimer)
    clearTimeout(messageTimer)
    if (window.loadlive2d && window.loadlive2d.dispose) window.loadlive2d.dispose()
  }
  function destroy () {
    if (!alive) return
    alive = false
    requests.abort()
    clearTimeout(closeTimer)
    clearInterval(chatTimer)
    removers.splice(0).forEach(function (remove) { remove() })
    pause()
  }
  function sdk () {
    if (typeof window.loadlive2d === 'function') return Promise.resolve()
    if (!window.__voloKanbanSDK) {
      window.__voloKanbanSDK = new Promise(function (resolve, reject) {
        var script = document.createElement('script')
        script.src = staticBase + '/live2d.js'
        script.async = true
        script.onload = function () {
          if (typeof window.loadlive2d === 'function') resolve()
          else reject(new Error('Live2D did not initialize'))
        }
        script.onerror = function () { script.remove(); reject(new Error('Local Live2D script is unavailable')) }
        document.head.appendChild(script)
      }).catch(function (error) { window.__voloKanbanSDK = null; throw error })
    }
    return window.__voloKanbanSDK
  }
  function change (random) {
    if (!alive || !started) return
    ready = false
    delete host.dataset.ready
    clearTimeout(loadTimer)
    loadTimer = setTimeout(pause, 10000)
    window.loadlive2d('soloKanbanniang', modelBase + (random ? 'absoluteRandomModel' : 'model') + '?t=' + Date.now())
  }
  function restorePosition () {
    var x = Number(get('soloKanbanniangX')), y = Number(get('soloKanbanniangY'))
    if (get('soloKanbanniangX') !== null && Number.isFinite(x)) host.style.left = Math.max(0, Math.min(x, innerWidth - 284)) + 'px'
    if (get('soloKanbanniangY') !== null && Number.isFinite(y)) {
      host.style.top = Math.max(0, Math.min(y, innerHeight - 254)) + 'px'
      host.style.bottom = 'auto'
    }
  }
  async function start () {
    if (!alive || started || innerWidth <= 800 || get('soloKanbanniang') === 'close') return
    started = true
    try {
      await sdk()
      if (!alive || innerWidth <= 800 || !host.isConnected) { started = false; return }
      // A context lost while hidden or after a PJAX visit needs a fresh canvas.
      var oldCanvas = host.querySelector('#soloKanbanniang')
      oldCanvas.replaceWith(oldCanvas.cloneNode(false))
      randomBackground()
      change(false)
    } catch (error) { pause() }
  }
  function tool (id, action) {
    var icon = host.querySelector('#soloKanbanniang' + id)
    if (icon) listen(icon.closest('button') || icon, 'click', action)
  }
  tool('Home', function () {
    if (window.BoloNavigation) window.BoloNavigation.navigate((label.servePath || '') + '/')
    else window.location.assign((label.servePath || '') + '/')
  })
  tool('RSS', function () { window.location.assign((label.servePath || '') + '/rss.xml') })
  tool('Github', function () { window.open('https://github.com/Willh92/Volo-solo', '_blank', 'noopener,noreferrer') })
  tool('Chat', function () { message(tips.chat); randomBackground() })
  tool('Change', function () { change(true); message('换一套新装，继续陪你阅读。'); randomBackground() })
  tool('Photo', function () {
    if (ready && window.Live2D) { window.Live2D.captureName = 'volo-kanbanniang.png'; window.Live2D.captureFrame = true }
  })
  tool('Close', function () {
    set('soloKanbanniang', 'close')
    host.classList.add('is-closed')
    closeTimer = setTimeout(pause, 160)
  })
  listen(window, 'volo:kanban-ready', function (event) {
    if (!alive || !started || innerWidth <= 800 || get('soloKanbanniang') === 'close') return
    ready = true
    clearTimeout(loadTimer)
    host.dataset.ready = 'true'
    host.dataset.model = event.detail && event.detail.model || ''
    host.hidden = false
    host.style.display = ''
    restorePosition()
    message('欢迎来到这个文字空间。', 5000)
  })
  listen(window, 'resize', function () {
    if (innerWidth <= 800) { if (started) pause() }
    else { restorePosition(); start() }
  })
  listen(document, 'copy', function () { message('如果转载，也请记得注明出处。') })
  listen(document, 'mouseover', function (event) {
    for (var entry of tips.mouseover || []) {
      var element
      try { element = event.target.closest(entry.selector) } catch (error) { continue }
      if (element && (!event.relatedTarget || !element.contains(event.relatedTarget))) {
        message(entry.text.replace('{text}', element.textContent.trim().slice(0, 42)), 3000)
        break
      }
    }
  })
  listen(host, 'pointerdown', function (event) {
    if (event.button !== 0 || event.target.closest('button')) return
    var rect = host.getBoundingClientRect()
    drag = {id: event.pointerId, x: event.clientX - rect.left, y: event.clientY - rect.top}
    host.setPointerCapture(event.pointerId)
  })
  listen(host, 'pointermove', function (event) {
    if (!drag || drag.id !== event.pointerId) return
    var x = Math.max(0, Math.min(event.clientX - drag.x, innerWidth - host.offsetWidth))
    var y = Math.max(0, Math.min(event.clientY - drag.y, innerHeight - host.offsetHeight))
    host.style.left = x + 'px'; host.style.top = y + 'px'; host.style.bottom = 'auto'
    set('soloKanbanniangX', x); set('soloKanbanniangY', y)
  })
  function releaseDrag (event) {
    if (drag && host.hasPointerCapture(event.pointerId)) host.releasePointerCapture(event.pointerId)
    drag = null
  }
  listen(host, 'pointerup', releaseDrag)
  listen(host, 'pointercancel', releaseDrag)
  if (window.BoloNavigation) window.BoloNavigation.onLeave(destroy)
  window.VoloKanban = {destroy: destroy, change: change, showMessage: message, isReady: function () { return ready }}
  if (innerWidth <= 800 || get('soloKanbanniang') === 'close') { host.hidden = true; return }
  fetch(staticBase + '/assets/tips.json', {signal: requests.signal}).then(function (response) {
    if (!response.ok) throw new Error('Local tips are unavailable')
    return response.json()
  }).then(function (data) { if (alive) tips = data }).catch(function () {})
  chatTimer = setInterval(function () { if (!document.hidden) message(tips.chat, 4000) }, 30000)
  if (document.readyState === 'complete') start()
  else listen(window, 'load', start, {once: true})
})()
