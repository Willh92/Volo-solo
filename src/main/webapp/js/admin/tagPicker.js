/* SPDX-License-Identifier: AGPL-3.0-or-later */
admin.tagPicker = {
  key: function (value) { return value.normalize('NFKC').toLocaleLowerCase() },
  read: function (value) {
    var that = this, seen = new Set()
    return String(value || '').split(/[,，\n]+/).map(function (tag) { return tag.trim() }).filter(function (tag) {
      if (!tag || seen.has(that.key(tag))) return false
      seen.add(that.key(tag)); return true
    })
  },
  init: function () {
    this.destroy()
    var input = document.getElementById('tag'), that = this
    if (!input) return
    var toggle = document.getElementById('articleTagToggle'), panel = document.getElementById('articleTagPanel')
    var selection = document.getElementById('articleTagSelection'), search = document.getElementById('articleTagSearch')
    var options = document.getElementById('articleTagOptions'), count = document.getElementById('articleTagCount')
    var draft = document.getElementById('articleTagInput')
    var tags = [], listeners = []
    function on(target, type, fn) { target.addEventListener(type, fn); listeners.push(function () { target.removeEventListener(type, fn) }) }
    function close() { panel.hidden = true; toggle.setAttribute('aria-expanded', 'false'); toggle.textContent = '选择标签' }
    function canonical(tag) { return tags.find(function (known) { return that.key(known) === that.key(tag) }) || tag }
    function write(selected) {
      input.value = that.read(selected.map(canonical).join(',')).join(',')
      input.dispatchEvent(new Event('input', {bubbles: true}))
      input.dispatchEvent(new Event('change', {bubbles: true}))
    }
    function commit() {
      var additions = that.read(draft.value)
      if (!additions.length) return
      draft.value = ''; write(that.read(input.value).concat(additions))
    }
    function choose(tag) {
      tag = canonical(tag)
      var selected = that.read(input.value), at = selected.findIndex(function (value) { return that.key(value) === that.key(tag) })
      if (at < 0) selected.push(tag); else selected.splice(at, 1)
      write(selected)
    }
    function option(tag, selected) {
      var button = document.createElement('button')
      button.type = 'button'; button.className = 'console-tag-option'; button.textContent = tag
      button.setAttribute('aria-pressed', String(selected)); button.addEventListener('click', function () {
        commit(); choose(tag)
        Array.from(options.querySelectorAll('button')).find(function (item) { return item.textContent === tag })?.focus()
      })
      return button
    }
    function drawOptions() {
      if (panel.hidden) return
      var selected = that.read(input.value), query = search.value.trim().toLocaleLowerCase()
      options.replaceChildren()
      tags.filter(function (tag) { return tag.toLocaleLowerCase().includes(query) }).forEach(function (tag) { options.append(option(tag, selected.includes(tag))) })
      if (!options.children.length) {
        var empty = document.createElement('p'); empty.className = 'console-tag-empty'; empty.textContent = '未找到标签，可在上方直接输入新标签。'; options.append(empty)
      }
      count.textContent = '已选 ' + selected.length + ' / 共 ' + tags.length + ' 个'
    }
    function refresh() {
      selection.replaceChildren()
      var selected = that.read(input.value).map(canonical)
      input.value = selected.join(',')
      selected.forEach(function (tag) {
        var chip = document.createElement('button'), remove = document.createElement('span')
        chip.type = 'button'; chip.className = 'console-tag-chip'; chip.textContent = tag
        chip.setAttribute('aria-label', '移除标签：' + tag); remove.textContent = '×'; remove.setAttribute('aria-hidden', 'true'); chip.append(remove)
        chip.addEventListener('click', function () { commit(); choose(tag); draft.focus() }); selection.append(chip)
      })
      draft.placeholder = selected.length ? '添加标签…' : '输入标签，按回车添加'
      drawOptions()
    }
    this.state = {refresh: refresh, close: close, reset: function () { draft.value = ''; close(); refresh() }, setTags: function (values) {
      tags = that.read((Array.isArray(values) ? values : []).map(function (tag) { return typeof tag === 'string' ? tag : tag.tagTitle || '' }).join(','))
      toggle.disabled = !tags.length; toggle.title = tags.length ? '选择已有标签' : '暂无已有标签，可直接输入新标签'; refresh()
    }}
    on(input, 'input', refresh)
    on(input, 'change', refresh)
    on(draft, 'keydown', function (event) {
      if (event.isComposing) return
      if (event.key === 'Enter') { event.preventDefault(); commit() }
      else if (event.key === 'Backspace' && !draft.value) selection.lastElementChild?.focus()
    })
    on(draft, 'blur', function (event) { if (!draft.closest('.console-tag-field').contains(event.relatedTarget)) commit() })
    on(toggle, 'click', function () {
      commit()
      if (!panel.hidden) { close(); return }
      panel.hidden = false; toggle.setAttribute('aria-expanded', 'true'); toggle.textContent = '收起标签'; search.value = ''; drawOptions(); search.focus()
    })
    on(search, 'input', drawOptions)
    on(search, 'keydown', function (event) {
      if (event.isComposing) return
      if (event.key === 'Enter' || event.key === 'ArrowDown') {
        event.preventDefault()
        var first = options.querySelector('button'); if (first) first.focus()
      }
    })
    on(panel, 'keydown', function (event) { if (event.key === 'Escape') { event.preventDefault(); close(); toggle.focus() } })
    on(document, 'click', function (event) {
      var field = input.closest('.console-tag-field')
      if (!field.contains(event.target) && !(event.composedPath && event.composedPath().includes(field))) close()
    })
    on(window, 'hashchange', close)
    this.cleanup = function () { listeners.forEach(function (remove) { remove() }); close(); that.state = null; that.cleanup = null }
    close(); this.state.setTags([])
    if (window.BoloNavigation) window.BoloNavigation.onLeave(function () { that.destroy() })
  },
  setTags: function (tags) { if (this.state) this.state.setTags(tags) },
  refresh: function () { if (this.state) this.state.refresh() },
  reset: function () { if (this.state) this.state.reset() },
  destroy: function () { if (this.cleanup) this.cleanup() }
}
