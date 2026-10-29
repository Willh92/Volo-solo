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
<div id="tabPreference" class="sub-tabs fn__clear">
    <ul>
        <li>
            <div id="tabPreference_config">
                <a class="tab-current" href="#tools/preference/config">${configSettingsLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_setting">
                <a href="#tools/preference/setting">${paramSettingsLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_mailAndRemind">
                <a href="#tools/preference/mailAndRemind">${mailAndRemindLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_imageBed">
                <a href="#tools/preference/imageBed">${imageBedLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_signs">
                <a href="#tools/preference/signs">${signLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_markdown">
                <a href="#tools/preference/markdown">${editorLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_interactive">
                <a href="#tools/preference/interactive">${interactiveLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_security">
                <a href="#tools/preference/security">${securityLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabPreference_myGitHub">
                <a href="#tools/preference/myGitHub">${myGitHubLabel}</a>
            </div>
        </li>
        <#if b3logEnabled>
            <li>
                <div id="tabPreference_b3logEcology">
                    <a href="#tools/preference/b3logEcology">${b3logEcologyLabel}</a>
                </div>
            </li>
        </#if>
        <#if fishpiEnabled>
            <li>
                <div id="tabPreference_fishpiEcology">
                    <a href="#tools/preference/fishpiEcology">${fishpiEcologyLabel}</a>
                </div>
            </li>
        </#if>
    </ul>
</div>
<div id="tabPreferencePanel" class="sub-tabs-main">
    <div id="tabPreferencePanel_config" class="form">
        <label for="blogTitle">${blogTitle1Label}</label>
        <input id="blogTitle" type="text"/>
        <label for="blogSubtitle">${blogSubtitle1Label}</label>
        <input id="blogSubtitle" type="text"/>
        <label for="blogHost">${blogHost1Label}</label>
        <input id="blogHost" type="text" value="${serverHost}" readonly="true"/>
        <label for="metaKeywords">${metaKeywords1Label}</label>
        <input id="metaKeywords" type="text"/>
        <label for="metaDescription">${metaDescription1Label}</label>
        <input id="metaDescription" type="text"/>
        <label for="htmlHead">${htmlhead1Label}</label>
        <textarea rows="6" id="htmlHead"></textarea>
        <label for="noticeBoard">${noticeBoard1Label}</label>
        <textarea rows="6" id="noticeBoard"></textarea>
        <label for="footerContent">${footerContent1Label}</label>
        <textarea rows="6" id="footerContent"></textarea>
<div class="fn__clear">
            <div class="console-form-actions"><button onclick="admin.preference.update()" class="fn__right">${updateLabel}</button></div>
        </div>
    </div>
    <div id="tabPreferencePanel_setting" class="fn__none form">
        <label for="localeString">${localeString1Label}</label>
        <select id="localeString">
            <option value="zh_CN">简体中文</option>
            <option value="en_US">English(US)</option>
        </select>
        <label for="articleListDisplayCount">${pageSize1Label}</label>
        <input id="articleListDisplayCount" class="normalInput" type="text"/>
        <label for="faviconURL">${faviconLabel}</label>
        <input id="faviconURL" class="normalInput" type="text"/>
        <label for="githubPAT">${githubPATLabel}</label>
        <input id="githubPAT" class="normalInput" type="text"/>
        <label for="kanbanniangSelector">${selectedKanBanNiangLabel}</label>
        <select id="kanbanniangSelector">
            <option value="" selected>${randomLabel}</option>
        </select>
        <label>
            <div class="fn__flex-inline">
                ${welfareLabel}
                <input id="welfareLuteService" type="checkbox" class="normalInput"/>
            </div>
        </label>
        <label>
            <div class="fn__flex-inline">
                ${enableArticleUpdateHint1Label}
                <input id="enableArticleUpdateHint" type="checkbox" class="normalInput"/>
            </div>
        </label>
        <label>
            <div class="fn__flex-inline">
                ${allowVisitDraftViaPermalink1Label}
                <input id="allowVisitDraftViaPermalink" type="checkbox" class="normalInput"/>
            </div>
        </label>
        <label>
            <div class="fn__flex-inline">
                ${allowComment1Label}
                <input id="commentable" type="checkbox" class="normalInput"/>
            </div>
        </label>
        <label>
            <div class="fn__flex-inline">
                ${syncGitHubLabel}
                <input id="syncGitHub" type="checkbox" class="normalInput"/>
            </div>
        </label>
        <br><br>
        <details>
            <summary style="cursor: default">${advancedSettingsLabel}</summary>
            <label for="timeZoneId">${timeZoneId1Label}</label>
            <select id="timeZoneId">
                ${timeZoneIdOptions}
            </select>
            <label for="articleListDisplay">${articleListDisplay1Label}</label>
            <select id="articleListDisplay">
                <option value="titleOnly">${titleOnlyLabel}</option>
                <option value="titleAndAbstract">${titleAndAbstractLabel}</option>
                <option value="titleAndContent">${titleAndContentLabel}</option>
            </select>
            <label for="hljsTheme">
                <a href="https://xyproto.github.io/splash/docs/longer/all.html" target="_blank">${previewLabel}</a>${hljsThemeLabel}
            </label>
            <select id="hljsTheme">
                <option value="abap">abap</option>
                <option value="algol">algol</option>
                <option value="algol_nu">algol_nu</option>
                <option value="arduino">arduino</option>
                <option value="autumn">autumn</option>
                <option value="borland">borland</option>
                <option value="bw">bw</option>
                <option value="colorful">colorful</option>
                <option value="dracula">dracula</option>
                <option value="emacs">emacs</option>
                <option value="friendly">friendly</option>
                <option value="fruity">fruity</option>
                <option value="github">github</option>
                <option value="igor">igor</option>
                <option value="lovelace">lovelace</option>
                <option value="manni">manni</option>
                <option value="monokai">monokai</option>
                <option value="monokailight">monokailight</option>
                <option value="murphy">murphy</option>
                <option value="native">native</option>
                <option value="paraiso-dark">paraiso-dark</option>
                <option value="paraiso-light">paraiso-light</option>
                <option value="pastie">pastie</option>
                <option value="perldoc">perldoc</option>
                <option value="pygments">pygments</option>
                <option value="rainbow_dash">rainbow_dash</option>
                <option value="rrt">rrt</option>
                <option value="solarized-dark">solarized-dark</option>
                <option value="solarized-dark256">solarized-dark256</option>
                <option value="solarized-light">solarized-light</option>
                <option value="swapoff">swapoff</option>
                <option value="tango">tango</option>
                <option value="trac">trac</option>
                <option value="vim">vim</option>
                <option value="vs">vs</option>
                <option value="xcode">xcode</option>
            </select>
            <label for="maxArchive">${maxArchiveLabel}</label>
            <input id="maxArchive" class="normalInput" type="text"/>
            <label for="mostUsedTagDisplayCount">${indexTagDisplayCnt1Label}</label>
            <input id="mostUsedTagDisplayCount" class="normalInput" type="text"/>
            <label for="recentCommentDisplayCount">${indexRecentCommentDisplayCnt1Label}</label>
            <input id="recentCommentDisplayCount" class="normalInput" type="text"/>
            <label for="mostCommentArticleDisplayCount">${indexMostCommentArticleDisplayCnt1Label}</label>
            <input id="mostCommentArticleDisplayCount" class="normalInput" type="text"/>
            <label for="mostViewArticleDisplayCount">${indexMostViewArticleDisplayCnt1Label}</label>
            <input id="mostViewArticleDisplayCount" class="normalInput" type="text"/>
            <label for="articleListPaginationWindowSize">${windowSize1Label}</label>
            <input id="articleListPaginationWindowSize" class="normalInput" type="text"/>
            <label for="randomArticlesDisplayCount">${randomArticlesDisplayCnt1Label}</label>
            <input id="randomArticlesDisplayCount" class="normalInput" type="text"/>
            <label for="relevantArticlesDisplayCount">${relevantArticlesDisplayCnt1Label}</label>
            <input id="relevantArticlesDisplayCount" class="normalInput" type="text"/>
            <label for="feedOutputMode">${feedOutputModel1Label}</label>
            <select id="feedOutputMode">
                <option value="abstract">${abstractLabel}</option>
                <option value="fullContent">${fullContentLabel}</option>
            </select>
            <label for="feedOutputCnt">${feedOutputCnt1Label}</label>
            <input id="feedOutputCnt" class="normalInput" type="text"/>
            <label for="imageUploadCompress">${imageUploadCompressLabel}</label>
            <input id="imageUploadCompress" class="normalInput" type="text"/>
            <label for="thumbCompress">${thumbCompressLabel}</label>
            <input id="thumbCompress" class="normalInput" type="text"/>
            <label for="customVars">${customVars1Label}</label>
            <input id="customVars" class="normalInput" type="text"/>
        </details>
        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_signs" class="fn__none form">
        <button id="preferenceSignButton1">${signLabel}1</button>
        <textarea rows="8" id="preferenceSign1"></textarea>
        <button id="preferenceSignButton2">${signLabel}2</button>
        <textarea rows="8" id="preferenceSign2"></textarea>
        <button id="preferenceSignButton3">${signLabel}3</button>
        <textarea rows="8" id="preferenceSign3"></textarea>
        <div class="console-form-actions"><button onclick="admin.preference.update()" class="fn__right">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_markdown" class="fn__none form">

        <h3>${editorModeLabel}</h3>
        <br>
        <label><input name="editorMode" type="radio" value="wysiwyg">&nbsp;${editorModeWYSIWYGLabel} </label>
        <label><input name="editorMode" type="radio" value="ir">&nbsp;${editorModeIRLabel} </label>
        <label><input name="editorMode" type="radio" value="sv" checked>&nbsp;${editorModeSVLabel} </label>
        <br><br><br>
        <h3>${othersLabel}</h3>
        <br>
        <label class="checkbox">
            <input id="showCodeBlockLn" type="checkbox" class="normalInput"/>
            <span>&nbsp;${showCodeBlockLnLabel}</span>
        </label>

        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_imageBed" class="fn__none form">
        <div class="fn__clear"></div>
        <div style="display: none">
            ${srcDataLabel}<span id="sourceTC"></span>
        </div>
        <label for="tcS">${selectPicBedLabel}</label>
        <select id="tcS" disabled>
            <option selected></option>
            <option value="local">${localPicBedLabel}</option>
            <option value="qiniu">${qiNiuLabel}</option>
            <option value="aliyun">${aliLabel}</option>
            <option value="upyun">${upyunLabel}</option>
            <option value="tencent">${tencentLabel}</option>
            <option value="picuang">${picuangLabel}</option>
            <option value="hacpai">${liandiPicBedLabel}</option>
        </select>
        <div class="fn__clear" id="changeCfgBtn">
            <button onclick="unlock()" class="fn__left">${allowConfigPicBedLabel}</button>
        </div>
        <div id="td1" for="tc1" style="display:none"></div>
        <input id="tc1" type="text" style="display:none" />
        <div id="td2" for="tc2" style="display:none"></div>
        <input id="tc2" type="text" style="display:none" />
        <div id="td3" for="tc3" style="display:none"></div>
        <input id="tc3" type="text" style="display:none" />
        <div id="td4" for="tc4" style="display:none"></div>
        <input id="tc4" type="text" style="display:none" />
        <div id="td5" for="tc5" style="display:none"></div>
        <input id="tc5" type="text" style="display:none" />
        <div id="td6" for="tc6" style="display:none"></div>
        <input id="tc6" type="text" style="display:none" />
        <div id="td7" for="tc7" style="display:none"></div>
        <input id="tc7" type="text" style="display:none" />
        <div id="td8" for="tc8" style="display:none"></div>
        <input id="tc8" type="text" style="display:none" />
        <div class="fn__clear" id="tuChuangCfg" style="display: none">
            <button onclick="save()" class="fn__left">${saveConfigPicBedLabel}</button>
        </div>
        <script>
            function loadRemind() {
                clear();
                sel = $('#tcS').val();
                switch (sel) {
                    case 'hacpai':
                        $('#td1').show();
                        $('#td1').text('${picBed1Label}');
                        break;
                    case 'picuang':
                        $('#td1').show();
                        $('#td1').html('${picBed2Label}');
                        $('#td2').show(); $('#tc2').show();
                        $('#td2').html('${picBed3Label}');
                        $('#td3').show(); $('#tc3').show();
                        $('#td3').html('${picBed4Label}');
                        $('#td4').show();
                        $('#td4').html('${picBed5Label}');
                        break;
                    case 'qiniu':
                        $('#td1').show();
                        $('#td1').html('${picBed6Label}');
                        $('#td2').show(); $('#tc2').show();
                        $('#td2').html('<b>AccessKey</b>');
                        $('#td3').show(); $('#tc3').show();
                        $('#td3').html('<b>SecretKey</b>');
                        $('#td4').show(); $('#tc4').show();
                        $('#td4').html('${picBed7Label}');
                        $('#td5').show(); $('#tc5').show();
                        $('#td5').html('${picBed8Label}');
                        $('#td6').show(); $('#tc6').show();
                        $('#td6').html('${picBed9Label}');
                        break;
                    case 'aliyun':
                        $('#td1').show();
                        $('#td1').html('${picBed10Label}');
                        $('#td2').show(); $('#tc2').show();
                        $('#td2').html('<b>AccessKeyID</b>');
                        $('#td3').show(); $('#tc3').show();
                        $('#td3').html('<b>AccessKeySecret</b>');
                        $('#td4').show(); $('#tc4').show();
                        $('#td4').html('${picBed11Label}');
                        $('#td5').show(); $('#tc5').show();
                        $('#td5').html('${picBed12Label}');
                        $('#td6').show(); $('#tc6').show();
                        $('#td6').html('${picBed13Label}');
                        $('#td7').show(); $('#tc7').show();
                        $('#td7').html('${picBed14Label}')
                        $('#td8').show(); $('#tc8').show();
                        $('#td8').html('${picBed26Label}')
                        break;
                    case 'upyun':
                        $('#td1').show();
                        $('#td1').html('${picBed15Label}');
                        $('#td2').show(); $('#tc2').show();
                        $('#td2').html('${picBed16Label}');
                        $('#td3').show(); $('#tc3').show();
                        $('#td3').html('${picBed17Label}');
                        $('#td4').show(); $('#tc4').show();
                        $('#td4').html('${picBed18Label}');
                        $('#td5').show(); $('#tc5').show();
                        $('#td5').html('${picBed19Label}');
                        $('#td6').show(); $('#tc6').show();
                        $('#td6').html('${picBed20Label}');
                        break;
                    case 'local':
                        $('#td1').show();
                        $('#td1').html('${picBed21Label}');
                        $('#td2').show(); $('#tc2').show();
                        $('#td2').html('${picBed22Label}');
                        $('#td3').show();
                        $('#td3').html('${picBed23Label}');
                        break;
                    case 'tencent':
                        $('#td1').show();
                        $('#td1').html('${picBed27Label}');
                        $('#td2').show(); $('#tc2').show();
                        $('#td2').html('${picBed28Label}');
                        $('#td3').show(); $('#tc3').show();
                        $('#td3').html('${picBed29Label}');
                        $('#td4').show(); $('#tc4').show();
                        $('#td4').html('${picBed30Label}');
                        $('#td5').show(); $('#tc5').show();
                        $('#td5').html('${picBed31Label}');
                        $('#td6').show(); $('#tc6').show();
                        $('#td6').html('${picBed32Label}');
                        $('#td7').show(); $('#tc7').show();
                        $('#td7').html('${picBed33Label}')
                        break;
                }
                var stc = $('#sourceTC').text().split('<<>>');
                for (var i = 1; i < stc.length; i++) {
                    $('#tc' + (i + 1)).val(stc[i]);
                }
            }
            $('#tcS').change(loadRemind);

            function clear() {
                for (i = 1; i <= 8; i++) {
                    $('#tc' + i).hide();
                    $('#td' + i).hide();
                }
            }

            function unlock() {
                $('#changeCfgBtn').hide();
                $('#tcS').removeAttr("disabled");
                $('#tuChuangCfg').show();
                loadRemind();
            }

            function save() {
                sel = $('#tcS').val();
                switch (sel) {
                    case 'hacpai':
                        $('#sourceTC').text('hacpai');
                        break;
                    case 'picuang':
                        $('#sourceTC').text('picuang<<>>' + $('#tc2').val() + '<<>>' + $('#tc3').val());
                        break;
                    case 'qiniu':
                        $('#sourceTC').text('qiniu<<>>' + $('#tc2').val() + '<<>>' + $('#tc3').val() + '<<>>' + $('#tc4').val() + '<<>>' + $('#tc5').val() + '<<>>' + $('#tc6').val());
                        break;
                    case 'aliyun':
                        $('#sourceTC').text('aliyun<<>>' + $('#tc2').val() + '<<>>' + $('#tc3').val() + '<<>>' + $('#tc4').val() + '<<>>' + $('#tc5').val() + '<<>>' + $('#tc6').val() + '<<>>' + $('#tc7').val() + '<<>>' + $('#tc8').val());
                        break;
                    case 'upyun':
                        $('#sourceTC').text('upyun<<>>' + $('#tc2').val() + '<<>>' + $('#tc3').val() + '<<>>' + $('#tc4').val() + '<<>>' + $('#tc5').val() + '<<>>' + $('#tc6').val());
                        break;
                    case 'local':
                        $('#sourceTC').text('local<<>>' + $('#tc2').val());
                        break;
                    case 'tencent':
                        $('#sourceTC').text('tencent<<>>' + $('#tc2').val() + '<<>>' + $('#tc3').val() + '<<>>' + $('#tc4').val() + '<<>>' + $('#tc5').val() + '<<>>' + $('#tc6').val() + '<<>>' + $('#tc7').val());
                        break;
                }
                admin.preference.update();
                noBtnSwal('${picBed24Label}');
            }

            function checkImageBedConfig() {
                let flag = false;
                $.ajax({
                    type: 'GET',
                    url: Label.servePath + '/pic/local/check?path=' + encodeURIComponent($('#tc2').val()),
                    async: false,
                    success: function (res) {
                        result = res.msg;
                        flag = result.indexOf(":)") !== -1;
                    }
                })
                return flag;
            }

            function checkImageBedConfigAndAlert() {
                $.ajax({
                    type: 'GET',
                    url: Label.servePath + '/pic/local/check?path=' + encodeURIComponent($('#tc2').val()),
                    async: false,
                    success: function (res) {
                        noBtnSwal(res.msg);
                    }
                })
            }
        </script>
        <div>${picBed25Label}</div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_mailAndRemind" class="fn__none form">
        <div class="fn__clear"></div>
        <label for="adminActiveSentToMailbox">${adminActiveSentToMailboxLabel}</label>
        <br>
        <select id="adminActiveSentToMailbox">
            <option value="on" selected="">${enableLabel}</option>
            <option value="off">${disableLabel}</option>
        </select>
        <br><br><br>
        <h3>${mailRemindLabel}</h3>
        <br>
        <label for="mailBox">${mailBoxLabel}</label>
        <select id="mailBox">
            <option value="" selected>${unsetLabel}</option>
            <option value="QQ">${qqMailboxLabel}</option>
            <option value="QQ_ENT">${qqEntMailboxLabel}</option>
            <option value="163">${neteaseMailboxLabel}</option>
        </select>
        <label for="mailUsername">${mailUsernameLabel}</label>
        <input id="mailUsername" type="text"/>
        <label for="mailPassword">${mailPasswordLabel}</label>
        <input id="mailPassword" type="text"/>
        <div>
            ${mailboxDescriptionLabel}
        </div>
        <label for="replyRemind">${BloggerMailboxLabel}</label>
        <input id="replyRemind" type="text" />
        <div>
            ${BloggerMailboxDescribeLabel}
        </div>
        <br><br>
        <h3>${serverJiangRemindLabel}</h3>
        <br>
        <label for="sendKey">${sendKeyLabel}</label>
        <input id="sendKey" type="text"/>
        <br><br>
        <a href="https://github.com/Willh92/Volo-solo/blob/HEAD/UPGRADE.md#%E6%8F%90%E9%86%92%E8%AE%BE%E7%BD%AE" target="_blank">
            ${usageLabel}?
        </a>

        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_b3logEcology" class="fn__none form">
        <div class="fn__clear"></div>
        <h4>${liandi1Label}</h4>
        <br>
        <label for="hacpaiUser">${hacpaiUser1Label}</label>
        <input id="hacpaiUser" type="text"/>
        <label for="b3logKey">${b3logKey1Label}</label>
        <input id="b3logKey" type="text">
        <div>
            <div>
                ${liandi2Label}
            </div>
            <style>
                .menu ul li {
                    list-style-type: none;
                }
            </style>
            <details class="menu">
                ${liandi3Label}
            </details>
            <details class="menu">
                ${liandi4Label}
            </details>
        </div>
        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_fishpiEcology" class="fn__none form">
        <div class="fn__clear"></div>
        <h4>${fishpi1Label}</h4>
        <br>
        <label for="fishpiKey">API KEY</label>
        <input id="fishpiKey" type="text">
        <div>
            <div>
                ${fishpi2Label}
            </div>
            <style>
                .menu ul li {
                    list-style-type: none;
                }
            </style>
            <details class="menu">
                ${fishpi3Label}
            </details>
        </div>
        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_interactive" class="fn__none form">
        <div class="console-preference-field">
            <label for="interactiveSwitch">${nonInteractiveLabel}</label>
            <select id="interactiveSwitch" aria-describedby="interactiveDescription">
                <option value="on" selected="">${enableLabel}</option>
                <option value="off">${disableLabel}</option>
            </select>
            <p id="interactiveDescription" class="console-form-help">${interactiveDescribeLabel}</p>
            <p class="console-form-help">${managementLoginPathLabel!'当前登录路径'}：<a href="${(adminLoginPath!'/start')?html}"><code>${(adminLoginPath!'/start')?html}</code></a></p>
        </div>

        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_security" class="fn__none form">
        <section class="console-entry-info">
            <h3>管理入口</h3>
            <p>前台不展示管理链接。请在服务器的 <code>WEB-INF/classes/local.properties</code> 设置 <code>adminPath</code> 并重启，具体示例见升级说明。</p>
            <p>当前面板：<code>${(adminPanelPath!'/admin-index.do')?html}</code><br>当前登录页：<code>${(adminLoginPath!'/start')?html}</code></p>
        </section>
        <section class="console-ip-bans" aria-labelledby="ipBanTitle">
            <h3 id="ipBanTitle">IP 封禁</h3>
            <p class="console-form-help">支持 IPv4、IPv6 单个地址或 CIDR 网段，如 192.0.2.1、192.0.2.0/24、2001:db8::/48。封禁后返回 403。位置仅供参考，网段显示起始地址的位置。</p>
            <form id="ipBanForm" class="console-ip-ban-add" onsubmit="admin.preference.addIpBan(); return false;">
                <label for="ipBanRange">IP / CIDR 网段</label><input id="ipBanRange" type="text" maxlength="64" placeholder="192.0.2.1 或 2001:db8::/48" required/>
                <label for="ipBanReason">备注（选填）</label><input id="ipBanReason" type="text" maxlength="200" placeholder="例如：持续发布广告"/>
                <button type="submit">添加封禁</button>
            </form>
            <p id="ipBanMessage" role="status"></p>
            <div class="console-ip-ban-toolbar">
                <form class="console-ip-ban-search" onsubmit="admin.preference.loadIpBans(1); return false;">
                    <input id="ipBanQuery" type="search" maxlength="100" aria-label="搜索封禁 IP、网段或备注" placeholder="搜索 IP、网段或备注"/>
                    <button type="submit">搜索</button>
                </form>
                <span id="ipBanTotal" role="status"></span>
            </div>
            <div id="ipBanList" aria-live="polite"></div>
            <nav id="ipBanPagination" class="console-ip-ban-pagination" aria-label="IP 封禁列表分页"></nav>
        </section>
        <section class="console-geo-access" aria-labelledby="geoAccessTitle">
            <h3 id="geoAccessTitle">地区访问规则</h3>
            <p class="console-form-help">使用本地 IP 库识别国家、省、市，命中限制时返回 403；与 IP 封禁、访问频率限制分别生效。</p>
            <p id="geoAccessCurrent" class="console-form-help" role="status"></p>
            <form onsubmit="admin.preference.saveGeoAccess(); return false;">
                <div class="console-preference-field">
                    <label for="geoAccessMode">访问策略</label>
                    <select id="geoAccessMode" onchange="if (this.value === 'allow') document.getElementById('geoAccessUnknown').value = 'deny';">
                        <option value="off">不限制地区</option><option value="deny">拒绝指定地区</option><option value="allow">仅允许指定地区</option>
                    </select>
                </div>
                <div class="console-preference-field">
                    <label for="geoAccessCountries">国家或地区代码</label>
                    <input id="geoAccessCountries" type="text" maxlength="1000" placeholder="例如 CN 或 US,CA" aria-describedby="geoAccessExamples"/>
                    <p id="geoAccessExamples" class="console-form-help">使用两位代码，逗号分隔。拒绝 US：选“拒绝指定地区”并填写 US；只允许 CN：选“仅允许指定地区”并填写 CN。</p>
                </div>
                <div class="console-preference-field">
                    <label for="geoAccessRegions">省市规则（选填）</label>
                    <textarea id="geoAccessRegions" rows="4" maxlength="10000" placeholder="CN/浙江省&#10;CN/广东省/深圳市" aria-describedby="geoAccessRegionHelp"></textarea>
                    <p id="geoAccessRegionHelp" class="console-form-help">每行一条，格式为“国家代码/省”或“国家代码/省/市”。与国家列表合并匹配；只限制省市时，请留空上方国家列表。名称以本地定位库显示为准，支持 ip2region 和 MMDB。</p>
                </div>
                <div class="console-preference-field">
                    <label for="geoAccessUnknown">位置无法识别时</label>
                    <select id="geoAccessUnknown"><option value="allow">允许访问</option><option value="deny">拒绝访问</option></select>
                    <p class="console-form-help">IP 库缺失、地址未收录、内网地址或缺少所需省市信息都可能无法识别。保存时会校验当前连接，避免封禁自己。</p>
                </div>
                <p id="geoAccessMessage" role="status"></p>
                <div class="console-form-actions"><button id="geoAccessSave" type="submit">保存地区规则</button></div>
            </form>
        </section>
        <div class="console-preference-field">
            <label for="wafPower">${securityPowerLabel}</label>
            <select id="wafPower" aria-describedby="securityDescription">
                <option value="on" selected="">${enableLabel}</option>
                <option value="off">${disableLabel}</option>
            </select>
            <p id="securityDescription" class="console-form-help">${securityDescribeLabel}</p>
            <p class="console-form-help">${clientAddressLabel!'识别的访客 IP'}：<code>${(securityClientAddress!'—')?html}</code><br>${proxyPeerAddressLabel!'连接到 Java 的 IP'}：<code>${(securityPeerAddress!'—')?html}</code><br>${proxyAddressHelpLabel}</p>
        </div>
        <br><br>
        <label>${frequencyLimitLabel}</label>
        <br>
        <input id="wafCurrentLimitTimes" type="text" style="width: 100px"> ${securitySetTimesLabel} <input id="wafCurrentLimitSecond" type="text" style="width: 100px"> ${securitySetSecondLabel}
        <br><br>
        <div class="console-preference-field">
            <label for="commentFilterBuiltin">评论敏感词库</label>
            <select id="commentFilterBuiltin" aria-describedby="commentFilterDescription">
                <option value="on">启用内置第三方词库</option>
                <option value="off">仅使用自定义过滤词</option>
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
        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_myGitHub" class="fn__none form">
        <div class="fn__clear"></div>
        ${myGitHub1Label}
        <br>
        <label class="checkbox">
            <input id="enableAutoFlushGitHub" type="checkbox" class="normalInput"/>
            <span>&nbsp;${autoFlushGitHubLabel}</span>
        </label>
        <div class="fn__clear"></div>
        ${myGitHubProfileLabel}
        <br>
        <label class="checkbox">
            <input id="enableAutoFlushGitHubProfile" type="checkbox" class="normalInput"/>
            <span>&nbsp;${autoFlushGitHubProfileLabel}</span>
        </label>
        <br>
        <label for="myGitHubID">${myGitHubIDLabel}</label>
        <input id="myGitHubID" type="text"/>
        <br><br>
        <p>${myGitHub2Label}</p>

        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
</div>
${plugins}
