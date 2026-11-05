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
<div id="banner_wave_1">
</div>
<div id="banner_wave_2">
</div>
<figure id="centerbg" class="centerbg">
    <div class="focusinfo no-select">
        <h1 class="center-text glitch is-glitching Ubuntu-font" data-text="${blogTitle}">${blogTitle}</h1>
        <div class="header-info">
            <p>
                <#if blogSubtitle??>
                    <i class="fa fa-quote-left"></i> ${blogSubtitle} <i class="fa fa-quote-right"></i>
                <#else>
                    <i class="fa fa-quote-left"></i> Nothing here... <i class="fa fa-quote-right"></i>
                </#if>
            </p>
            <div class="top-social_v2">
                <style>
                    .pc .user__site {
                        width: 32px;
                        height: 32px;
                        float: left;
                        background-color: #f1f2f7;
                        border-radius: 50%;
                        margin-right: 10px;
                        opacity: 0.6;
                    }

                    .pc .user__site svg {
                        vertical-align:middle;
                        width: 20px;
                        height: 20px;
                    }
                </style>
                <div class="pc">
                    <#include "../../common-template/macro-user_site.ftl"/>
                    <@userSite dir=""></@userSite>
                </div>
            </div>
        </div>
    </div>
</figure>
<div id="video-container" style="">
    <video id="bgvideo" class="video" video-name="" width="auto" preload="none"></video>
    <!--<div id="video-btn" class="loadvideo videolive">
    </div>-->
    <div id="video-add">
    </div>
    <div class="video-stu">
    </div>
</div>
<div class="headertop-down faa-float animated" onclick="headertop_down()">
    <span><i class="fa fa-chevron-down" aria-hidden="true"></i></span>
</div>
</div>
<div id="page" class="site wrapper">
    <header class="site-header no-select is-homepage" role="banner">
        <div class="site-top">
            <div class="site-branding">
                <span class="site-title"><span class="logolink"><a href="${servePath}/" title="${blogTitle}"># ${blogTitle}</a></span></span>
            </div>
            <div class="lower-cantiner">
                <div class="lower">
                    <div id="show-nav" class="showNav mobile-fit">
                        <div class="line line1">
                        </div>
                        <div class="line line2">
                        </div>
                        <div class="line line3">
                        </div>
                    </div>
                    <nav class="mobile-fit-control hide">
                        <ul id="menu-new" class="menu">
                            <li><a href="${servePath}/"><span class="faa-parent animated-hover"><i
                                                class="fa fa-home faa-horizontal" aria-hidden="true"></i> 首页</span></a></li>
                            <li><a href="${servePath}/links.html"><span class="faa-parent animated-hover"><i
                                                class="fa fa-link faa-horizontal" aria-hidden="true"></i> 友链</span></a></li>
                            <li><a href="${servePath}/follows.html"><span class="faa-parent animated-hover"><i
                                                class="fa fa-link faa-horizontal" aria-hidden="true"></i> 关注</span></a></li>
                            <li><a href="${servePath}/tags.html"><span class="faa-parent animated-hover"><i
                                                class="fa fa-tag faa-horizontal" aria-hidden="true"></i> 标签</span></a></li>
                            <li><a href="${servePath}/archives.html"><span class="faa-parent animated-hover"><i
                                                class="fa fa-archive faa-horizontal" aria-hidden="true"></i> 归档</span></a></li>
                            <#list pageNavigations as page>
                                <li><a href="${page.pagePermalink?html}" target="${page.pageOpenTarget?html}"><span
                                                class="faa-parent animated-hover">
                                            <#if page.pageIcon?contains("/")>
                                                <img class="icon faa-horizontal" aria-hidden="true" style="height: 16px; width: 16px; vertical-align: -2px; object-fit: contain;"
                                                     src="${page.pageIcon?html}">
                                            <#else>
                                                <i class="fa fa-${page.pageIcon?html} faa-horizontal" aria-hidden="true"></i>
                                            </#if> ${page.pageTitle?html}</span></a></li>
                            </#list>
                        </ul>
                    </nav>
                </div>
            </div>
            <div class="skin-theme-slot" data-bolo-theme-slot></div>
            <#if (isAdminLoggedIn!false) || (isLoggedIn!false) || (showLoginEntry!false) || (allowRegister!false) || (commentAccessMode!'all') == 'registered'><div class="header-user-avatar">
                <#if isAdminLoggedIn!false><a href="${adminPanelURL?html}" aria-label="后台管理"><img class="faa-shake animated-hover" src="${((adminUser.userAvatar)!'')?html}" width="30" height="30" alt=""/></a><#elseif isLoggedIn!false><a href="${logoutURL?html}" data-volo-reader-logout data-no-pjax title="退出读者（${(userName!'已登录')?html}）" aria-label="退出读者（${(userName!'已登录')?html}）"><i class="fa fa-user-circle" aria-hidden="true"></i></a><#elseif (allowRegister!false) || (commentAccessMode!'all') == 'registered'><a href="${servePath}/user/login" data-volo-auth-trigger="login" title="读者登录" aria-label="读者登录"><i class="fa fa-user-circle-o" aria-hidden="true"></i></a><#else><a href="${adminLoginURL?html}" aria-label="管理员登录"><i class="fa fa-user-circle-o" aria-hidden="true"></i></a></#if>
                <div class="header-user-menu"><div class="herder-user-name logged-in-as"><#include "../../common-template/macro-management.ftl"><@managementLinks/></div></div>
            </div></#if>

            <div class="searchbox">
                <button class="js-toggle-search sakura-search-toggle" type="button" aria-label="${searchLabel}"><i class="fa fa-search" aria-hidden="true"></i></button>
            </div>

        </div>
    </header>
    <div class="blank">
    </div>
