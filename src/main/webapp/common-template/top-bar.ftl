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
<style type="text/css">
    #top {
        background-color: #FFF;
        background-image: linear-gradient(#FFFFFF,#E5E5E5);
        background-image: -ms-linear-gradient(#FFFFFF,#E5E5E5);
        background-image: -o-linear-gradient(#FFFFFF,#E5E5E5);
        background-image: -webkit-linear-gradient(#FFFFFF,#E5E5E5);
        filter: progid:DXImageTransform.Microsoft.gradient(GradientType=0, startColorstr='#FFFFFF', endColorstr='#E5E5E5');
        border-bottom: 1px solid #E5E5E5;
        line-height: 26px;
        display: none;
    }

    #top a, #top span span {
        border-right: 1px solid #D9D9D9;
        color: #4C4C4C;
        float: left;
        line-height: 14px;
        margin: 6px 0;
        padding: 0 6px;
        text-decoration: none;
        text-shadow: 0 -1px 0 #FFFFFF;
        font-weight: normal;
    }

    #top a:hover, #top a.hover {
        background-color: #EEF2FA;
        border-left-color: #707070;
        border-radius: 0 13px 13px 0;
        margin: 0px;
        line-height: 26px;
    }

    #showTop {
        background-image: url("${staticServePath}/images/arrow-right.png");
        cursor: pointer;
        height: 26px;
        right: 0;
        position: absolute;
        top: 0;
        width: 26px;
        border-radius: 0 0 0 15px;
        z-index: 10;
    }

    #showTop:hover {
        background-image: url("${staticServePath}/images/arrow-right.gif");
    }

    #top #hideTop {
        background-image: url("${staticServePath}/images/arrow-left.png");
        height: 26px;
        margin: 0;
        padding: 0;
        width: 26px;
        border: 0;
    }

    #top #hideTop:hover {
        background-image: url("${staticServePath}/images/arrow-left.gif");
        border-radius: 0;
    }
</style>
<div id="showTop"></div>
<div id="top">
    <a href="https://github.com/Willh92/Volo-solo" target="_blank" rel="noopener noreferrer" class="hover">
        Volo
    </a>
    <span class="left">&nbsp;${onlineVisitor1Label}${onlineVisitorCnt}</span>
    <span class="right" id="admin" data-login="${isLoggedIn?string}">
        <#if isLoggedIn!false>
            <#if isAdmin!false>
            <a href="${servePath}/admin-index.do#main" title="${adminLabel!''}">${adminLabel!'管理'}</a>
            <a href="${(logoutURL!'/logout')?html}" data-no-pjax title="${logoutLabel!''}">${logoutLabel!'退出'}</a>
            <#else>
            <span>读者：${(userName!'')?html}</span>
            <a href="${(logoutURL!'/logout')?html}" data-volo-reader-logout data-no-pjax title="${logoutLabel!'退出'}">${logoutLabel!'退出'}</a>
            </#if>
        <#else>
            <#if (allowRegister!false) || (commentAccessMode!'all') == 'registered'>
            <a href="${servePath}/user/login" data-volo-auth-trigger="login" title="读者登录">读者登录</a>
            </#if>
            <#if showLoginEntry!false>
            <a href="${(loginURL!'/start')?html}" title="${startToUseLabel!'登录'}">${startToUseLabel!'登录'}</a>
            </#if>
        </#if>
        <#if isMobileRequest>
            <a href="javascript:void(0)" onclick="Util.switchMobile('mobile');" title="${mobileLabel}">${mobileLabel}</a>
        </#if>
        <a href="javascript:void(0)" id="hideTop"></a>
    </span>
    <div class="clear"></div>
</div>
