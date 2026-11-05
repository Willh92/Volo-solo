<#-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<div class="console-security">
<#if securitySection == 'access'>
<div data-security-page="access" class="form">
        <section class="console-entry-info">
            <h3>管理入口</h3>
            <p>访客登录入口由“前端开放设置”控制；已登录管理员保留后台和退出入口。在下方修改管理登录路径，保存后立即生效。后台面板路径保持不变。</p>
            <p>当前面板：<code>${(adminPanelPath!'/admin-index.do')?html}</code><br>当前登录页：<code id="adminLoginPathCurrent">${(adminLoginPath!'/start')?html}</code><br>2FA 验证：支持基于 TOTP 标准的动态口令保护，<a href="#security/totp">前往“2FA 验证”配置</a></p>
            <div class="console-entry-field">
                <label for="adminLoginPathSetting">管理登录路径</label>
                <input id="adminLoginPathSetting" type="text" maxlength="120" placeholder="/start" autocomplete="off" aria-describedby="adminLoginPathHelp"/>
                <p id="adminLoginPathHelp" class="console-form-help">例如 /private-login；留空使用 /start。保存后旧登录入口返回 404，请记住新的地址。</p>
            </div>
        </section>
        <div class="console-preference-field">
            <label for="riskControlEnabled">自动风控</label>
            <select id="riskControlEnabled"><option value="true">启用</option><option value="false">暂停自动风控</option></select>
            <p class="console-form-help">识别路径遍历、敏感文件和脚本扫描、高频 404、未授权或无效请求及请求洪泛。普通风控自动封禁 24 小时，到期自动解封；明确的 XSS 注入永久封禁。记录来源和触发原因。已登录管理员和内网地址不进入风控计数，可信反代使用解析后的访客 IP。</p>
            <p class="console-form-help">路径遍历立即触发；2 分钟内 3 个不同探测路径或 8 次探测、20 个不同 404 路径或 60 次 404、40 次拒绝响应或 480 次非静态请求触发临时封禁。普通缺失图片、脚本和样式不计入通用 404 规则。登录重试与评论防刷仍独立生效。</p>
        </div>
        <div class="console-preference-field">
            <label for="wafPower">${securityPowerLabel}</label>
            <select id="wafPower" aria-describedby="securityDescription">
                <option value="true" selected="">${enableLabel}</option>
                <option value="false">${disableLabel}</option>
            </select>
            <p id="securityDescription" class="console-form-help">${securityDescribeLabel}</p>
            <p class="console-form-help">${clientAddressLabel!'识别的访客 IP'}：<code>${(securityClientAddress!'—')?html}</code><br>${proxyPeerAddressLabel!'连接到 Java 的 IP'}：<code>${(securityPeerAddress!'—')?html}</code><br>${proxyAddressHelpLabel}</p>
        </div>
        <div class="console-preference-field console-rate-limit">
            <label for="wafCurrentLimitTimes">${frequencyLimitLabel}</label>
            <div class="console-rate-limit-inputs"><input id="wafCurrentLimitTimes" type="text" inputmode="numeric" aria-label="访问次数"> <span>${securitySetTimesLabel}</span> <input id="wafCurrentLimitSecond" type="text" inputmode="numeric" aria-label="时间范围（秒）"> <span>${securitySetSecondLabel}</span></div>
        </div>
        <div class="console-form-actions"><button type="button" onclick="admin.security.saveSettings()">${updateLabel}</button></div>

