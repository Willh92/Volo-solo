<#-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<#macro managementLinks itemTag="" itemClass="" linkClass="" panelIcon="" exitIcon="" loginIcon="" iconsOnly=false>
<#local entries=[]>
<#if isAdminLoggedIn!false>
  <#local entries=[{"kind":"panel", "title":"后台管理", "url":adminPanelURL, "icon":panelIcon, "attr":""}, {"kind":"logout", "title":"退出登录", "url":logoutURL, "icon":exitIcon, "attr":""}]>
<#elseif isLoggedIn!false>
  <#local readerExitTitle = "退出读者（" + (userName!'已登录') + "）">
  <#local entries=[{"kind":"reader-logout", "title":readerExitTitle, "url":logoutURL, "icon":exitIcon, "attr":"data-volo-reader-logout data-no-pjax"}]>
<#else>
  <#if (allowRegister!false) || (commentAccessMode!'all') == 'registered'>
    <#local entries = entries + [{"kind":"reader-login", "title":"读者登录", "url":servePath + "/user/login", "icon":loginIcon, "attr":"data-volo-auth-trigger=\"login\""}]>
  </#if>
  <#if showLoginEntry!false>
    <#local entries = entries + [{"kind":"login", "title":"管理员登录", "url":adminLoginURL, "icon":loginIcon, "attr":""}]>
  </#if>
</#if>
  <#list entries as entry>
    <#if itemTag?has_content><${itemTag} class="${itemClass?html}"></#if>
    <a href="${entry.url?html}" class="management-link<#if iconsOnly> management-link--icon</#if> ${linkClass?html}" rel="nofollow" title="${entry.title?html}" aria-label="${entry.title?html}" data-management-link="${entry.kind}"<#if (entry.attr!'')?has_content> ${entry.attr}</#if>><#if entry.icon?has_content><i class="${entry.icon?html}" aria-hidden="true"></i></#if><#if !iconsOnly>${entry.title?html}</#if></a>
    <#if itemTag?has_content></${itemTag}></#if>
  </#list>
</#macro>
