/* SPDX-License-Identifier: AGPL-3.0-or-later */
(function () {
  'use strict'
  if (!document.body.classList.contains('bolo-console')) return
  var controls = new Map(), active = null, sequence = 0, scheduled = false, disposed = false
  var scrollOptions = {capture: true, passive: true}
  var previousSet = window.jQuery && jQuery.valHooks.select.set
  function node(tag, name, text) {
    var element = document.createElement(tag); if (name) element.className = name
    if (text != null) element.textContent = text
    return element
  }
  function close() {
    if (!active) return
    active.button.setAttribute('aria-expanded', 'false'); active.button.removeAttribute('aria-activedescendant')
    active.popup.remove(); active = null
  }
  function sync(control) {
    var select = control.select, option = select.options[select.selectedIndex]
    var label = option ? option.textContent.trim() : '请选择'
    if (control.label.textContent !== label) control.label.textContent = label
    if (control.button.disabled !== select.disabled) control.button.disabled = select.disabled
    var hidden = select.hidden || select.style.display === 'none' || select.classList.contains('fn__none') || getComputedStyle(select).display === 'none'
    if (control.button.hidden !== hidden) control.button.hidden = hidden
    control.button.setAttribute('aria-required', String(select.required))
    if (active === control && (select.disabled || hidden || !select.getClientRects().length)) close()
  }
  function activate(control, index) {
    control.index = index
    control.items.forEach(function (item) { item.element.classList.toggle('is-active', item.index === index) })
    var item = control.items.find(function (item) { return item.index === index })
    if (item) {
      control.button.setAttribute('aria-activedescendant', item.element.id)
      item.element.scrollIntoView({block: 'nearest'})
    }
  }
  function choose(control, index) {
    if (control.select.disabled) return
    var option = control.select.options[index]
    if (!option || option.disabled || option.parentElement.disabled) return
    var changed = control.select.selectedIndex !== index
    control.select.selectedIndex = index; sync(control); close(); control.button.focus()
    if (changed) {
      control.select.dispatchEvent(new Event('input', {bubbles: true}))
      control.select.dispatchEvent(new Event('change', {bubbles: true}))
    }
  }
  function open(control) {
    sync(control); if (control.select.disabled) return
    close(); active = control; control.items = []; control.popup.replaceChildren()
    var group
    Array.from(control.select.options).forEach(function (option, index) {
      if (option.hidden) return
      var parent = option.parentElement
      if (parent.tagName === 'OPTGROUP' && group !== parent) {
        group = parent; control.popup.append(node('div', 'console-select-group', parent.label))
      }
      var item = node('div', 'console-select-option', option.textContent)
      item.id = control.popup.id + '-' + index; item.dataset.index = index; item.setAttribute('role', 'option')
      item.setAttribute('aria-selected', String(index === control.select.selectedIndex))
      var disabled = option.disabled || parent.disabled
      item.setAttribute('aria-disabled', String(!!disabled))
      control.popup.append(item)
      if (!disabled) control.items.push({index: index, element: item})
    })
    document.body.append(control.popup)
    var rect = control.button.getBoundingClientRect(), width = Math.min(Math.max(rect.width, 160), innerWidth - 24)
    var below = innerHeight - rect.bottom - 16, above = rect.top - 16
    var upward = below < Math.min(260, control.popup.scrollHeight) && above > below
    control.popup.style.width = width + 'px'; control.popup.style.left = Math.max(12, Math.min(rect.left, innerWidth - width - 12)) + 'px'
    control.popup.style.maxHeight = Math.max(60, Math.min(280, upward ? above : below)) + 'px'
    control.popup.style.top = upward ? 'auto' : rect.bottom + 6 + 'px'
    control.popup.style.bottom = upward ? innerHeight - rect.top + 6 + 'px' : 'auto'
    control.button.setAttribute('aria-expanded', 'true')
    var initial = control.items.find(function (item) { return item.index === control.select.selectedIndex }) || control.items[0]
    if (initial) activate(control, initial.index)
  }
  function enhance(select) {
    if (controls.has(select) || select.multiple || select.size > 1 || select.closest('.vditor') || select.hasAttribute('data-native-select')) return
    var styles = getComputedStyle(select), button = node('button', 'console-select-trigger'), label = node('span', 'console-select-value')
    var popup = node('div', 'console-select-options'), id = 'console-select-' + (++sequence)
    popup.id = id; popup.setAttribute('role', 'listbox')
    button.type = 'button'; button.style.cssText = select.style.cssText; button.style.removeProperty('display')
    button.style.setProperty('--select-max-width', styles.maxWidth)
    button.append(label, node('span', 'console-select-chevron'))
    button.setAttribute('role', 'combobox'); button.setAttribute('aria-haspopup', 'listbox'); button.setAttribute('aria-expanded', 'false'); button.setAttribute('aria-controls', id)
    label.id = id + '-value'
    var caption = select.previousElementSibling
    var fieldLabel = select.labels && select.labels[0] || caption && caption.tagName === 'LABEL' && caption
    if (fieldLabel && !fieldLabel.contains(select)) {
      if (!fieldLabel.id) fieldLabel.id = id + '-label'
      button.setAttribute('aria-labelledby', fieldLabel.id + ' ' + label.id)
    }
    else if (select.getAttribute('aria-label')) button.setAttribute('aria-label', select.getAttribute('aria-label'))
    else if (fieldLabel) {
      var name = Array.from(fieldLabel.childNodes).filter(function (child) { return child.nodeType === 3 || child === caption }).map(function (child) { return child.textContent.trim() }).filter(Boolean).join(' ')
      button.setAttribute('aria-label', name || '选择选项')
    }
    else button.setAttribute('aria-label', '选择选项')
    select.before(button); select.classList.add('console-native-select'); select.tabIndex = -1; select.setAttribute('aria-hidden', 'true'); select.setAttribute('autocomplete', 'off')
    var control = {select: select, button: button, label: label, popup: popup, items: [], index: -1, text: '', typedAt: 0}
    controls.set(select, control); sync(control)
    button.addEventListener('click', function () { if (active === control) close(); else open(control) })
    button.addEventListener('focus', function () { sync(control) })
    button.addEventListener('keydown', function (event) {
      if (event.key === 'Tab') { close(); return }
      if (event.key === 'Escape') { if (active === control) { event.preventDefault(); close() } return }
      if (['ArrowDown','ArrowUp','Home','End','Enter',' '].includes(event.key)) {
        event.preventDefault()
        if (active !== control) { open(control); return }
        if (event.key === 'Enter' || event.key === ' ') { choose(control, control.index); return }
        var at = control.items.findIndex(function (item) { return item.index === control.index })
        at = event.key === 'Home' ? 0 : event.key === 'End' ? control.items.length - 1 : Math.max(0, Math.min(control.items.length - 1, at + (event.key === 'ArrowUp' ? -1 : 1)))
        if (control.items[at]) activate(control, control.items[at].index)
      } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey && !event.isComposing) {
        event.preventDefault(); if (active !== control) open(control)
        var now = Date.now(); control.text = (now - control.typedAt < 700 ? control.text : '') + event.key.toLocaleLowerCase(); control.typedAt = now
        var match = control.items.find(function (item) { return item.element.textContent.trim().toLocaleLowerCase().startsWith(control.text) })
        if (match) activate(control, match.index)
      }
    })
    popup.addEventListener('mousedown', function (event) { event.preventDefault() })
    popup.addEventListener('click', function (event) { var item = event.target.closest('[data-index]'); if (item) choose(control, Number(item.dataset.index)) })
    popup.addEventListener('pointermove', function (event) { var item = event.target.closest('[aria-disabled=false]'); if (item) activate(control, Number(item.dataset.index)) })
    select.addEventListener('change', function () { sync(control) })
    select.addEventListener('invalid', function (event) { event.preventDefault(); button.setAttribute('aria-invalid', 'true'); button.focus() })
    select.addEventListener('input', function () { button.removeAttribute('aria-invalid') })
  }
  function refresh() {
    scheduled = false; if (disposed) return
    controls.forEach(function (control, select) {
      if (!select.isConnected) { if (active === control) close(); control.button.remove(); controls.delete(select) } else sync(control)
    })
    document.querySelectorAll('select').forEach(enhance)
  }
  function schedule() { if (!scheduled) { scheduled = true; queueMicrotask(refresh) } }
  function outside(event) {
    if (active && !active.button.contains(event.target) && !active.popup.contains(event.target)) close()
    var label = event.target.closest('label')
    if (label && controls.has(label.control) && !controls.get(label.control).button.contains(event.target)) { event.preventDefault(); controls.get(label.control).button.focus() }
  }
  function scroll(event) { if (active && !(event && event.target && event.target !== window && active.popup.contains(event.target))) close() }
  function setValue(select, value) { var result = previousSet.call(this, select, value); if (controls.has(select)) sync(controls.get(select)); return result }
  if (previousSet) jQuery.valHooks.select.set = setValue
  if (window.jQuery) jQuery(document).on('ajaxComplete.voloSelect', schedule)
  function isSelectMutation(record) {
    if (!record || !record.target) return true
    var target = record.target
    if (record.type === 'childList') {
      if (target.tagName === 'SELECT' || target.tagName === 'OPTGROUP') return true
      var nodes = [].concat(Array.from(record.addedNodes || []), Array.from(record.removedNodes || []))
      return !nodes.length || nodes.some(function (n) {
        return n && (n.tagName === 'SELECT' || n.tagName === 'OPTION' || n.tagName === 'OPTGROUP' ||
          (typeof n.querySelector === 'function' && !!n.querySelector('select')))
      })
    }
    if (target.tagName === 'SELECT' || target.tagName === 'OPTION' || target.tagName === 'OPTGROUP') return true
    return typeof target.querySelector === 'function' && !!target.querySelector('select')
  }
  var observer = new MutationObserver(function (records) {
    if (active && records.some(function (record) { return active.select.contains(record.target) })) close()
    if (!records.length || records.some(isSelectMutation)) schedule()
  })
  observer.observe(document.body, {childList: true, subtree: true, attributes: true, attributeFilter: ['disabled','selected','hidden','style','class','required']})
  document.addEventListener('click', outside); window.addEventListener('scroll', scroll, scrollOptions)
  window.addEventListener('resize', close); window.addEventListener('hashchange', close); window.addEventListener('pageshow', refresh)
  refresh()
  if (window.BoloNavigation) window.BoloNavigation.onLeave(function () {
    disposed = true; close(); observer.disconnect(); document.removeEventListener('click', outside); window.removeEventListener('scroll', scroll, scrollOptions)
    window.removeEventListener('resize', close); window.removeEventListener('hashchange', close); window.removeEventListener('pageshow', refresh)
    if (previousSet && jQuery.valHooks.select.set === setValue) jQuery.valHooks.select.set = previousSet
    if (window.jQuery) jQuery(document).off('ajaxComplete.voloSelect', schedule)
    controls.clear()
  })
})()
