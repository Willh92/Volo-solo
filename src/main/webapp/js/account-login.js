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
  var form = document.getElementById('loginForm')
  if (!form || form.dataset.bound) return
  form.dataset.bound = 'true'
  var button = document.getElementById('loginBtn')
  var status = document.getElementById('status')
  var password = document.getElementById('password')
  var mode = form.dataset.mode
  var busy = false
  var retryUntil = 0
  var retryTimer
  var labels = {
    '-1': ['创建管理账号', '欢迎使用Volo。设置用户名和密码，开始记录你的想法。', '创建并进入后台'],
    '2': ['迁移到Volo', '请先备份数据库，再设置迁移后的管理账号。', '开始迁移'],
    'default': ['欢迎回来', '登录你的博客，继续上次的创作。', '登录管理后台'],
  }
  var copy = labels[mode] || labels.default
  document.getElementById('loginHeading').textContent = copy[0]
  document.getElementById('loginDescription').textContent = copy[1]
  button.querySelector('span').textContent = copy[2]
  if (mode === '-1' || mode === '2') password.autocomplete = 'new-password'
  function message (text) {
    status.textContent = text
    status.hidden = !text
    form.classList.toggle('has-error', !!text)
    password.setAttribute('aria-invalid', text ? 'true' : 'false')
  }
  if (mode === '0') message('用户名或密码错误，请重试。')
  document.getElementById('togglePassword').addEventListener('click', function () {
    var show = password.type === 'password'
    password.type = show ? 'text' : 'password'
    this.setAttribute('aria-pressed', String(show))
    this.setAttribute('aria-label', show ? '隐藏密码' : '显示密码')
  })
  function cooldown (seconds) {
    retryUntil = Date.now() + Math.max(1, Math.min(86400, Number(seconds) || 60)) * 1000
    clearInterval(retryTimer)
    function update () {
      var remaining = Math.max(0, Math.ceil((retryUntil - Date.now()) / 1000))
      button.disabled = remaining > 0 || busy
      button.querySelector('span').textContent = remaining > 0 ? Math.floor(remaining / 60) + ':' + String(remaining % 60).padStart(2, '0') + ' 后可重试' : copy[2]
      if (!remaining) { clearInterval(retryTimer); message('现在可以重新尝试登录。') }
    }
    update()
    retryTimer = setInterval(update, 1000)
  }
  if (window.BoloNavigation) window.BoloNavigation.onLeave(function () { clearInterval(retryTimer) })
  form.addEventListener('input', function () { if (!busy && Date.now() >= retryUntil) message('') })
  form.addEventListener('submit', async function (event) {
    event.preventDefault()
    if (busy || Date.now() < retryUntil) return
    message('')
    if (!form.reportValidity()) return
    if ((mode === '-1' || mode === '2') && !/^(?=.*[0-9])(?=.*[a-zA-Z]).{8,30}$/.test(password.value)) {
      message('请使用 8–30 位、包含字母和数字的密码。')
      password.focus()
      return
    }
    busy = true
    button.disabled = true
    form.setAttribute('aria-busy', 'true')
    button.querySelector('span').textContent = mode === '2' ? '正在迁移…' : mode === '-1' ? '正在初始化…' : '正在登录…'
    try {
      var target = new URL(form.action, window.location.href)
      // Keep credentials on the current origin even when a public URL setting is misconfigured.
      var response = await fetch(target.pathname + target.search, {
        method: 'POST', credentials: 'same-origin', redirect: 'error',
        headers: {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'},
        body: new URLSearchParams(new FormData(form)),
      })
      var result = await response.json()
      if (response.status === 429) {
        message(result.msg || '登录尝试过于频繁，请稍后再试。')
        cooldown(result.retryAfter || response.headers.get('Retry-After'))
        return
      }
      if (!response.ok || !result.sc) {
        message(result.msg || '用户名或密码错误，请重试。')
        password.focus()
        return
      }
      var next = new URL(result.redirect, window.location.origin)
      if (next.origin !== window.location.origin) throw new Error('Invalid login redirect')
      button.querySelector('span').textContent = '正在进入后台…'
      await window.BoloNavigation.navigate(next.href, {replace: true})
    } catch (error) {
      message('暂时无法连接登录服务，请稍后重试。')
    } finally {
      busy = false
      button.disabled = Date.now() < retryUntil
      form.removeAttribute('aria-busy')
      if (!button.disabled) button.querySelector('span').textContent = copy[2]
    }
  })
})()