</div>
</#if>
<#if securitySection == 'totp'>
<div data-security-page="totp" class="form">
        <section class="console-totp" aria-labelledby="totpTitle">
            <div class="console-totp-header">
                <div>
                    <h3 id="totpTitle">2FA 双重验证</h3>
                    <p class="console-form-help">基于 RFC 6238 时间型动态口令（TOTP）标准，密钥与二维码完全在本地生成与校验，不会连接任何外部服务。兼容各类支持 TOTP 标准的 2FA 身份验证器应用与密码管理器。</p>
                </div>
                <span id="totpStatusBadge" class="console-totp-badge" data-state="unbound">读取中…</span>
            </div>
            <p id="totpSummary" class="console-totp-summary" role="status"></p>

            <div class="console-totp-toolbar">
                <button id="totpStartSetup" type="button" onclick="admin.security.startTotpSetup()" disabled>生成绑定密钥</button>
                <button id="totpRegenRecoveryBtn" type="button" class="console-btn-secondary" onclick="admin.security.openTotpAction('recovery')" hidden>重新生成应急恢复码</button>
                <button id="totpRevokeDevicesBtn" type="button" class="console-btn-secondary" onclick="admin.security.revokeTotpDevices()" hidden>撤销已信任浏览器</button>
                <button id="totpUnbindBtn" type="button" class="console-btn-danger" onclick="admin.security.openTotpAction('unbind')" hidden>解绑并关闭验证</button>
            </div>

            <form id="totpSetupPanel" class="console-totp-card" onsubmit="admin.security.confirmTotpSetup(); return false;" hidden>
                <h4 id="totpSetupHeading">扫码绑定 2FA 验证器</h4>
                <div class="console-totp-setup-grid">
                    <div class="console-totp-qr-wrap">
                        <div id="totpQrCode" class="console-totp-qr" role="img" aria-label="2FA 扫码绑定二维码"></div>
                        <small>使用身份验证器扫描上方二维码</small>
                    </div>
                    <div class="console-totp-setup-fields">
                        <p class="console-form-help">无法扫码时，可在验证器中选择“输入设置密钥”（基于时间），填入以下 Base32 密钥：</p>
                        <div class="console-totp-secret-box">
                            <code id="totpSecretText"></code>
                            <button type="button" class="console-btn-secondary" onclick="admin.security.copyTotpSecret()">复制密钥</button>
                        </div>
                        <p id="totpSetupMeta" class="console-form-help"></p>
                        <div id="totpCurrentCodeWrap" class="console-preference-field" hidden>
                            <label for="totpCurrentCode">原验证器动态口令或恢复码</label>
                            <input id="totpCurrentCode" type="text" inputmode="numeric" maxlength="16" autocomplete="off" placeholder="更换密钥需验证原设备 6 位口令或 8 位恢复码"/>
                        </div>
                        <div class="console-preference-field">
                            <label for="totpSetupCode">新验证器 6 位动态口令</label>
                            <input id="totpSetupCode" type="text" inputmode="numeric" maxlength="10" autocomplete="one-time-code" placeholder="输入验证器显示的 6 位数字"/>
                        </div>
                        <p id="totpSetupMessage" class="console-totp-feedback" role="status"></p>
                        <div class="console-totp-inline-actions">
                            <button id="totpConfirmBtn" type="submit">验证并启用 2FA</button>
                            <button type="button" class="console-btn-secondary" onclick="admin.security.cancelTotpSetup()">取消</button>
                        </div>
                    </div>
                </div>
            </form>

            <form id="totpActionPanel" class="console-totp-card" onsubmit="admin.security.submitTotpAction(); return false;" hidden>
                <h4 id="totpActionTitle">验证身份</h4>
                <p id="totpActionDesc" class="console-form-help"></p>
                <div class="console-preference-field">
                    <label for="totpActionCode">动态口令确认</label>
                    <input id="totpActionCode" type="text" inputmode="numeric" maxlength="16" autocomplete="one-time-code" placeholder="输入 6 位动态口令"/>
                </div>
                <p id="totpActionMessage" class="console-totp-feedback" role="status"></p>
                <div class="console-totp-inline-actions">
                    <button id="totpActionSubmit" type="submit">确认执行</button>
                    <button type="button" class="console-btn-secondary" onclick="admin.security.closeTotpAction()">取消</button>
                </div>
            </form>

            <div id="totpRecoveryPanel" class="console-totp-card console-totp-recovery" hidden>
                <h4>请妥善保存一次性应急恢复码</h4>
                <p class="console-form-help">验证器设备遗失或更换时可使用应急恢复码完成登录或解绑。每组恢复码仅限使用一次，离开本页后不再展示明文：</p>
                <div id="totpRecoveryList" class="console-totp-recovery-grid"></div>
                <div class="console-totp-inline-actions">
                    <button type="button" onclick="admin.security.copyRecoveryCodes()">复制全部恢复码</button>
                    <button type="button" class="console-btn-secondary" onclick="document.getElementById('totpRecoveryPanel').hidden = true">我已妥善保存</button>
                </div>
            </div>

            <form id="totpSettingsForm" class="console-totp-settings" onsubmit="admin.security.saveTotpSettings(); return false;">
                <h4>2FA 配置项</h4>
                <div class="console-smtp-grid">
                    <div class="console-preference-field">
                        <label for="totpEnabled">登录 2FA 状态</label>
                        <select id="totpEnabled" onchange="admin.security.syncTotpToggleVerify()">
                            <option value="false">停用</option>
                            <option value="true">启用 2FA 验证</option>
                        </select>
                        <p class="console-form-help">绑定密钥后默认自动启用；启用期间后台登录图形验证码自动关闭，停用或解绑后自动恢复图形验证码保护。</p>
                    </div>
                    <div class="console-preference-field">
                        <label for="totpScope">适用账号范围</label>
                        <select id="totpScope">
                            <option value="admin">仅管理员账号（推荐）</option>
                            <option value="all">全部后台登录账号（含普通作者）</option>
                        </select>
                        <p class="console-form-help">仅管理员可进入安全后台管理验证器；多人独立写作时建议保持“仅管理员账号”。</p>
                    </div>
                    <div class="console-preference-field">
                        <label for="totpIssuer">验证器显示名称（Issuer）</label>
                        <input id="totpIssuer" type="text" maxlength="64" autocomplete="off" placeholder="留空默认使用博客标题或 Volo"/>
                        <p id="totpIssuerHelp" class="console-form-help">在身份验证器列表中展示的站点标识（不含冒号），生成新绑定二维码时生效。</p>
                    </div>
                    <div class="console-preference-field">
                        <label for="totpWindowSteps">时间偏移容差</label>
                        <select id="totpWindowSteps">
                            <option value="0">严格当前窗口（0 秒）</option>
                            <option value="1">允许前后 1 个周期（±30 秒，推荐）</option>
                            <option value="2">允许前后 2 个周期（±60 秒）</option>
                        </select>
                        <p class="console-form-help">口令每 30 秒轮换且不可重复使用；允许 ±30 秒可兼容服务器与客户端之间的轻微时钟偏差。</p>
                    </div>
                    <div class="console-preference-field">
                        <label for="totpRememberDays">浏览器免二次验证时长</label>
                        <select id="totpRememberDays">
                            <option value="0">每次登录均需验证（最安全）</option>
                            <option value="7">允许信任当前浏览器 7 天</option>
                            <option value="14">允许信任当前浏览器 14 天</option>
                            <option value="30">允许信任当前浏览器 30 天</option>
                        </select>
                        <p class="console-form-help">开启后登录页可勾选免二次验证，有效期内仅跳过动态口令，仍需校验用户名与密码。</p>
                    </div>
                    <div id="totpToggleVerifyWrap" class="console-preference-field" hidden>
                        <label for="totpSettingsVerifyCode">切换状态口令确认</label>
                        <input id="totpSettingsVerifyCode" type="text" inputmode="numeric" maxlength="16" autocomplete="one-time-code" placeholder="输入 6 位动态口令（停用时也可用恢复码）"/>
                        <p class="console-form-help">启用或停用 2FA 前需校验动态口令，防止未授权修改与误锁账号。</p>
                    </div>
                </div>
                <p id="totpSettingsMessage" class="console-totp-feedback" role="status"></p>
                <div class="console-form-actions"><button id="totpSaveSettings" type="submit" disabled>保存 2FA 配置</button></div>
            </form>
        </section>
        <section class="console-passkey console-subcard console-subcard--surface" aria-labelledby="passkeyTitle" style="margin-top: 20px;">
            <div class="console-section-head">
                <div class="console-section-head__copy">
                    <h4 id="passkeyTitle">通行密钥（Passkey / WebAuthn）免密登录</h4>
                    <p>基于 FIDO2 / WebAuthn 标准绑定本机指纹、Face ID、Windows Hello 或硬件安全密钥（YubiKey）。私钥永不离开您的设备，登录后台免密码与验证码。</p>
                </div>
                <span id="passkeyStatusBadge" class="console-totp-badge" data-state="unbound">未绑定</span>
            </div>
            <div class="console-inline-bar console-inline-bar--fill">
                <input id="passkeyDeviceName" type="text" maxlength="64" autocomplete="off" placeholder="设备备注名称（可选，如 MacBook Touch ID / YubiKey 5C）"/>
                <button id="passkeyRegisterBtn" type="button" onclick="admin.security.registerPasskey(this)">注册新通行密钥</button>
            </div>
            <p id="passkeyMessage" class="console-totp-feedback" role="status"></p>
            <div class="console-simple-table-wrap">
                <table class="console-simple-table">
                    <thead>
                        <tr>
                            <th>设备名称</th>
                            <th>签名算法</th>
                            <th>绑定时间</th>
                            <th>最近使用</th>
                            <th class="is-right">操作</th>
                        </tr>
                    </thead>
                    <tbody id="passkeyListBody">
                        <tr><td colspan="5" class="console-simple-table__empty">尚未绑定任何通行密钥。</td></tr>
                    </tbody>
                </table>
            </div>
        </section>
