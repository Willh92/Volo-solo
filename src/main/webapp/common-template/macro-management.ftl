<#-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<#macro managementLinks itemTag="" itemClass="" linkClass="" panelIcon="" exitIcon="" loginIcon="" iconsOnly=false>
<#local entries=[]>
<#if isAdminLoggedIn!false>
  <#local entries=[{"kind":"panel", "title":"后台管理", "url":adminPanelURL, "icon":panelIcon}, {"kind":"logout", "title":"退出登录", "url":logoutURL, "icon":exitIcon}]>
<#elseif showLoginEntry!false>
  <#local entries=[{"kind":"login", "title":"管理员登录", "url":adminLoginURL, "icon":loginIcon}]>
</#if>
  <#list entries as entry>
    <#if itemTag?has_content><${itemTag} class="${itemClass?html}"></#if>
    <a href="${entry.url?html}" class="management-link<#if iconsOnly> management-link--icon</#if> ${linkClass?html}" rel="nofollow" title="${entry.title}" aria-label="${entry.title}" data-management-link="${entry.kind}"><#if entry.icon?has_content><i class="${entry.icon?html}" aria-hidden="true"></i></#if><#if !iconsOnly>${entry.title}</#if></a>
    <#if itemTag?has_content></${itemTag}></#if>
  </#list>
</#macro>
