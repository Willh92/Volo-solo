/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
(function () {
    'use strict';

    if (window.__voloEnhancementsBooted) {
        if (typeof window.__voloEnhancementsRefresh === 'function') {
            window.__voloEnhancementsRefresh();
        }
        return;
    }
    window.__voloEnhancementsBooted = true;

    function getConfig() {
        var cfg = window.VoloSiteConfig || {};
        var servePath = cfg.servePath || (window.Label && window.Label.servePath) || '';
        return {
            servePath: servePath.replace(/\/$/, ''),
            allowRegister: Boolean(cfg.allowRegister),
            commentAccessMode: cfg.commentAccessMode || 'all',
            enableInstantSearch: cfg.enableInstantSearch !== false,
            isLoggedIn: Boolean(cfg.isLoggedIn),
            isAdminLoggedIn: Boolean(cfg.isAdminLoggedIn),
            userName: String(cfg.userName || '')
        };
    }

    function isConsolePage() {
        return Boolean(document.body && document.body.classList && document.body.classList.contains('bolo-console'));
    }

    function escapeHtml(val) {
        return String(val == null ? '' : val)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function isSafeChapterUrl(url) {
        return /^(?:https?:\/\/|\/(?!\/))/i.test(String(url || '').trim());
    }

    function navigateTo(url) {
        if (!url) return;
        if (window.BoloNavigation && typeof window.BoloNavigation.navigate === 'function') {
            try {
                window.BoloNavigation.navigate(url);
                return;
            } catch (ignored) {}
        }
        window.location.href = url;
    }

    // =========================================================================
    // 1. Instant Search Modal (Ctrl/Cmd + K)
    // =========================================================================
    var searchModal = null;
    var searchInput = null;
    var searchResultsEl = null;
    var searchFiltersEl = null;
    var searchTimer = null;
    var activeTag = '';
    var activeCategory = '';
    var activeIndex = -1;
    var currentItems = [];

    function ensureSearchModal() {
        if (searchModal && document.body && document.body.contains(searchModal)) {
            return searchModal;
        }
        var wrap = document.createElement('div');
        wrap.className = 'volo-modal-backdrop volo-search-backdrop';
        wrap.hidden = true;
        wrap.setAttribute('role', 'dialog');
        wrap.setAttribute('aria-modal', 'true');
        wrap.setAttribute('aria-label', '站内即时搜索');
        wrap.innerHTML =
            '<div class="volo-search-dialog">' +
            '  <div class="volo-search-head">' +
            '    <svg class="volo-search-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>' +
            '    <input type="search" class="volo-search-input" placeholder="搜索文章标题、标签或正文关键词..." autocomplete="off" />' +
            '    <button type="button" class="volo-search-esc" data-volo-search-close>ESC</button>' +
            '  </div>' +
            '  <div class="volo-search-filters" hidden></div>' +
            '  <div class="volo-search-results" role="listbox"></div>' +
            '  <div class="volo-search-foot">' +
            '    <span><kbd>↑</kbd><kbd>↓</kbd> 选择 · <kbd>Enter</kbd> 打开 · <kbd>Esc</kbd> 关闭</span>' +
            '    <span>Ctrl / ⌘ + K</span>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(wrap);
        searchModal = wrap;
        searchInput = wrap.querySelector('.volo-search-input');
        searchResultsEl = wrap.querySelector('.volo-search-results');
        searchFiltersEl = wrap.querySelector('.volo-search-filters');

        wrap.addEventListener('click', function (e) {
            if (e.target === wrap || e.target.closest('[data-volo-search-close]')) {
                closeSearch();
                return;
            }
            var chip = e.target.closest('[data-search-filter-type]');
            if (chip) {
                var type = chip.getAttribute('data-search-filter-type');
                var val = chip.getAttribute('data-search-filter-val') || '';
                if (type === 'tag') {
                    activeTag = activeTag === val ? '' : val;
                } else if (type === 'category') {
                    activeCategory = activeCategory === val ? '' : val;
                }
                performSearch();
                return;
            }
            var itemLink = e.target.closest('.volo-search-item');
            if (itemLink) {
                closeSearch();
            }
        });

        searchInput.addEventListener('input', function () {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(performSearch, 140);
        });

        searchInput.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                moveSelection(1);
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                moveSelection(-1);
            } else if (e.key === 'Enter' && activeIndex >= 0 && currentItems[activeIndex]) {
                e.preventDefault();
                var targetUrl = currentItems[activeIndex].url;
                closeSearch();
                navigateTo(targetUrl);
            }
        });

        return wrap;
    }

    function moveSelection(delta) {
        if (!currentItems.length || !searchResultsEl) return;
        activeIndex = (activeIndex + delta + currentItems.length) % currentItems.length;
        var nodes = searchResultsEl.querySelectorAll('.volo-search-item');
        nodes.forEach(function (node, idx) {
            node.classList.toggle('is-active', idx === activeIndex);
            if (idx === activeIndex && node.scrollIntoView) {
                node.scrollIntoView({ block: 'nearest' });
            }
        });
    }

    function openSearch(initialQuery) {
        if (isConsolePage() || !getConfig().enableInstantSearch) return;
        var modal = ensureSearchModal();
        modal.hidden = false;
        if (typeof initialQuery === 'string') {
            searchInput.value = initialQuery;
        }
        performSearch();
        setTimeout(function () { searchInput.focus(); }, 20);
    }

    function closeSearch() {
        if (searchModal) {
            searchModal.hidden = true;
        }
    }

    function performSearch() {
        if (!searchResultsEl) return;
        var cfg = getConfig();
        var q = searchInput ? searchInput.value.trim() : '';
        var params = new URLSearchParams();
        if (q) params.set('q', q);
        if (activeTag) params.set('tag', activeTag);
        if (activeCategory) params.set('category', activeCategory);
        params.set('limit', '15');

        fetch(cfg.servePath + '/api/search/instant?' + params.toString(), {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (!data || !data.sc) {
                    searchResultsEl.innerHTML = '<div class="volo-search-empty">' + escapeHtml((data && data.msg) || '搜索暂不可用') + '</div>';
                    return;
                }
                renderSearchFilters(data.categories || [], data.tags || []);
                currentItems = Array.isArray(data.items) ? data.items : [];
                activeIndex = currentItems.length ? 0 : -1;
                if (!currentItems.length) {
                    searchResultsEl.innerHTML = '<div class="volo-search-empty">未找到匹配的文章，试试更换关键词或清除筛选标签。</div>';
                    return;
                }
                var html = '';
                for (var i = 0; i < currentItems.length; i++) {
                    var it = currentItems[i];
                    var metaHtml = '';
                    if (it.category) {
                        metaHtml += '<span class="volo-search-chip volo-search-chip--cat">' + escapeHtml(it.category) + '</span>';
                    }
                    if (Array.isArray(it.tags)) {
                        for (var t = 0; t < Math.min(3, it.tags.length); t++) {
                            metaHtml += '<span class="volo-search-chip">#' + escapeHtml(it.tags[t]) + '</span>';
                        }
                    }
                    html += '<a class="volo-search-item' + (i === 0 ? ' is-active' : '') + '" role="option" href="' + escapeHtml(it.url) + '">' +
                        '<div class="volo-search-item__title">' + (it.titleHtml || escapeHtml(it.title)) + '</div>' +
                        (it.snippetHtml ? '<div class="volo-search-item__snippet">' + it.snippetHtml + '</div>' : '') +
                        (metaHtml ? '<div class="volo-search-item__meta">' + metaHtml + '</div>' : '') +
                        '</a>';
                }
                searchResultsEl.innerHTML = html;
            })
            .catch(function () {
                searchResultsEl.innerHTML = '<div class="volo-search-empty">网络请求失败，请稍后重试。</div>';
            });
    }

    function renderSearchFilters(categories, tags) {
        if (!searchFiltersEl) return;
        if ((!categories || !categories.length) && (!tags || !tags.length)) {
            searchFiltersEl.hidden = true;
            return;
        }
        var html = '';
        for (var i = 0; i < Math.min(6, categories.length); i++) {
            var c = categories[i];
            var isCatActive = activeCategory && activeCategory.toLowerCase() === String(c).toLowerCase();
            html += '<button type="button" class="volo-filter-pill' + (isCatActive ? ' is-active' : '') + '" data-search-filter-type="category" data-search-filter-val="' + escapeHtml(c) + '">📁 ' + escapeHtml(c) + '</button>';
        }
        for (var j = 0; j < Math.min(8, tags.length); j++) {
            var tg = tags[j];
            var isTagActive = activeTag && activeTag.toLowerCase() === String(tg).toLowerCase();
            html += '<button type="button" class="volo-filter-pill' + (isTagActive ? ' is-active' : '') + '" data-search-filter-type="tag" data-search-filter-val="' + escapeHtml(tg) + '">#' + escapeHtml(tg) + '</button>';
        }
        searchFiltersEl.innerHTML = html;
        searchFiltersEl.hidden = false;
    }

    window.VoloSearch = { open: openSearch, close: closeSearch };

    // =========================================================================
    // 2. Reader Authentication & 6-Digit Email Verification (Modal + Page)
    // =========================================================================
    var authModal = null;

    function buildReaderAuthCardHtml(allowRegister, initialMode) {
        var isReg = allowRegister && initialMode === 'register';
        return '<div class="volo-reader-dialog" data-volo-reader-card data-allow-register="' + (allowRegister ? 'true' : 'false') + '">' +
            '  <div class="volo-reader-card-head">' +
            '    <div class="volo-reader-tabs" role="tablist">' +
            '      <button type="button" class="volo-reader-tab' + (!isReg ? ' is-active' : '') + '" data-reader-tab="login">读者登录</button>' +
            (allowRegister ? '<button type="button" class="volo-reader-tab' + (isReg ? ' is-active' : '') + '" data-reader-tab="register">邮箱注册</button>' : '') +
            '    </div>' +
            '    <button type="button" class="volo-modal-close" data-volo-auth-close aria-label="关闭">&times;</button>' +
            '  </div>' +
            '  <div class="volo-reader-pane"' + (isReg ? ' hidden' : '') + ' data-reader-pane="login">' +
            '    <p class="volo-reader-desc">使用注册邮箱或昵称登录，即可参与评论互动并解锁读者专享章节。</p>' +
            '    <form class="volo-reader-form" data-reader-login-form>' +
            '      <label>邮箱或昵称<input type="text" name="account" autocomplete="username" placeholder="请输入邮箱或昵称" required/></label>' +
            '      <label>登录密码<input type="password" name="password" autocomplete="current-password" placeholder="请输入密码" required/></label>' +
            '      <div class="volo-reader-alert" data-reader-msg hidden></div>' +
            '      <button type="submit" class="volo-reader-submit">登录账号</button>' +
            '    </form>' +
            '  </div>' +
            (allowRegister
                ? ('<div class="volo-reader-pane"' + (!isReg ? ' hidden' : '') + ' data-reader-pane="register">' +
                    '  <p class="volo-reader-desc">验证常用邮箱（6 位验证码）即可创建独立读者身份。</p>' +
                    '  <form class="volo-reader-form" data-reader-register-form>' +
                    '    <label>读者昵称<input type="text" name="userName" maxlength="32" autocomplete="nickname" placeholder="2-32 位昵称" required/></label>' +
                    '    <label>个人主页（选填）<input type="url" name="userURL" maxlength="255" autocomplete="url" placeholder="https://example.com"/></label>' +
                    '    <label>常用邮箱<input type="email" name="email" maxlength="254" autocomplete="email" placeholder="name@example.com" required/></label>' +
                    '    <div class="volo-reader-captcha-row">' +
                    '      <label class="volo-reader-captcha-field">图形验证码<input type="text" name="captchaAnswer" maxlength="5" autocomplete="off" placeholder="输入右侧字符"/></label>' +
                    '      <button type="button" class="volo-reader-captcha-img-btn" data-reader-captcha-refresh title="点击刷新验证码"><img alt="图形验证码" data-reader-captcha-img/></button>' +
                    '    </div>' +
                    '    <div class="volo-reader-code-row">' +
                    '      <label class="volo-reader-code-field">邮箱验证码<input type="text" name="emailCode" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位数字验证码" required/></label>' +
                    '      <button type="button" class="volo-reader-send-code" data-reader-send-code>获取验证码</button>' +
                    '    </div>' +
                    '    <label>设置密码<input type="password" name="password" autocomplete="new-password" placeholder="8-64 位，需含字母与数字" required/></label>' +
                    '    <div class="volo-reader-alert" data-reader-msg hidden></div>' +
                    '    <button type="submit" class="volo-reader-submit">完成验证并注册</button>' +
                    '  </form>' +
                    '</div>')
                : '') +
            '</div>';
    }

    function bindReaderAuthContainer(container, onSuccess) {
        if (!container || container.dataset.voloBound === 'true') return;
        container.dataset.voloBound = 'true';

        var captchaId = '';
        var cooldownTimer = null;
        var captchaImg = container.querySelector('[data-reader-captcha-img]');
        var captchaBtn = container.querySelector('[data-reader-captcha-refresh]');
        var sendCodeBtn = container.querySelector('[data-reader-send-code]');

        function refreshRegisterCaptcha() {
            if (!captchaImg) return;
            fetch(getConfig().servePath + '/captcha?purpose=register&_=' + Date.now(), {
                credentials: 'same-origin',
                headers: { 'Accept': 'application/json' }
            })
                .then(function (r) { return r.json(); })
                .then(function (res) {
                    if (res && res.sc && res.image) {
                        captchaId = res.captchaId || '';
                        captchaImg.src = res.image;
                    }
                })
                .catch(function () {});
        }

        function switchTab(mode) {
            container.querySelectorAll('[data-reader-tab]').forEach(function (btn) {
                btn.classList.toggle('is-active', btn.getAttribute('data-reader-tab') === mode);
            });
            container.querySelectorAll('[data-reader-pane]').forEach(function (pane) {
                pane.hidden = pane.getAttribute('data-reader-pane') !== mode;
            });
            if (mode === 'register' && !captchaId) {
                refreshRegisterCaptcha();
            }
        }

        container.querySelectorAll('[data-reader-tab]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                switchTab(btn.getAttribute('data-reader-tab'));
            });
        });

        if (captchaBtn) {
            captchaBtn.addEventListener('click', refreshRegisterCaptcha);
        }

        var regPane = container.querySelector('[data-reader-pane="register"]');
        if (regPane && !regPane.hidden) {
            refreshRegisterCaptcha();
        }

        function showAlert(form, msg, isSuccess) {
            var alertEl = form.querySelector('[data-reader-msg]');
            if (!alertEl) return;
            alertEl.textContent = msg || '';
            alertEl.hidden = !msg;
            alertEl.classList.toggle('is-success', Boolean(isSuccess));
        }

        function startCooldown(seconds) {
            if (!sendCodeBtn) return;
            var left = Math.max(1, Number(seconds) || 60);
            clearInterval(cooldownTimer);
            sendCodeBtn.disabled = true;
            sendCodeBtn.textContent = left + 's 后重发';
            cooldownTimer = setInterval(function () {
                left--;
                if (left <= 0) {
                    clearInterval(cooldownTimer);
                    sendCodeBtn.disabled = false;
                    sendCodeBtn.textContent = '重新获取';
                } else {
                    sendCodeBtn.disabled = true;
                    sendCodeBtn.textContent = left + 's 后重发';
                }
            }, 1000);
        }

        var loginForm = container.querySelector('[data-reader-login-form]');
        if (loginForm) {
            loginForm.addEventListener('submit', function (e) {
                e.preventDefault();
                var submitBtn = loginForm.querySelector('button[type="submit"]');
                var account = (loginForm.elements.account && loginForm.elements.account.value || '').trim();
                var password = loginForm.elements.password && loginForm.elements.password.value || '';
                showAlert(loginForm, '');
                if (submitBtn) submitBtn.disabled = true;
                fetch(getConfig().servePath + '/api/reader/login', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json;charset=UTF-8',
                        'X-Requested-With': 'XMLHttpRequest'
                    },
                    body: JSON.stringify({ account: account, password: password })
                })
                    .then(function (r) { return r.json(); })
                    .then(function (res) {
                        if (!res || !res.sc) {
                            showAlert(loginForm, (res && res.msg) || '登录失败，请检查账号或密码。', false);
                            return;
                        }
                        showAlert(loginForm, '登录成功，正在刷新页面...', true);
                        onSuccess();
                    })
                    .catch(function () {
                        showAlert(loginForm, '网络请求失败，请稍后重试。', false);
                    })
                    .finally(function () {
                        if (submitBtn) submitBtn.disabled = false;
                    });
            });
        }

        var regForm = container.querySelector('[data-reader-register-form]');
        if (regForm) {
            if (sendCodeBtn) {
                sendCodeBtn.addEventListener('click', function () {
                    var email = (regForm.elements.email && regForm.elements.email.value || '').trim();
                    var userName = (regForm.elements.userName && regForm.elements.userName.value || '').trim();
                    var captchaAnswer = (regForm.elements.captchaAnswer && regForm.elements.captchaAnswer.value || '').trim();
                    if (!email) {
                        showAlert(regForm, '请先填写常用邮箱地址。', false);
                        regForm.elements.email.focus();
                        return;
                    }
                    if (!captchaAnswer) {
                        showAlert(regForm, '请先输入图形验证码字符。', false);
                        regForm.elements.captchaAnswer.focus();
                        return;
                    }
                    sendCodeBtn.disabled = true;
                    showAlert(regForm, '');
                    fetch(getConfig().servePath + '/api/reader/send-code', {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: {
                            'Content-Type': 'application/json;charset=UTF-8',
                            'X-Requested-With': 'XMLHttpRequest'
                        },
                        body: JSON.stringify({
                            email: email,
                            userName: userName,
                            captchaId: captchaId,
                            captchaAnswer: captchaAnswer
                        })
                    })
                        .then(function (r) { return r.json(); })
                        .then(function (res) {
                            if (!res || !res.sc) {
                                sendCodeBtn.disabled = false;
                                if (res && res.retryAfter) {
                                    startCooldown(res.retryAfter);
                                }
                                refreshRegisterCaptcha();
                                showAlert(regForm, (res && res.msg) || '发送验证码失败。', false);
                                return;
                            }
                            showAlert(regForm, res.msg || '6 位数字验证码已发送至邮箱。', true);
                            startCooldown(res.cooldown || 60);
                        })
                        .catch(function () {
                            sendCodeBtn.disabled = false;
                            showAlert(regForm, '发送失败，请检查网络连接。', false);
                        });
                });
            }

            regForm.addEventListener('submit', function (e) {
                e.preventDefault();
                var submitBtn = regForm.querySelector('button[type="submit"]');
                var payload = {
                    userName: (regForm.elements.userName && regForm.elements.userName.value || '').trim(),
                    userURL: (regForm.elements.userURL && regForm.elements.userURL.value || '').trim(),
                    email: (regForm.elements.email && regForm.elements.email.value || '').trim(),
                    emailCode: (regForm.elements.emailCode && regForm.elements.emailCode.value || '').trim(),
                    password: regForm.elements.password && regForm.elements.password.value || ''
                };
                showAlert(regForm, '');
                if (submitBtn) submitBtn.disabled = true;
                fetch(getConfig().servePath + '/api/reader/register', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json;charset=UTF-8',
                        'X-Requested-With': 'XMLHttpRequest'
                    },
                    body: JSON.stringify(payload)
                })
                    .then(function (r) { return r.json(); })
                    .then(function (res) {
                        if (!res || !res.sc) {
                            showAlert(regForm, (res && res.msg) || '注册未完成，请核对信息。', false);
                            return;
                        }
                        showAlert(regForm, '注册成功，已自动登录！', true);
                        onSuccess();
                    })
                    .catch(function () {
                        showAlert(regForm, '网络请求失败，请稍后重试。', false);
                    })
                    .finally(function () {
                        if (submitBtn) submitBtn.disabled = false;
                    });
            });
        }

        container._switchTab = switchTab;
    }

    function openReaderAuthModal(mode) {
        var cfg = getConfig();
        var targetMode = (mode === 'register' && cfg.allowRegister) ? 'register' : 'login';
        if (!authModal || !document.body || !document.body.contains(authModal)) {
            authModal = document.createElement('div');
            authModal.className = 'volo-modal-backdrop volo-auth-backdrop';
            authModal.hidden = true;
            authModal.setAttribute('role', 'dialog');
            authModal.setAttribute('aria-modal', 'true');
            authModal.innerHTML = buildReaderAuthCardHtml(cfg.allowRegister, targetMode);
            document.body.appendChild(authModal);
            authModal.addEventListener('click', function (e) {
                if (e.target === authModal || e.target.closest('[data-volo-auth-close]')) {
                    authModal.hidden = true;
                }
            });
            bindReaderAuthContainer(authModal.querySelector('[data-volo-reader-card]'), function () {
                setTimeout(function () { window.location.reload(); }, 350);
            });
        }
        var card = authModal.querySelector('[data-volo-reader-card]');
        if (card && typeof card._switchTab === 'function') {
            card._switchTab(targetMode);
        }
        authModal.hidden = false;
    }

    function logoutReader() {
        fetch(getConfig().servePath + '/api/reader/logout', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                'Content-Type': 'application/json;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: '{}'
        }).finally(function () {
            window.location.reload();
        });
    }

    window.VoloReaderAuth = {
        open: openReaderAuthModal,
        logout: logoutReader
    };

    // =========================================================================
    // 3. Partial Hidden Content Password Unlock (.volo-lock-card)
    // =========================================================================
    function unlockPasswordBlock(card) {
        if (!card) return;
        var articleId = card.getAttribute('data-article-id') || '';
        var blockIndex = parseInt(card.getAttribute('data-block-index') || '0', 10);
        var input = card.querySelector('.volo-lock-input');
        var btn = card.querySelector('[data-volo-unlock-submit]');
        var msgEl = card.querySelector('.volo-lock-card__msg');
        var password = input ? input.value : '';
        if (!password) {
            if (msgEl) msgEl.textContent = '请输入访问口令。';
            if (input) input.focus();
            return;
        }
        if (btn) btn.disabled = true;
        if (msgEl) msgEl.textContent = '';

        var previewToken = '';
        try {
            previewToken = new URLSearchParams(window.location.search || '').get('preview') || '';
        } catch (ignored) {}

        fetch(getConfig().servePath + '/article/unlock-block', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                'Content-Type': 'application/json;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: JSON.stringify({
                articleId: articleId,
                blockIndex: blockIndex,
                password: password,
                preview: previewToken
            })
        })
            .then(function (r) { return r.json(); })
            .then(function (res) {
                if (!res || !res.sc || !res.html) {
                    if (msgEl) msgEl.textContent = (res && res.msg) || '口令不正确，请重试。';
                    return;
                }
                var temp = document.createElement('div');
                temp.innerHTML = res.html;
                var unlockedNode = temp.firstElementChild || temp;
                card.replaceWith(unlockedNode);
                if (window.Util && typeof window.Util.parseMarkdown === 'function') {
                    try { window.Util.parseMarkdown(); } catch (ignored) {}
                }
            })
            .catch(function () {
                if (msgEl) msgEl.textContent = '请求失败，请检查网络后重试。';
            })
            .finally(function () {
                if (btn) btn.disabled = false;
            });
    }

    // =========================================================================
    // 4. Podcast Audio Speed & Timestamp Jump (.volo-audio-player)
    // =========================================================================
    var SPEED_STEPS = [1, 1.25, 1.5, 2, 0.75];

    function bindAudioPlayers() {
        var players = document.querySelectorAll('[data-volo-audio-player]');
        if (!players.length) return;
        var primaryAudio = players[0].querySelector('audio');

        players.forEach(function (box) {
            if (box.dataset.voloBound === 'true') return;
            box.dataset.voloBound = 'true';
            var audio = box.querySelector('audio');
            var speedBtn = box.querySelector('[data-volo-audio-speed]');
            if (audio && speedBtn) {
                speedBtn.addEventListener('click', function () {
                    var cur = Number(audio.playbackRate || 1);
                    var idx = SPEED_STEPS.indexOf(cur);
                    var next = SPEED_STEPS[(idx + 1) % SPEED_STEPS.length];
                    audio.playbackRate = next;
                    speedBtn.textContent = next.toFixed(next % 1 === 0 ? 1 : 2).replace(/0$/, '') + 'x';
                });
            }
        });

        if (primaryAudio) {
            var contentBoxes = document.querySelectorAll('.vditor-reset, .article__content, .entry-content, .post-body, .post__content');
            contentBoxes.forEach(function (container) {
                if (container.dataset.voloAudioLinked === 'true') return;
                container.dataset.voloAudioLinked = 'true';
                container.querySelectorAll('code, a[href^="#t="]').forEach(function (el) {
                    var txt = (el.textContent || '').trim();
                    var m = /^\[?(\d{1,2}):(\d{2})(?::(\d{2}))?\]?$/.exec(txt);
                    if (m) {
                        var secs = m[3]
                            ? (parseInt(m[1], 10) * 3600 + parseInt(m[2], 10) * 60 + parseInt(m[3], 10))
                            : (parseInt(m[1], 10) * 60 + parseInt(m[2], 10));
                        el.setAttribute('data-volo-audio-seek', String(secs));
                        el.classList.add('volo-audio-timestamp');
                        el.title = '点击跳转音频至 ' + txt;
                    }
                });
            });
        }
    }

    // =========================================================================
    // 5. Embedded Bookshelf & Split-Screen / Fullscreen Reader (.volo-bookshelf)
    // =========================================================================
    function progressKey(bookId) {
        return 'volo.book.progress.' + bookId;
    }

    function readProgress(bookId) {
        try {
            var raw = localStorage.getItem(progressKey(bookId));
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    }

    function writeProgress(bookId, url, title) {
        try {
            localStorage.setItem(progressKey(bookId), JSON.stringify({
                url: url,
                title: title,
                ts: Date.now()
            }));
        } catch (e) {}
    }

    function enterReaderFullscreen(readerEl) {
        if (!readerEl || readerEl.classList.contains('is-fullscreen')) return;
        if (readerEl.parentNode && readerEl.parentNode !== document.body && document.body) {
            var anchor = document.createComment('volo-reader-anchor');
            readerEl.parentNode.insertBefore(anchor, readerEl);
            readerEl._voloAnchor = anchor;
            document.body.appendChild(readerEl);
        }
        readerEl.classList.add('is-fullscreen');
        document.documentElement.classList.add('volo-reader-lock-scroll');
    }

    function exitReaderFullscreen(readerEl) {
        if (!readerEl) return;
        readerEl.classList.remove('is-fullscreen');
        document.documentElement.classList.remove('volo-reader-lock-scroll');
        if (readerEl._voloAnchor && readerEl._voloAnchor.parentNode) {
            readerEl._voloAnchor.parentNode.insertBefore(readerEl, readerEl._voloAnchor);
            readerEl._voloAnchor.remove();
        }
        readerEl._voloAnchor = null;
    }

    function bindBookshelves() {
        document.querySelectorAll('[data-volo-bookshelf]').forEach(function (shelf) {
            if (shelf.dataset.voloBound === 'true') return;
            shelf.dataset.voloBound = 'true';

            var filterInput = shelf.querySelector('.volo-bookshelf__filter');
            var reader = shelf.querySelector('[data-volo-book-reader]');
            var readerTitle = reader && reader.querySelector('[data-volo-reader-title]');
            var readerSidebar = reader && reader.querySelector('[data-volo-reader-sidebar]');
            var readerIframe = reader && reader.querySelector('[data-volo-reader-iframe]');
            var readerExt = reader && reader.querySelector('[data-volo-reader-external]');
            var prevBtn = reader && reader.querySelector('[data-volo-reader-prev]');
            var nextBtn = reader && reader.querySelector('[data-volo-reader-next]');

            var activeLinks = [];
            var activeIdx = -1;
            var activeBookId = '';

            shelf.querySelectorAll('[data-volo-book-resume]').forEach(function (btn) {
                var bid = btn.getAttribute('data-volo-book-resume');
                var saved = readProgress(bid);
                if (saved && isSafeChapterUrl(saved.url)) {
                    btn.hidden = false;
                    btn.textContent = '继续阅读：' + (saved.title || '上次章节');
                    btn.addEventListener('click', function () {
                        openChapterInReader(bid, saved.url, saved.title || '');
                    });
                }
            });

            if (filterInput) {
                filterInput.addEventListener('input', function () {
                    var q = filterInput.value.trim().toLowerCase();
                    shelf.querySelectorAll('.volo-book-card').forEach(function (card) {
                        var cardTitle = (card.getAttribute('data-book-title') || '').toLowerCase();
                        var items = card.querySelectorAll('.volo-book-toc__item');
                        if (!q) {
                            card.hidden = false;
                            items.forEach(function (li) { li.hidden = false; });
                            return;
                        }
                        var anyChapterMatch = false;
                        items.forEach(function (li) {
                            var text = (li.textContent || '').toLowerCase();
                            var matched = text.indexOf(q) !== -1;
                            li.hidden = !matched;
                            if (matched) anyChapterMatch = true;
                        });
                        var showCard = cardTitle.indexOf(q) !== -1 || anyChapterMatch;
                        card.hidden = !showCard;
                        if (anyChapterMatch) {
                            var details = card.querySelector('.volo-book-card__catalog');
                            if (details) details.open = true;
                        }
                    });
                });
            }

            function openChapterInReader(bookId, url, chapterTitle) {
                if (!reader || !readerIframe || !isSafeChapterUrl(url)) return;
                var escapedId = (window.CSS && typeof window.CSS.escape === 'function') ? window.CSS.escape(bookId) : String(bookId).replace(/["\\]/g, '\\$&');
                var card = shelf.querySelector('.volo-book-card[data-book-id="' + escapedId + '"]');
                var bookName = card ? (card.getAttribute('data-book-title') || '') : '';
                if (activeBookId !== bookId && readerSidebar) {
                    activeBookId = bookId;
                    var sourceToc = card && card.querySelector('.volo-book-toc');
                    readerSidebar.innerHTML = sourceToc ? sourceToc.outerHTML : '';
                }
                activeLinks = readerSidebar
                    ? Array.prototype.slice.call(readerSidebar.querySelectorAll('.volo-book-toc__link'))
                    : [];
                activeIdx = -1;
                activeLinks.forEach(function (a, i) {
                    var match = a.getAttribute('data-volo-chapter-url') === url;
                    a.classList.toggle('is-active', match);
                    if (match) activeIdx = i;
                });

                reader.hidden = false;
                if ((window.innerWidth || document.documentElement.clientWidth || 1024) <= 640) {
                    reader.classList.add('is-sidebar-collapsed');
                }
                readerIframe.src = url;
                if (readerTitle) {
                    readerTitle.textContent = (bookName ? bookName + ' · ' : '') + (chapterTitle || '章节阅读');
                }
                if (readerExt) {
                    readerExt.href = url;
                }
                if (prevBtn) prevBtn.disabled = activeIdx <= 0;
                if (nextBtn) nextBtn.disabled = activeIdx < 0 || activeIdx >= activeLinks.length - 1;

                writeProgress(bookId, url, chapterTitle);
                var resumeBtn = card && card.querySelector('[data-volo-book-resume]');
                if (resumeBtn) {
                    resumeBtn.hidden = false;
                    resumeBtn.textContent = '继续阅读：' + (chapterTitle || '上次章节');
                }
                if (!reader.classList.contains('is-fullscreen')) {
                    reader.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                }
            }

            function handleChapterLinkClick(e) {
                var chapterTrigger = e.target.closest('[data-volo-chapter-url]');
                if (!chapterTrigger) return false;
                var embedMode = chapterTrigger.getAttribute('data-volo-embed-mode') || 'iframe';
                if (embedMode === 'link') {
                    chapterTrigger.setAttribute('target', '_blank');
                    chapterTrigger.setAttribute('rel', 'noopener noreferrer');
                    return true;
                }
                e.preventDefault();
                openChapterInReader(
                    chapterTrigger.getAttribute('data-volo-book-id') || activeBookId,
                    chapterTrigger.getAttribute('data-volo-chapter-url'),
                    chapterTrigger.getAttribute('data-volo-chapter-title') || chapterTrigger.textContent.trim()
                );
                return true;
            }

            shelf.addEventListener('click', function (e) {
                if (reader && reader.contains(e.target)) return;
                handleChapterLinkClick(e);
            });

            if (reader) {
                reader.addEventListener('click', function (e) {
                    if (handleChapterLinkClick(e)) return;
                    if (e.target.closest('[data-volo-reader-toggle-toc]')) {
                        reader.classList.toggle('is-sidebar-collapsed');
                    } else if (e.target.closest('[data-volo-reader-fullscreen]')) {
                        if (reader.classList.contains('is-fullscreen')) {
                            exitReaderFullscreen(reader);
                        } else {
                            enterReaderFullscreen(reader);
                        }
                    } else if (e.target.closest('[data-volo-reader-close]')) {
                        exitReaderFullscreen(reader);
                        reader.hidden = true;
                        if (readerIframe) readerIframe.removeAttribute('src');
                    } else if (e.target.closest('[data-volo-reader-prev]')) {
                        if (activeIdx > 0 && activeLinks[activeIdx - 1]) {
                            activeLinks[activeIdx - 1].click();
                        }
                    } else if (e.target.closest('[data-volo-reader-next]')) {
                        if (activeIdx >= 0 && activeIdx < activeLinks.length - 1 && activeLinks[activeIdx + 1]) {
                            activeLinks[activeIdx + 1].click();
                        }
                    }
                });
            }
        });
    }

    // =========================================================================
    // 6. Adaptive Quick Dock (Touch/Mobile Search & Reader Status Fallback)
    // =========================================================================
    var quickDock = null;

    function hasVisibleControl(selector, excludeRoot) {
        var nodes = document.querySelectorAll(selector);
        var vw = window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0;
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (excludeRoot && excludeRoot.contains(el)) continue;
            var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
            if (!rect || rect.width < 4 || rect.height < 4) continue;
            if (vw > 0 && (rect.right <= 2 || rect.left >= vw - 2)) continue;
            var hiddenByAncestor = false;
            for (var p = el; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
                var style = window.getComputedStyle ? window.getComputedStyle(p) : null;
                if (!style) continue;
                if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0
                    || (style.maxHeight === '0px' && style.overflow !== 'visible')) {
                    hiddenByAncestor = true;
                    break;
                }
            }
            if (hiddenByAncestor) continue;
            return true;
        }
        return false;
    }

    function syncQuickDock() {
        if (!document.body || isConsolePage() || document.querySelector('[data-volo-reader-page]')) {
            if (quickDock) quickDock.hidden = true;
            return;
        }
        var cfg = getConfig();
        if (!quickDock || !document.body.contains(quickDock)) {
            quickDock = document.createElement('div');
            quickDock.className = 'volo-quick-dock';
            quickDock.setAttribute('aria-label', '快捷工具');
            document.body.appendChild(quickDock);
        }

        var needSearch = cfg.enableInstantSearch && !hasVisibleControl('[data-volo-search-trigger], a[href$="/search"], form[action$="/search"] input', quickDock);
        var needReaderLogout = cfg.isLoggedIn && !cfg.isAdminLoggedIn && !hasVisibleControl('[data-volo-reader-logout]', quickDock);
        var needReaderLogin = !cfg.isLoggedIn && (cfg.allowRegister || cfg.commentAccessMode === 'registered') && !hasVisibleControl('[data-volo-auth-trigger]', quickDock);

        var html = '';
        if (needSearch) {
            html += '<button type="button" class="volo-quick-pill" data-volo-search-trigger aria-label="站内搜索" title="站内即时搜索 (Ctrl+K)">' +
                '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>' +
                '<span>搜索</span></button>';
        }
        if (needReaderLogout) {
            var readerLabel = cfg.userName ? escapeHtml(cfg.userName) : '已登录读者';
            html += '<button type="button" class="volo-quick-pill volo-quick-pill--reader" data-volo-reader-logout title="退出读者账号">' +
                '<span>' + readerLabel + ' · 退出</span></button>';
        } else if (needReaderLogin) {
            html += '<button type="button" class="volo-quick-pill volo-quick-pill--reader" data-volo-auth-trigger="login" title="读者登录 / 注册">' +
                '<span>读者登录</span></button>';
        }

        if (!html) {
            quickDock.hidden = true;
            quickDock.innerHTML = '';
        } else {
            quickDock.innerHTML = html;
            quickDock.hidden = false;
        }
    }

    // =========================================================================
    // Global Event Delegation & Lifecycle Hook
    // =========================================================================
    document.addEventListener('keydown', function (e) {
        if (isConsolePage()) return;
        if ((e.ctrlKey || e.metaKey) && String(e.key).toLowerCase() === 'k') {
            if (!getConfig().enableInstantSearch) return;
            e.preventDefault();
            if (searchModal && !searchModal.hidden) {
                closeSearch();
            } else {
                openSearch();
            }
            return;
        }
        if (e.key === 'Escape') {
            if (searchModal && !searchModal.hidden) {
                e.preventDefault();
                closeSearch();
                return;
            }
            if (authModal && !authModal.hidden) {
                e.preventDefault();
                authModal.hidden = true;
                return;
            }
            var fsReader = document.querySelector('.volo-book-reader.is-fullscreen');
            if (fsReader) {
                e.preventDefault();
                exitReaderFullscreen(fsReader);
            }
            return;
        }
        if (e.key === 'Enter' && e.target && e.target.classList && e.target.classList.contains('volo-lock-input')) {
            e.preventDefault();
            unlockPasswordBlock(e.target.closest('.volo-lock-card'));
        }
    });

    document.addEventListener('click', function (e) {
        if (isConsolePage()) return;
        var searchTrig = e.target.closest('[data-volo-search-trigger]');
        if (searchTrig) {
            e.preventDefault();
            openSearch();
            return;
        }
        var logoutTrig = e.target.closest('[data-volo-reader-logout]');
        if (logoutTrig) {
            e.preventDefault();
            logoutReader();
            return;
        }
        var authTrig = e.target.closest('[data-volo-auth-trigger]');
        if (authTrig) {
            e.preventDefault();
            openReaderAuthModal(authTrig.getAttribute('data-volo-auth-trigger'));
            return;
        }
        if (!e.defaultPrevented && e.button === 0 && !e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey && getConfig().enableInstantSearch) {
            var searchAnchor = e.target.closest('a[href]');
            if (searchAnchor && !searchAnchor.hasAttribute('download') && !searchAnchor.hasAttribute('data-no-pjax')) {
                try {
                    var dest = new URL(searchAnchor.href, window.location.href);
                    if (dest.origin === window.location.origin && /\/search\/?$/.test(dest.pathname) && !dest.searchParams.has('keyword')) {
                        e.preventDefault();
                        openSearch();
                        return;
                    }
                } catch (ignored) {}
            }
        }
        var unlockBtn = e.target.closest('[data-volo-unlock-submit]');
        if (unlockBtn) {
            e.preventDefault();
            unlockPasswordBlock(unlockBtn.closest('.volo-lock-card'));
            return;
        }
        var seekEl = e.target.closest('[data-volo-audio-seek]');
        if (seekEl) {
            var playerBox = document.querySelector('[data-volo-audio-player] audio');
            if (playerBox) {
                e.preventDefault();
                playerBox.currentTime = Number(seekEl.getAttribute('data-volo-audio-seek') || 0);
                playerBox.play().catch(function () {});
                playerBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }
    });

    function refreshPageEnhancements() {
        document.documentElement.classList.remove('volo-reader-lock-scroll');
        if (isConsolePage()) return;
        var standaloneAuth = document.querySelector('[data-volo-reader-page]');
        if (standaloneAuth) {
            bindReaderAuthContainer(standaloneAuth, function () {
                var redirect = standaloneAuth.getAttribute('data-redirect') || '/';
                var base = getConfig().servePath;
                setTimeout(function () {
                    window.location.href = redirect.startsWith('/') ? (base + redirect) : base + '/';
                }, 300);
            });
        }
        bindAudioPlayers();
        bindBookshelves();
        syncQuickDock();
    }

    var dockResizeTimer = null;
    window.addEventListener('resize', function () {
        clearTimeout(dockResizeTimer);
        dockResizeTimer = setTimeout(syncQuickDock, 180);
    });

    window.addEventListener('bolo:before-navigate', function () {
        closeSearch();
        if (authModal) authModal.hidden = true;
        document.querySelectorAll('.volo-book-reader.is-fullscreen').forEach(exitReaderFullscreen);
        document.documentElement.classList.remove('volo-reader-lock-scroll');
    });

    window.__voloEnhancementsRefresh = refreshPageEnhancements;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', refreshPageEnhancements);
    } else {
        refreshPageEnhancements();
    }
    window.addEventListener('pjax:complete', refreshPageEnhancements);
    window.addEventListener('bolo:after-navigate', refreshPageEnhancements);
})();
