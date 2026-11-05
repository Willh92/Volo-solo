/* Shared chapter tracking and compact mobile directory for bundled skins. */
(function () {
  'use strict'
  if (window.BoloTOC) return
  var currentRoot
  var dispose = function () {}
  var closePanels = function () {}
  function mount () {
    var lists = Array.from(document.querySelectorAll('.article__toc'))
    if (!lists.length) { dispose(); currentRoot = null; return }
    if (currentRoot === lists[0]) return
    dispose()
    currentRoot = lists[0]
    var skin = document.documentElement?.dataset?.boloSkin
    var mobileRail
    if (skin && skin !== 'volo-studio') {
      mobileRail = document.createElement('aside')
      mobileRail.className = 'mobile-reading-toc'
      var panel = document.createElement('details')
      panel.className = 'reading-toc-panel'
      panel.setAttribute('data-reading-toc', '')
      var summary = document.createElement('summary')
      summary.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h12M8 12h12M8 18h12M3 6h.01M3 12h.01M3 18h.01"/></svg>'
      var label = document.createElement('span')
      label.textContent = lists[0].getAttribute('aria-label') || '目录'
      summary.setAttribute('aria-label', label.textContent)
      summary.setAttribute('title', label.textContent)
      summary.appendChild(label)
      var chevron = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
      chevron.setAttribute('viewBox', '0 0 24 24')
      chevron.setAttribute('aria-hidden', 'true')
      chevron.classList.add('reading-toc-chevron')
      chevron.innerHTML = '<path d="m8 10 4 4 4-4"/>'
      summary.appendChild(chevron)
      var copy = lists[0].cloneNode(true)
      copy.className = 'reading-toc-links'
      copy.removeAttribute('id')
      panel.appendChild(summary)
      panel.appendChild(copy)
      mobileRail.appendChild(panel)
      document.body.appendChild(mobileRail)
      lists.push(copy)
    }
    var links = lists.flatMap(function (list) { return Array.from(list.querySelectorAll('a[href^="#"]')) })
    var chapters = links.map(function (link) {
      try { return document.getElementById(decodeURIComponent(link.hash.slice(1))) } catch (error) { return null }
    })
    var frame = 0
    var selectedChapter
    var revealSelection = true
    var panels = Array.from(document.querySelectorAll('[data-reading-toc]'))
    var animations = new Map()
    var scrollAnimation = 0
    var smoothScrolling = false
    var breakpoint = skin === 'volo-studio' ? 1099 : ['bolo-xups', 'bolo-sakura', 'bolo-bubble', 'bolo-butterfly', 'bolo-casper', 'bolo-pinghsu'].includes(skin) ? 1399 : 1199
    var narrow = window.matchMedia?.('(max-width: ' + breakpoint + 'px)')
    var content = document.querySelector?.('.article__content,.post__content,.entry-content,.post-body,#article-container,.article .item__content,.vditor-reset')
    var floating = content ? Array.from(document.querySelectorAll('.post__toc,.reading-toc-inline')) : []
    function resizePanels () {
      animations.forEach(function (entry, panel) { entry.animation.cancel(); panel.style.overflow = entry.overflow; panel.classList.remove('is-closing') })
      animations.clear()
      panels.forEach(function (panel) { panel.open = !narrow?.matches })
      revealSelection = true
      update()
    }
    function setOpen (panel, open) {
      var running = animations.get(panel)
      var start = panel.getBoundingClientRect?.()
      var startStyle = window.getComputedStyle?.(panel)
      var startPadding = startStyle?.padding
      var startRadius = startStyle?.borderRadius
      var startTransform = startStyle?.transform || 'none'
      if (running) {
        running.animation.cancel()
        panel.style.overflow = running.overflow
        animations.delete(panel)
      }
      panel.classList?.remove('is-closing')
      panel.open = open
      if (open) scheduleReveal()
      if (!start || !panel.animate || window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches) return
      var end = panel.getBoundingClientRect()
      var endStyle = window.getComputedStyle(panel)
      var endPadding = endStyle.padding
      var endRadius = endStyle.borderRadius
      var endTransform = endStyle.transform
      if (!open) { panel.open = true; panel.classList.add('is-closing') }
      var overflow = panel.style.overflow
      panel.style.overflow = 'hidden'
      var animation = panel.animate([
        {width: start.width + 'px', height: start.height + 'px', padding: startPadding, borderRadius: startRadius, transform: startTransform},
        {width: end.width + 'px', height: end.height + 'px', padding: endPadding, borderRadius: endRadius, transform: endTransform}
      ], {duration: 220, easing: 'cubic-bezier(.2,.7,.2,1)'})
      animations.set(panel, {animation: animation, open: open, overflow: overflow})
      animation.onfinish = function () {
        panel.open = open
        panel.style.overflow = overflow
        panel.classList.remove('is-closing')
        animations.delete(panel)
        if (open) scheduleReveal()
        window.BoloTheme?.syncControls?.()
      }
    }
    function togglePanel (event) {
      var summary = event.target.closest?.('summary')
      var panel = summary?.parentElement
      if (!panel || !panels.includes(panel)) return
      event.preventDefault()
      var running = animations.get(panel)
      setOpen(panel, running ? !running.open : !panel.open)
    }
    function closePanel (event) {
      var panel = event.target.closest?.('[data-reading-toc]')
      if (!panel || !narrow?.matches) return
      if (event.type === 'click' && event.target.closest('a[href^="#"]')) setOpen(panel, false)
      if (event.key === 'Escape' && panel.open) {
        setOpen(panel, false)
        panel.querySelector('summary').focus()
      }
    }
    function closeOutside (event) {
      if (!narrow?.matches) return
      panels.forEach(function (panel) { if (panel.open && !panel.contains(event.target)) setOpen(panel, false) })
    }
    function clickPanel (event) { togglePanel(event); closePanel(event) }
    function stopScroll () {
      if (scrollAnimation) cancelAnimationFrame(scrollAnimation)
      scrollAnimation = 0
      smoothScrolling = false
    }
    function scrollToChapter (top, reduced, chapter, offset) {
      stopScroll()
      var start = window.scrollY
      var distance = top - start
      if (reduced || Math.abs(distance) < 1) { window.scrollTo({top: top, behavior: 'instant'}); return }
      // Short jumps stay gentle; a distant chapter never causes a long scroll.
      var duration = Math.min(460, Math.max(160, 160 + Math.sqrt(Math.abs(distance)) * 7))
      var began
      smoothScrolling = true
      function step (now) {
        if (!smoothScrolling) return
        if (began === undefined) began = now
        var progress = Math.min(1, (now - began) / duration)
        // Skin entrance animations and loading content can move a chapter during the jump.
        var destination = chapter?.isConnected ? Math.max(0, chapter.getBoundingClientRect().top + window.scrollY - offset) : top
        window.scrollTo({top: start + (destination - start) * (1 - Math.pow(1 - progress, 3)), behavior: 'instant'})
        if (progress < 1) scrollAnimation = requestAnimationFrame(step)
        else { scrollAnimation = 0; smoothScrolling = false }
      }
      scrollAnimation = requestAnimationFrame(step)
    }
    function cancelOnKey (event) {
      if (['ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown', ' '].includes(event.key)) stopScroll()
    }
    function goToChapter (event) {
      if ((event.button && event.button !== 0) || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
      var link = event.target.closest?.('.article__toc a[href^="#"],.reading-toc-links a[href^="#"]')
      if (!link) return
      var chapter
      try { chapter = document.getElementById(decodeURIComponent(link.hash.slice(1))) } catch (error) { return }
      if (!chapter) return
      event.preventDefault()
      event.stopImmediatePropagation()
      var offset = parseFloat(window.getComputedStyle?.(document.documentElement)?.scrollPaddingTop) || 96
      var top = Math.max(0, chapter.getBoundingClientRect().top + window.scrollY - offset)
      var reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
      window.history?.replaceState(window.history.state, '', link.hash)
      if (narrow?.matches) panels.forEach(function (panel) { if (panel.open) setOpen(panel, false) })
      scrollToChapter(top, reduced, chapter, offset)
      if (event.detail === 0 && chapter.focus) { chapter.setAttribute('tabindex', '-1'); chapter.focus({preventScroll: true}) }
    }
    closePanels = function () {
      if (narrow?.matches) panels.forEach(function (panel) { if (panel.open) setOpen(panel, false) })
    }
    resizePanels()
    document.addEventListener('click', closeOutside)
    document.addEventListener('click', goToChapter, true)
    window.addEventListener('wheel', stopScroll, {passive: true})
    window.addEventListener('touchstart', stopScroll, {passive: true})
    window.addEventListener('pointerdown', stopScroll, {passive: true})
    window.addEventListener('keydown', cancelOnKey)
    narrow?.addEventListener('change', resizePanels)
    panels.forEach(function (panel) {
      panel.addEventListener('click', clickPanel)
      panel.addEventListener('keydown', closePanel)
    })
    function revealLink (link) {
      var list = link.closest?.('.article__toc,.reading-toc-links')
      for (var container = list; container && container !== document.body && container !== document.documentElement; container = container.parentElement) {
        var directory = container === list || container.matches?.('[data-reading-toc],.post__toc,.reading-toc-inline,.reading-toc-panel,.nexmoe-toc,.sidebar__toc,#toc,.next-toc-card')
        if (!directory || !container.clientHeight || container.scrollHeight <= container.clientHeight + 1) continue
        var style = window.getComputedStyle?.(container)
        if (!/(auto|scroll)/.test(style?.overflowY || '')) continue
        var bounds = container.getBoundingClientRect(), item = link.getBoundingClientRect()
        var top = bounds.top + (container.clientTop || 0) + 6
        var heading = container === list ? window.getComputedStyle?.(container, '::before') : null
        if (heading?.display !== 'none' && heading?.content && !['none', 'normal'].includes(heading.content)) {
          top += (parseFloat(heading.height) || parseFloat(heading.lineHeight) || 0) + (parseFloat(heading.paddingTop) || 0) + (parseFloat(heading.paddingBottom) || 0)
        }
        var bottom = bounds.top + (container.clientTop || 0) + container.clientHeight - 6
        var delta = item.top < top ? item.top - top : item.bottom > bottom ? item.bottom - bottom : 0
        if (Math.abs(delta) < 1) continue
        var position = Math.max(0, Math.min(container.scrollHeight - container.clientHeight, container.scrollTop + delta))
        // Scroll only directory panes; scrollIntoView can also move the article itself.
        if (container.scrollTo) container.scrollTo({top: position, behavior: 'instant'})
        else container.scrollTop = position
      }
    }
    function setCurrent (activeId, reveal) {
      links.forEach(function (link, index) {
        var active = Boolean(chapters[index] && chapters[index].id === activeId)
        link.parentElement.classList.toggle('current', active)
        if (active) {
          link.setAttribute('aria-current', 'location')
          if (reveal) revealLink(link)
        }
        else link.removeAttribute('aria-current')
      })
    }
    function update () {
      frame = 0
      var rootStyle = window.getComputedStyle?.(document.documentElement)
      var offset = Math.max(110, parseFloat(rootStyle?.scrollPaddingTop) || 0) + 4
      var bounds = content ? content.getBoundingClientRect() : null
      var current = chapters.find(Boolean)?.id
      chapters.forEach(function (chapter) {
        if (chapter && chapter.getBoundingClientRect().top <= offset) current = chapter.id
      })
      if (bounds) {
        var nextVisibility = bounds.top > 160 || bounds.bottom < 100 ? 'hidden' : 'visible'
        floating.forEach(function (element) {
          if (element.style.visibility !== nextVisibility) element.style.visibility = nextVisibility
        })
      }
      var reveal = revealSelection || selectedChapter !== current
      selectedChapter = current
      revealSelection = false
      setCurrent(current, reveal)
    }
    function schedule () { if (!frame) frame = requestAnimationFrame(update) }
    function scheduleReveal () { revealSelection = true; schedule() }
    var ro = content && typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null
    if (ro) ro.observe(content)
    window.addEventListener('scroll', schedule, {passive: true})
    window.addEventListener('resize', scheduleReveal)
    dispose = function () {
      document.removeEventListener('click', closeOutside)
      document.removeEventListener('click', goToChapter, true)
      stopScroll()
      window.removeEventListener('wheel', stopScroll)
      window.removeEventListener('touchstart', stopScroll)
      window.removeEventListener('pointerdown', stopScroll)
      window.removeEventListener('keydown', cancelOnKey)
      animations.forEach(function (entry, panel) { entry.animation.cancel(); panel.style.overflow = entry.overflow; panel.classList.remove('is-closing') })
      animations.clear()
      ro?.disconnect()
      window.removeEventListener('scroll', schedule)
      window.removeEventListener('resize', scheduleReveal)
      if (frame) cancelAnimationFrame(frame)
      narrow?.removeEventListener('change', resizePanels)
      panels.forEach(function (panel) {
        panel.removeEventListener('click', clickPanel)
        panel.removeEventListener('keydown', closePanel)
      })
      mobileRail?.remove()
      frame = 0
      currentRoot = null
      closePanels = function () {}
    }
    if (window.BoloNavigation) BoloNavigation.onLeave(dispose)
    update()
    window.BoloTheme?.syncControls?.()
  }
  window.BoloTOC = {refresh: mount, close: function () { closePanels() }}
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, {once: true})
  else mount()
  window.addEventListener('bolo:after-navigate', mount)
})()
