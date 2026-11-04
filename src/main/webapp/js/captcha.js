/* SPDX-License-Identifier: AGPL-3.0-or-later */
(function () {
  'use strict'
  if (window.VoloCaptcha) return
  function mount (host, purpose, stateChanged) {
    var label = document.createElement('label'), title = document.createElement('span'), row = document.createElement('div')
    var answer = document.createElement('input'), refresh = document.createElement('button'), image = document.createElement('img'), message = document.createElement('p')
    var id = '', required = true, ready = false, disposed = false, revision = 0, expiresAt = 0, request
    host.classList.add('volo-captcha'); title.textContent = '图片验证码'; row.className = 'volo-captcha-row'
    answer.name = 'captchaAnswer'; answer.autocomplete = 'off'; answer.maxLength = 5; answer.pattern = '[A-Za-z0-9]{5}'
    answer.placeholder = '输入图中 5 位字符'; answer.required = true; answer.setAttribute('aria-label', '图片验证码，不区分大小写')
    refresh.type = 'button'; refresh.className = 'volo-captcha-refresh'; refresh.setAttribute('aria-label', '刷新验证码')
    image.alt = '验证码，点击换一张'; image.width = 180; image.height = 64
    refresh.append(image); label.append(title, row); row.append(answer, refresh); message.setAttribute('role', 'status'); host.replaceChildren(label, message)
    function state () { if (stateChanged) stateChanged({ready: ready, required: required}) }
    async function reload () {
      var current = ++revision; request?.abort(); request = new AbortController(); var controller = request
      ready = false; id = ''; answer.value = ''; expiresAt = 0; message.textContent = '正在获取验证码…'; host.setAttribute('aria-busy', 'true'); state()
      var timeout = setTimeout(function () { controller.abort() }, 10000)
      try {
        // The app can run under a context path; callers supply the server-owned context prefix.
        var prefix = host.dataset.context || ''
        var response = await fetch(prefix + '/captcha?purpose=' + encodeURIComponent(purpose), {credentials:'same-origin',cache:'no-store',signal:controller.signal})
        var result = await response.json()
        if (disposed || current !== revision) return
        if (!response.ok || !result.sc) throw new Error(result.msg || '验证码获取失败，点击图片重试。')
        required = result.required !== false; host.hidden = !required; answer.required = required
        if (required) { id = result.captchaId; expiresAt = Number(result.expiresAt); image.src = result.image; message.textContent = '不区分大小写，3 分钟内有效。点击图片换一张。' }
        else message.textContent = ''
        ready = true; state()
      } catch (error) {
        if (!disposed && current === revision) { message.textContent = error.name === 'AbortError' ? '验证码获取超时，点击图片重试。' : error.message; state() }
      } finally { clearTimeout(timeout); if (current === revision) host.removeAttribute('aria-busy') }
    }
    function onPageShow (event) { if (event && event.persisted && !disposed) reload() }
    function onVisibilityChange () { if (!document.hidden && !disposed && ready && required && expiresAt <= Date.now()) reload() }
    refresh.addEventListener('click', reload)
    if (window.addEventListener) window.addEventListener('pageshow', onPageShow)
    if (document.addEventListener) document.addEventListener('visibilitychange', onVisibilityChange)
    var initial = reload()
    return {ready:initial, refresh:reload, payload:function () { return !required ? {} : {captchaId:id,captchaAnswer:answer.value.trim()} },
      valid:function () {
        if (!ready) return false
        if (required && expiresAt <= Date.now()) { reload(); answer.focus(); return false }
        return !required || answer.reportValidity()
      }, focus:function () { answer.focus() },
      ensureFresh:function () { if (ready && required && expiresAt <= Date.now()) reload() },
      isRequired:function () { return required },
      destroy:function () {
        disposed = true; ++revision; request?.abort()
        if (window.removeEventListener) window.removeEventListener('pageshow', onPageShow)
        if (document.removeEventListener) document.removeEventListener('visibilitychange', onVisibilityChange)
      }}
  }
  window.VoloCaptcha = {mount:mount}
})()
