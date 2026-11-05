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
  var username = document.getElementById('username')
  var password = document.getElementById('password')
  var toggleBtn = document.getElementById('togglePassword')
  var captchaIdInput = document.getElementById('loginCaptchaId')
  var primaryFields = document.getElementById('loginPrimaryFields')
  var totpFields = document.getElementById('loginTotpFields')
  var totpTokenInput = document.getElementById('loginTotpToken')
  var totpCodeInput = document.getElementById('totpCode')
  var totpRememberRow = document.getElementById('totpRememberRow')
  var totpRememberInput = document.getElementById('rememberDevice')
  var totpRememberLabel = document.getElementById('totpRememberLabel')
  var totpBackBtn = document.getElementById('totpBackBtn')
  var passkeyBox = document.getElementById('passkeyLoginBox')
  var passkeyBtn = document.getElementById('passkeyLoginBtn')
  var heading = document.getElementById('loginHeading')
  var description = document.getElementById('loginDescription')
  var mode = form.dataset.mode
  var busy = false
  var totpStep = false
  var captchaReady = true
  var retryUntil = 0
  var retryTimer
  var labels = {
    '-1': ['创建管理账号', '欢迎使用Volo。设置用户名和密码，开始记录你的想法。', '创建并进入后台'],
    '2': ['迁移到Volo', '请先备份数据库，再设置迁移后的管理账号。', '开始迁移'],
    'default': ['欢迎回来', '登录你的博客，继续上次的创作。', '登录管理后台'],
  }
  var copy = labels[mode] || labels.default
  heading.textContent = copy[0]
  description.textContent = copy[1]
  button.querySelector('span').textContent = copy[2]
  if (mode === '-1' || mode === '2') password.autocomplete = 'new-password'

  function currentActionText () {
    return totpStep ? '验证并进入后台' : copy[2]
  }

  function syncButtonState () {
    var throttled = Date.now() < retryUntil
    button.disabled = busy || throttled || (!totpStep && !captchaReady)
    if (passkeyBtn) passkeyBtn.disabled = busy || throttled
    if (passkeyBox) {
      var canPasskey = !totpStep && mode !== '-1' && mode !== '2' && !!(window.PublicKeyCredential && navigator && navigator.credentials && typeof navigator.credentials.get === 'function')
      passkeyBox.hidden = !canPasskey
    }
    if (!busy && !throttled) {
      button.querySelector('span').textContent = currentActionText()
    }
  }

  function hidePassword () {
    if (!password) return
    password.type = 'password'
    if (toggleBtn && toggleBtn.setAttribute) {
      toggleBtn.setAttribute('aria-pressed', 'false')
      toggleBtn.setAttribute('aria-label', '显示密码')
    }
  }

  document.getElementById('loginCaptcha').dataset.context = new URL(form.action, window.location.href).pathname.replace(/\/oauth\/bolo\/login$/, '')
  var captcha = window.VoloCaptcha.mount(document.getElementById('loginCaptcha'), 'login', function (state) {
    captchaReady = !!state.ready
    syncButtonState()
  })

  function message (text) {
    status.textContent = text
    status.hidden = !text
    form.classList.toggle('has-error', !!text)
    if (totpStep && totpCodeInput) {
      totpCodeInput.setAttribute('aria-invalid', text ? 'true' : 'false')
    } else {
      password.setAttribute('aria-invalid', text ? 'true' : 'false')
    }
  }

  function enterTotpStep (result) {
    totpStep = true
    hidePassword()
    if (primaryFields) primaryFields.hidden = true
    if (username) username.disabled = true
    if (password) password.disabled = true
    if (captchaIdInput) captchaIdInput.disabled = true
    if (totpFields) totpFields.hidden = false
    if (totpTokenInput) {
      totpTokenInput.disabled = false
      totpTokenInput.value = result.totpToken
    }
    if (totpCodeInput) {
      totpCodeInput.disabled = false
      totpCodeInput.required = true
      totpCodeInput.value = ''
      totpCodeInput.setAttribute('aria-invalid', 'false')
    }
    var days = Number(result.rememberDays) || 0
    if (days > 0 && totpRememberRow && totpRememberInput) {
      totpRememberRow.hidden = false
      totpRememberInput.disabled = false
      totpRememberInput.checked = false
      if (totpRememberLabel) totpRememberLabel.textContent = '在此浏览器 ' + days + ' 天内免 2FA 验证'
    } else if (totpRememberRow && totpRememberInput) {
      totpRememberRow.hidden = true
      totpRememberInput.disabled = true
      totpRememberInput.checked = false
    }
    if (totpBackBtn) totpBackBtn.hidden = false
    heading.textContent = '2FA 双重验证'
    description.textContent = '账号密码已验证通过，请输入 2FA 动态口令。'
    message('')
    syncButtonState()
    if (totpCodeInput && totpCodeInput.focus) totpCodeInput.focus()
  }

  function exitTotpStep (errorText, silent) {
    totpStep = false
    if (totpFields) totpFields.hidden = true
    if (totpTokenInput) {
      totpTokenInput.value = ''
      totpTokenInput.disabled = true
    }
    if (totpCodeInput) {
      totpCodeInput.value = ''
      totpCodeInput.required = false
      totpCodeInput.disabled = true
    }
    if (totpRememberRow && totpRememberInput) {
      totpRememberRow.hidden = true
      totpRememberInput.checked = false
      totpRememberInput.disabled = true
    }
    if (totpBackBtn) totpBackBtn.hidden = true
    if (primaryFields) primaryFields.hidden = false
    if (username) username.disabled = false
    if (password) password.disabled = false
    if (captchaIdInput) captchaIdInput.disabled = false
    heading.textContent = copy[0]
    description.textContent = copy[1]
    if (form.isConnected) captcha.refresh()
    message(errorText || '')
    syncButtonState()
    if (!silent && password && password.focus) password.focus()
  }

  function onPageShow (event) {
    if (!event || !event.persisted) return
    busy = false
    form.removeAttribute('aria-busy')
    hidePassword()
    if (password) password.value = ''
    if (captchaIdInput) captchaIdInput.value = ''
    if (totpStep) exitTotpStep('', true)
    else {
      captcha.refresh()
      message('')
      syncButtonState()
    }
  }

  if (mode === '0') message('用户名或密码错误，请重试。')
  if (toggleBtn && toggleBtn.addEventListener) {
    toggleBtn.addEventListener('click', function () {
      var show = password.type === 'password'
      password.type = show ? 'text' : 'password'
      this.setAttribute('aria-pressed', String(show))
      this.setAttribute('aria-label', show ? '隐藏密码' : '显示密码')
    })
  }
  if (totpBackBtn && totpBackBtn.addEventListener) {
    totpBackBtn.addEventListener('click', function () {
      if (!busy) exitTotpStep('')
    })
  }
  function b64UrlToBytes (b64url) {
    var str = String(b64url || '').replace(/-/g, '+').replace(/_/g, '/')
    while (str.length % 4 !== 0) str += '='
    var bin = atob(str)
    var out = new Uint8Array(bin.length)
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
    return out
  }
  function bytesToB64Url (buf) {
    var bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf || 0)
    var bin = ''
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  }
  if (passkeyBtn && passkeyBtn.addEventListener) {
    passkeyBtn.addEventListener('click', async function () {
      if (busy || Date.now() < retryUntil) return
      message('')
      busy = true
      syncButtonState()
      form.setAttribute('aria-busy', 'true')
      var passkeyLabel = passkeyBtn.querySelector && passkeyBtn.querySelector('span')
      if (passkeyLabel) passkeyLabel.textContent = '正在唤醒通行密钥…'
      try {
        var base = new URL(form.action, window.location.href).pathname.replace(/\/oauth\/bolo\/login$/, '')
        var optResp = await fetch(base + '/oauth/bolo/passkey/options', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
          body: JSON.stringify({ username: username ? (username.value || '').trim() : '' })
        })
        var optJson = await optResp.json()
        if (optResp.status === 429) {
          message(optJson.msg || '登录尝试过于频繁，请稍后再试。')
          cooldown(optJson.retryAfter || optResp.headers.get('Retry-After'))
          return
        }
        if (!optResp.ok || !optJson.sc || !optJson.data) {
          message(optJson.msg || '尚未绑定通行密钥，请先使用账号密码登录。')
          return
        }
        var opts = optJson.data
        var publicKey = {
          challenge: b64UrlToBytes(opts.challenge),
          rpId: opts.rpId,
          timeout: opts.timeout || 60000,
          userVerification: opts.userVerification || 'preferred'
        }
        if (Array.isArray(opts.allowCredentials) && opts.allowCredentials.length > 0) {
          publicKey.allowCredentials = opts.allowCredentials.map(function (c) {
            return { type: c.type || 'public-key', id: b64UrlToBytes(c.id) }
          })
        }
        var assertion = await navigator.credentials.get({ publicKey: publicKey })
        if (!assertion || !assertion.response) {
          message('未获取到通行密钥签名。')
          return
        }
        if (passkeyLabel) passkeyLabel.textContent = '正在验证通行密钥…'
        var verifyResp = await fetch(base + '/oauth/bolo/passkey/verify', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
          body: JSON.stringify({
            challengeId: opts.challengeId,
            credential: {
              id: assertion.id,
              rawId: bytesToB64Url(assertion.rawId),
              type: assertion.type || 'public-key',
              response: {
                clientDataJSON: bytesToB64Url(assertion.response.clientDataJSON),
                authenticatorData: bytesToB64Url(assertion.response.authenticatorData),
                signature: bytesToB64Url(assertion.response.signature),
                userHandle: assertion.response.userHandle ? bytesToB64Url(assertion.response.userHandle) : ''
              }
            }
          })
        })
        var verifyJson = await verifyResp.json()
        if (verifyResp.status === 429) {
          message(verifyJson.msg || '登录尝试过于频繁，请稍后再试。')
          cooldown(verifyJson.retryAfter || verifyResp.headers.get('Retry-After'))
          return
        }
        if (!verifyResp.ok || !verifyJson.sc) {
          message(verifyJson.msg || '通行密钥校验未通过，请重试或使用密码登录。')
          return
        }
        var next = new URL(verifyJson.redirect, window.location.origin)
        if (next.origin !== window.location.origin) throw new Error('Invalid login redirect')
        if (passkeyLabel) passkeyLabel.textContent = '正在进入后台…'
        await window.BoloNavigation.navigate(next.href, { replace: true })
      } catch (err) {
        if (err && err.name === 'NotAllowedError') {
          message('已取消通行密钥验证，您仍可使用密码登录。')
        } else {
          message('通行密钥登录未完成，请重试或使用密码登录。')
        }
      } finally {
        busy = false
        if (passkeyLabel) passkeyLabel.textContent = '使用通行密钥（Passkey）登录'
        form.removeAttribute('aria-busy')
        syncButtonState()
      }
    })
  }
  if (window.addEventListener) window.addEventListener('pageshow', onPageShow)

  function cooldown (seconds) {
    retryUntil = Date.now() + Math.max(1, Math.min(86400, Number(seconds) || 60)) * 1000
    clearInterval(retryTimer)
    function update () {
      var remaining = Math.max(0, Math.ceil((retryUntil - Date.now()) / 1000))
      if (remaining > 0) {
        button.disabled = true
        button.querySelector('span').textContent = Math.floor(remaining / 60) + ':' + String(remaining % 60).padStart(2, '0') + ' 后可重试'
      } else {
        clearInterval(retryTimer)
        syncButtonState()
        message('现在可以重新尝试登录。')
      }
    }
    update()
    retryTimer = setInterval(update, 1000)
  }

  if (window.BoloNavigation) {
    window.BoloNavigation.onLeave(function () {
      clearInterval(retryTimer)
      if (window.removeEventListener) window.removeEventListener('pageshow', onPageShow)
      captcha.destroy()
    })
  }
  form.addEventListener('input', function () { if (!busy && Date.now() >= retryUntil) message('') })
  form.addEventListener('submit', async function (event) {
    event.preventDefault()
    if (busy || Date.now() < retryUntil) return
    message('')
    if (totpStep) {
      var rawCode = (totpCodeInput.value || '').replace(/[\s-]+/g, '')
      if (!/^[0-9]{6}$/.test(rawCode) && !/^[A-Za-z2-9]{8}$/.test(rawCode)) {
        message('请输入 6 位数字动态口令，或 8 位应急恢复码。')
        totpCodeInput.focus()
        return
      }
    } else {
      if (!form.reportValidity() || !captcha.valid()) return
      var payload = captcha.payload ? captcha.payload() : {}
      if (captchaIdInput) {
        captchaIdInput.disabled = !payload.captchaId
        captchaIdInput.value = payload.captchaId || ''
      }
      if ((mode === '-1' || mode === '2') && !/^(?=.*[0-9])(?=.*[a-zA-Z]).{8,30}$/.test(password.value)) {
        message('请使用 8–30 位、包含字母和数字的密码。')
        password.focus()
        return
      }
    }
    var submittedTotpStep = totpStep
    var shouldRefreshCaptcha = !submittedTotpStep
    busy = true
    button.disabled = true
    form.setAttribute('aria-busy', 'true')
    button.querySelector('span').textContent = submittedTotpStep ? '正在校验口令…' : mode === '2' ? '正在迁移…' : mode === '-1' ? '正在初始化…' : '正在登录…'
    try {
      var bodyParams = new URLSearchParams(new FormData(form))
      if (submittedTotpStep) {
        bodyParams.delete('captchaAnswer')
      }
      var target = new URL(form.action, window.location.href)
      // Keep credentials on the current origin even when a public URL setting is misconfigured.
      var response = await fetch(target.pathname + target.search, {
        method: 'POST', credentials: 'same-origin', redirect: 'error',
        headers: {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'},
        body: bodyParams,
      })
      var result = await response.json()
      if (response.status === 429) {
        message(result.msg || '登录尝试过于频繁，请稍后再试。')
        cooldown(result.retryAfter || response.headers.get('Retry-After'))
        return
      }
      if (!submittedTotpStep && result.totpRequired && result.totpToken) {
        shouldRefreshCaptcha = false
        busy = false
        enterTotpStep(result)
        return
      }
      if (!response.ok || !result.sc) {
        if (submittedTotpStep) {
          if (result.totpExpired) {
            busy = false
            exitTotpStep(result.msg || '2FA 验证会话已过期，请重新登录。')
            return
          }
          message(result.msg || '动态口令或应急恢复码不正确，请重试。')
          totpCodeInput.select()
          totpCodeInput.focus()
          return
        }
        message(result.msg || '用户名或密码错误，请重试。')
        password.focus()
        return
      }
      shouldRefreshCaptcha = false
      var next = new URL(result.redirect, window.location.origin)
      if (next.origin !== window.location.origin) throw new Error('Invalid login redirect')
      hidePassword()
      if (password) password.value = ''
      if (totpCodeInput) totpCodeInput.value = ''
      if (totpTokenInput) totpTokenInput.value = ''
      button.querySelector('span').textContent = '正在进入后台…'
      await window.BoloNavigation.navigate(next.href, {replace: true})
    } catch (error) {
      message('暂时无法连接登录服务，请稍后重试。')
    } finally {
      busy = false
      if (shouldRefreshCaptcha && form.isConnected) captcha.refresh()
      form.removeAttribute('aria-busy')
      syncButtonState()
    }
  })
})()