</div>
</#if>
<#if securitySection == 'ip'>
<div data-security-page="ip" class="form">
        <section class="console-ip-bans" aria-labelledby="ipBanTitle">
            <h3 id="ipBanTitle">IP 限制</h3>
            <p class="console-form-help">支持 IPv4、IPv6 单个地址或 CIDR 网段，如 192.0.2.1、192.0.2.0/24、2001:db8::/48。封禁后返回 403。永久或按指定时长生效，到期自动解封，也可提前解封。位置仅供参考，网段显示起始地址的位置。</p>
            <p id="ipBanEditing" class="console-ip-ban-editing" role="status" hidden></p>
            <form id="ipBanForm" class="console-ip-ban-add" onsubmit="admin.security.addIpBan(); return false;">
                <label for="ipBanRange">IP / CIDR 网段<input id="ipBanRange" type="text" maxlength="64" placeholder="192.0.2.1 或 2001:db8::/48" required/></label>
                <label for="ipBanReason">备注（选填）<input id="ipBanReason" type="text" maxlength="200" placeholder="例如：持续发布广告"/></label>
                <label for="ipBanDuration">封禁时长
                <select id="ipBanDuration"><option value="keep" hidden disabled>保留原到期时间</option><option value="0" selected>永久</option><option value="86400">1 天</option><option value="259200">3 天</option><option value="604800">7 天</option><option value="2592000">30 天</option><option value="custom">自定义天数</option></select></label>
                <label class="console-duration-custom" hidden>天数<input id="ipBanCustomDays" type="number" min="1" max="3650" value="1"/></label>
                <div id="ipBanOccurrenceField" class="console-ip-ban-occurrences" hidden>
                    <label for="ipBanOccurrences">累计自动风控次数</label>
                    <div><input id="ipBanOccurrences" type="number" min="0" max="1000000" step="1" value="1"/><button type="button" onclick="document.getElementById('ipBanOccurrences').value = '0'">重置次数</button></div>
                    <p class="console-form-help">设为 0 清空累计次数，当前期限不变；到期后再触发按首次封禁处理。</p>
                </div>
                <div class="console-ip-ban-edit-actions"><button id="ipBanCancel" type="button" onclick="admin.security.cancelIpBanEdit()" hidden>取消编辑</button><button id="ipBanSubmit" type="submit">添加封禁</button></div>
            </form>
            <p id="ipBanMessage" role="status"></p>
            <div class="console-ip-ban-toolbar">
                <form class="console-ip-ban-search" onsubmit="admin.security.loadIpBans(1); return false;">
                    <input id="ipBanQuery" type="search" maxlength="100" aria-label="搜索封禁 IP、网段或备注" placeholder="搜索 IP、网段或备注"/>
                    <button type="submit">搜索</button>
                </form>
                <label>来源<select id="ipBanSource"><option value="all">全部来源</option><option value="manual">手动封禁</option><option value="automatic">自动风控</option></select></label>
                <label>状态<select id="ipBanStatus"><option value="active">生效中</option><option value="expired">已到期</option><option value="all">全部状态</option></select></label>
                <span id="ipBanTotal" role="status"></span>
            </div>
            <div id="ipBanList" aria-live="polite"></div>
            <nav id="ipBanPagination" class="console-ip-ban-pagination" aria-label="IP 封禁列表分页"></nav>
        </section>

