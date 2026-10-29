<#--

    Bolo - A stable and beautiful blogging system based in Solo.
    Copyright (c) 2020-present, https://github.com/bolo-blog

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

-->
<!DOCTYPE html>
<html lang="zh-CN" data-bolo-admin-url="${(adminPanelPath!'/admin-index.do')?html}" data-bolo-login-url="${(adminLoginPath!'/start')?html}">
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <meta name="robots" content="noindex,nofollow"/>
    <meta name="theme-color" content="#173f3b"/>
    <title>登录管理后台 · Volo</title>
    <meta name="bolo-accent" content="${appearanceAccent!'forest'}"/>
    <link rel="icon" href="${faviconURL?html}"/>
    <link rel="stylesheet" href="${staticServePath}/scss/account.css?${staticResourceVersion}"/>
    <script data-bolo-navigation src="${staticServePath}/js/auth-navigation.js?${staticResourceVersion}"></script>
    <script data-bolo-navigation src="${staticServePath}/js/appearance.js?${staticResourceVersion}"></script>
    <link rel="stylesheet" href="${staticServePath}/scss/appearance.css?${staticResourceVersion}"/>
</head>
<body class="login-page auth-page">
    <header class="auth-header">
        <a class="auth-brand" href="${servePath}/" aria-label="Volo首页">
            <span class="auth-brand-mark" aria-hidden="true">V.</span>
            <span>Volo <small>VOLO</small></span>
        </a>
        <div class="auth-header-actions"><div data-bolo-theme-slot></div><a class="auth-back" href="${servePath}/"><span aria-hidden="true">←</span> 返回博客</a></div>
    </header>
    <main class="auth-layout">
        <section class="auth-story" aria-label="Volo">
            <span class="auth-eyebrow">属于你的文字空间</span>
            <h1>记录日常，<br/>也记录不平凡。</h1>
            <p>把想法写下来，让每一次表达<br class="auth-desktop-break"/>都有一个温暖的归处。</p>
            <div class="auth-story-art" aria-hidden="true"><span></span><span></span><span></span></div>
            <div class="auth-story-foot"><span class="auth-dot"></span> 简单书写 · 自由表达</div>
        </section>
        <section class="auth-card" aria-labelledby="loginHeading">
            <div class="auth-card-top"><span class="auth-kicker">管理中心</span><span class="auth-version">VOLO 3.0.0</span></div>
            <h2 id="loginHeading">欢迎回来</h2>
            <p class="auth-description" id="loginDescription">登录你的博客，继续上次的创作。</p>
            <form id="loginForm" action="${servePath}/oauth/bolo/login" method="POST" data-mode="${data?html}">
                <label for="username">用户名</label>
                <div class="auth-input">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M5 21v-2a7 7 0 0 1 14 0v2"/></svg>
                    <input id="username" name="username" type="text" autocomplete="username" placeholder="输入你的用户名" required autofocus/>
                </div>
                <label for="password">密码</label>
                <div class="auth-input">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>
                    <input id="password" name="password" type="password" autocomplete="current-password" placeholder="输入你的密码" aria-describedby="status" required/>
                    <button class="auth-password-toggle" id="togglePassword" type="button" aria-label="显示密码" aria-pressed="false">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>
                    </button>
                </div>
                <div id="status" class="auth-error" role="alert" aria-live="polite" hidden></div>
                <button id="loginBtn" class="auth-submit" type="submit"><i aria-hidden="true"></i><span>登录管理后台</span><b aria-hidden="true">→</b></button>
            </form>
            <div class="auth-card-foot"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/></svg> 仅限博客管理者访问</div>
        </section>
    </main>
    <footer class="auth-footer"><span>© ${year} Volo</span><a href="https://github.com/Willh92/Volo-solo" target="_blank" rel="noopener noreferrer">开源，让表达更自由 <span aria-hidden="true">↗</span></a></footer>
    <script src="${staticServePath}/js/account-login.js?${staticResourceVersion}"></script>
</body>
</html>
