/* SPDX-License-Identifier: AGPL-3.0-or-later */
admin.pagination = {
  render: function (target, current, count, change) {
    var host = typeof target === 'string' ? document.getElementById(target) : target
    if (!host) return
    count = Math.max(1, Number(count) || 1); current = Math.min(count, Math.max(1, Number(current) || 1))
    host.replaceChildren(); host.classList.add('console-pagination'); host.setAttribute('aria-label', '分页')
    function node(tag, text, name) {
      var item = document.createElement(tag); if (text != null) item.textContent = text
      if (name) item.className = name
      return item
    }
    function button(text, title, page, disabled) {
      var item = node('button', text); item.type = 'button'; item.title = title; item.setAttribute('aria-label', title)
      item.disabled = disabled; item.addEventListener('click', function () { if (!item.disabled && page !== current) change(page) })
      host.append(item); return item
    }
    if (count > 1) {
      button('‹', '上一页', current - 1, current === 1)
      var start = Math.max(1, Math.min(current - 1, count - 2)), end = Math.min(count, start + 2)
      for (var page = start; page <= end; page++) {
        var item = button(String(page), '第 ' + page + ' 页', page, false)
        if (page === current) item.setAttribute('aria-current', 'page')
      }
      button('›', '下一页', current + 1, current === count)
    }
    host.append(node('span', current + ' / ' + count, 'console-page-count'))
    if (count <= 1) return
    var jump = node('div', null, 'console-page-jump'), input = node('input'), submit = node('button', '跳转')
    input.type = 'text'; input.inputMode = 'numeric'; input.autocomplete = 'off'; input.maxLength = 8
    input.placeholder = '页码'; input.setAttribute('aria-label', '跳转到页码'); input.setAttribute('enterkeyhint', 'go')
    submit.type = 'button'
    function go() {
      var value = input.value.trim(), page = Number(value)
      if (!/^[1-9]\d*$/.test(value) || page > count) {
        input.setAttribute('aria-invalid', 'true'); input.focus()
        if (admin.showNotice) admin.showNotice('请输入 1 到 ' + count + ' 之间的页码。', 'error')
        return
      }
      input.removeAttribute('aria-invalid'); if (page !== current) change(page)
    }
    input.addEventListener('input', function () { input.removeAttribute('aria-invalid') })
    input.addEventListener('keydown', function (event) { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); go() } })
    submit.addEventListener('click', go); jump.append(input, submit); host.append(jump)
  }
}