</div>
</#if>
<#if securitySection == 'geo'>
<div data-security-page="geo" class="form">
        <section class="console-geo-access" aria-labelledby="geoAccessTitle">
            <h3 id="geoAccessTitle">地区访问规则</h3>
            <p class="console-form-help">使用本地 IP 库识别国家、省、市，命中限制时返回 403；与 IP 封禁、访问频率限制分别生效。</p>
            <p id="geoAccessCurrent" class="console-form-help" role="status"></p>
            <form onsubmit="admin.security.saveGeoAccess(); return false;">
                <div class="console-preference-field">
                    <label for="geoAccessActive">地区访问限制</label>
                    <select id="geoAccessActive">
                        <option value="false">暂停地区限制</option><option value="true">启用规则列表</option>
                    </select>
                </div>
                <div class="console-geo-rule-heading">
                    <h4>规则列表</h4><span id="geoAccessRuleCount" role="status"></span>
                    <button id="geoAccessAdd" type="button" onclick="admin.security.addGeoAccessRule()" disabled>添加规则</button>
                </div>
                <p id="geoAccessRuleHelp" class="console-form-help">从上到下执行。每条规则分别设置命中操作和未命中操作；“继续下一条”才会检查后续规则，所有规则都继续且未命中时允许访问。禁用的规则完全跳过，上移、下移调整优先级。</p>
                <p id="geoAccessLegacyFallback" class="console-form-help" role="status"></p>
                <p class="console-form-help">国家使用两位代码；范围也可直接填写 CN，或“CN/省”“CN/省/市”，兼容反斜杠写法。多项使用逗号或换行分隔。同一规则内的范围合并匹配；仅限制省市时，不要再填写整个国家。省市名称以本地 IP 库为准。</p>
                <div id="geoAccessRules" aria-describedby="geoAccessRuleHelp"></div>
                <nav id="geoAccessPagination" class="console-ip-ban-pagination" aria-label="地区规则分页"></nav>
                <div class="console-preference-field">
                    <label for="geoAccessUnknown">位置无法识别时</label>
                    <select id="geoAccessUnknown"><option value="allow">允许访问</option><option value="deny">拒绝访问</option></select>
                    <p class="console-form-help">国家无法识别，或优先规则所需省市信息缺失时，直接使用此策略。保存时会校验当前连接，避免封禁自己。</p>
                </div>
                <p id="geoAccessMessage" role="status"></p>
                <div class="console-form-actions"><button id="geoAccessSave" type="submit" disabled>保存地区规则</button></div>
            </form>
        </section>
