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
<#include "../common-template/macro-utility_page.ftl">
<@utilityPage title=articleViewPwdLabel type="password">
<section class="utility-password-card" aria-labelledby="articlePasswordTitle">
    <div class="utility-password-symbol" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/></svg></div>
    <p class="utility-eyebrow">受保护的文章</p>
    <h1 id="articlePasswordTitle">${articleTitle?html}</h1>
    <p class="utility-error-hint">这篇文章设置了访问密码，输入后即可继续阅读。</p>
    <form method="POST" action="${servePath}/console/article-pwd" class="utility-password-form" autocomplete="off">
        <label for="pwdTyped">访问密码</label>
        <input type="password" id="pwdTyped" name="pwdTyped" autocomplete="off" maxlength="1024" required aria-describedby="articlePasswordMessage"/>
        <input type="hidden" name="articleId" value="${articleId?html}" autocomplete="off"/>
        <p id="articlePasswordMessage" class="utility-password-error" role="alert"><#if msg??>${msg?html}</#if></p>
        <button id="confirm" type="submit" class="utility-button">继续阅读 <span aria-hidden="true">→</span></button>
    </form>
</section>
<script>
window.addEventListener('pageshow', function (e) {
    var input = document.getElementById('pwdTyped');
    if (!input) return;
    var nav = window.performance && window.performance.getEntriesByType && window.performance.getEntriesByType('navigation')[0];
    if (e.persisted || (nav && nav.type === 'back_forward')) {
        input.value = '';
    }
});
</script>
</@utilityPage>
