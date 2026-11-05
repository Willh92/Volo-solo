<#--
    Volo - A lightweight, secure, and modern blogging system.
    Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
    SPDX-License-Identifier: AGPL-3.0-or-later
-->
<#include "macro-utility_page.ftl"/>
<@utilityPage title="${(authMode == 'register')?string('注册读者账号', '读者登录')}" type="reader-auth">
<section class="volo-reader-page-card" data-volo-reader-page
         data-auth-mode="${(authMode!'login')?html}"
         data-allow-register="${(allowRegister!false)?c}"
         data-redirect="${(redirectTarget!'/')?html}">
    <div class="volo-reader-card-head">
        <span class="volo-reader-kicker">READER ACCOUNT</span>
        <div class="volo-reader-tabs" role="tablist">
            <button type="button" class="volo-reader-tab<#if (authMode!'login') != 'register'> is-active</#if>" data-reader-tab="login">读者登录</button>
            <#if allowRegister!false>
            <button type="button" class="volo-reader-tab<#if (authMode!'login') == 'register'> is-active</#if>" data-reader-tab="register">邮箱注册</button>
            </#if>
        </div>
    </div>

    <div class="volo-reader-pane"<#if (authMode!'login') == 'register'> hidden</#if> data-reader-pane="login">
        <h1>欢迎回来</h1>
        <p class="volo-reader-desc">使用已注册的邮箱或昵称登录，即可参与评论互动并解锁读者专属内容。</p>
        <form class="volo-reader-form" data-reader-login-form>
            <label>邮箱或昵称
                <input type="text" name="account" autocomplete="username" placeholder="请输入注册邮箱或昵称" required/>
            </label>
            <label>登录密码
                <input type="password" name="password" autocomplete="current-password" placeholder="请输入密码" required/>
            </label>
            <div class="volo-reader-alert" data-reader-msg hidden></div>
            <button type="submit" class="volo-reader-submit">登录读者账号</button>
        </form>
    </div>

    <#if allowRegister!false>
    <div class="volo-reader-pane"<#if (authMode!'login') != 'register'> hidden</#if> data-reader-pane="register">
        <h1>注册读者账号</h1>
        <p class="volo-reader-desc">完成 6 位邮箱验证码校验即可创建读者身份，仅用于站内评论与读者可见内容访问。</p>
        <form class="volo-reader-form" data-reader-register-form>
            <label>读者昵称
                <input type="text" name="userName" maxlength="32" autocomplete="nickname" placeholder="2-32 位中英文或数字昵称" required/>
            </label>
            <label>个人主页（选填）
                <input type="url" name="userURL" maxlength="255" autocomplete="url" placeholder="https://example.com"/>
            </label>
            <label>常用邮箱
                <input type="email" name="email" maxlength="254" autocomplete="email" placeholder="name@example.com" required/>
            </label>
            <div class="volo-reader-captcha-row">
                <label class="volo-reader-captcha-field">图形验证码
                    <input type="text" name="captchaAnswer" maxlength="5" autocomplete="off" placeholder="输入右侧字符"/>
                </label>
                <button type="button" class="volo-reader-captcha-img-btn" data-reader-captcha-refresh title="点击刷新验证码">
                    <img alt="图形验证码" data-reader-captcha-img/>
                </button>
            </div>
            <div class="volo-reader-code-row">
                <label class="volo-reader-code-field">邮箱验证码
                    <input type="text" name="emailCode" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位数字验证码" required/>
                </label>
                <button type="button" class="volo-reader-send-code" data-reader-send-code>获取邮箱验证码</button>
            </div>
            <label>设置密码
                <input type="password" name="password" autocomplete="new-password" placeholder="8-64 位，包含字母与数字" required/>
            </label>
            <div class="volo-reader-alert" data-reader-msg hidden></div>
            <button type="submit" class="volo-reader-submit">完成验证并注册</button>
        </form>
    </div>
    <#else>
    <p class="volo-reader-closed-tip">站点当前暂未开放新读者自助注册，已有账号可直接登录。</p>
    </#if>
</section>
</@utilityPage>