</div>
</#if>
<#if securitySection == 'comments'>
<div data-security-page="comments" class="form">
        <div class="console-preference-field">
            <label for="commentAccessMode">评论发表权限</label>
            <select id="commentAccessMode">
                <option value="all">允许游客与注册用户评论（默认）</option>
                <option value="registered">仅限注册登录用户发表评论</option>
                <option value="off">关闭全站评论</option>
            </select>
            <p class="console-form-help">设为“仅限注册登录用户”时，未登录访客点击评论框将引导登录；如需开放前台读者邮箱注册，请前往 <a href="#tools/preference/interactive">偏好设定 &rarr; 前端开放设置</a> 开启。管理员评论不受影响。</p>
        </div>
        <div class="console-preference-field">
            <label for="commentModerationMode">评论审核策略</label>
            <select id="commentModerationMode">
                <option value="off">直接公开（经敏感词过滤后立即展示）</option>
                <option value="smart">智能审核（游客、首次评论或含多条外链进入待审）</option>
                <option value="all">全部先审后发（所有非管理员评论均需审核）</option>
            </select>
            <p class="console-form-help">待审核评论不会公开展示或计入文章评论数，可在“评论管理”中一键通过或驳回。</p>
        </div>
        <div class="console-preference-field">
            <label for="commentFilterBuiltin">评论敏感词库</label>
            <select id="commentFilterBuiltin" aria-describedby="commentFilterDescription">
                <option value="true">启用内置第三方词库</option>
                <option value="false">仅使用自定义过滤词</option>
            </select>
            <p id="commentFilterDescription" class="console-form-help">使用 sensitive-word 在服务器本地过滤，不会向外部服务发送评论。规则同时用于访客发表和编辑评论；管理员可保留原文进行管理。</p>
        </div>
        <div class="console-preference-field">
            <label for="spam">自定义评论过滤词</label>
            <textarea id="spam" rows="3" maxlength="20000" aria-describedby="commentFilterWordsHelp"></textarea>
            <p id="commentFilterWordsHelp" class="console-form-help">每行一个词，也可使用逗号分隔；与内置词库合并匹配。最多 2000 个词，每词最多 200 个字符。</p>
        </div>
        <div class="console-preference-field">
            <label for="commentFilterAllow">评论过滤白名单</label>
            <textarea id="commentFilterAllow" rows="3" maxlength="20000" aria-describedby="commentFilterAllowHelp"></textarea>
            <p id="commentFilterAllowHelp" class="console-form-help">用于允许被误判的词语或短语，填写方式同上；同一条评论中的其他过滤词仍会拦截。</p>
        </div>
        <div class="console-preference-field"><label for="commentCaptchaEnabled">游客评论验证码</label><select id="commentCaptchaEnabled"><option value="true">启用（默认）</option><option value="false">停用</option></select><p class="console-form-help">游客发表和编辑评论时验证本地图片验证码，已登录管理员免验证。后台登录在未启用 2FA 时开启图片验证码，启用 2FA 后自动关闭。</p></div>
        <div class="console-form-actions"><button type="button" onclick="admin.security.saveSettings()">${updateLabel}</button></div>
</div>
</#if>
</div>
