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
                <a href="#tools/preference/interactive">前端开放设置</a>
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
        <input id="githubPAT" class="normalInput" type="password" autocomplete="new-password"/>
        <label for="kanbanniangSelector">${selectedKanBanNiangLabel}</label>
        <select id="kanbanniangSelector">
            <option value="" selected>${randomLabel}</option>
        </select>
        <div class="console-preference-options">
            <label class="checkbox" for="welfareLuteService">
                <input id="welfareLuteService" type="checkbox" class="normalInput"/>
                <span>${welfareLabel}</span>
            </label>
            <label class="checkbox" for="enableArticleUpdateHint">
                <input id="enableArticleUpdateHint" type="checkbox" class="normalInput"/>
                <span>${enableArticleUpdateHint1Label}</span>
            </label>
            <label class="checkbox" for="allowVisitDraftViaPermalink">
                <input id="allowVisitDraftViaPermalink" type="checkbox" class="normalInput"/>
                <span>${allowVisitDraftViaPermalink1Label}</span>
            </label>
            <label class="checkbox" for="commentable">
                <input id="commentable" type="checkbox" class="normalInput"/>
                <span>${allowComment1Label}</span>
            </label>
            <label class="checkbox" for="syncGitHub">
                <input id="syncGitHub" type="checkbox" class="normalInput"/>
                <span>${syncGitHubLabel}</span>
            </label>
        </div>
        <div class="console-subcard console-subcard--surface">
            <div class="console-section-head">
                <div class="console-section-head__copy">
                    <h4>SEO 即时收录（IndexNow）与图片转换</h4>
                    <p>开启 IndexNow 后站点自动托管 <code>/{密钥}.txt</code> 校验文件，公开文章发布或更新时异步通知搜索引擎收录。</p>
                </div>
            </div>
            <div class="console-form-grid">
                <div class="console-form-cell">
                    <label for="indexNowEnabled">IndexNow 自动推送</label>
                    <select id="indexNowEnabled">
                        <option value="false">关闭（默认）</option>
                        <option value="true">开启发布后自动推送</option>
                    </select>
                </div>
                <div class="console-form-cell">
                    <label for="indexNowKey">IndexNow 验证密钥（8–64 位字母/数字/连字符）</label>
                    <div class="console-inline-bar console-inline-bar--fill" style="margin:0">
                        <input id="indexNowKey" type="text" maxlength="64" placeholder="留空在开启时自动生成随机密钥"/>
                        <button type="button" onclick="admin.preference.generateIndexNowKey()">生成密钥</button>
                    </div>
                </div>
            </div>
            <div class="console-checkbox-group" style="margin-bottom:0">
                <label class="checkbox" for="imageWebpAdaptive">
                    <input id="imageWebpAdaptive" type="checkbox" class="normalInput"/>
                    <span>编辑器上传静态图片（JPG / PNG / BMP）自动转换为 WebP（体积更小时生效，GIF 动图保持原格式）</span>
                </label>
            </div>
        </div>
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
            <option value="s3">S3 兼容存储（Cloudflare R2 / MinIO / AWS S3）</option>
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
        <input id="tc1" type="text" autocomplete="off" style="display:none" />
        <div id="td2" for="tc2" style="display:none"></div>
        <input id="tc2" type="text" autocomplete="off" style="display:none" />
        <div id="td3" for="tc3" style="display:none"></div>
        <input id="tc3" type="text" autocomplete="off" style="display:none" />
        <div id="td4" for="tc4" style="display:none"></div>
        <input id="tc4" type="text" autocomplete="off" style="display:none" />
        <div id="td5" for="tc5" style="display:none"></div>
        <input id="tc5" type="text" autocomplete="off" style="display:none" />
        <div id="td6" for="tc6" style="display:none"></div>
        <input id="tc6" type="text" autocomplete="off" style="display:none" />
        <div id="td7" for="tc7" style="display:none"></div>
        <input id="tc7" type="text" autocomplete="off" style="display:none" />
        <div id="td8" for="tc8" style="display:none"></div>
        <input id="tc8" type="text" autocomplete="off" style="display:none" />
        <div id="td9" for="tc9" style="display:none"></div>
        <div id="tc9Wrap" style="display:none">
            <select id="tc9">
                <option value="false">Virtual-Hosted 虚拟主机风格（Cloudflare R2 / AWS S3 默认）</option>
                <option value="true">Path-Style 路径风格（自建 MinIO / 局域网存储推荐）</option>
            </select>
        </div>
        <div class="fn__clear" id="tuChuangCfg" style="display: none">
            <button type="button" onclick="save()" class="fn__left">${saveConfigPicBedLabel}</button>
            <button type="button" id="clearPicSecretBtn" onclick="clearPicBedSecret()" class="fn__left" style="display:none;margin-left:8px">清空已存密钥</button>
            <button type="button" id="testS3PicBedBtn" onclick="testS3PicBedConfig()" class="fn__left" style="display:none;margin-left:8px">测试 S3 连通性</button>
        </div>
        <script>
            function loadRemind() {
                clear();
                var sel = $('#tcS').val();
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
                        $('#td7').html('${picBed33Label}');
                        break;
                    case 's3':
                        $('#td1').show().html('原生 AWS Signature V4 协议直传（零重型 SDK），兼容 Cloudflare R2、MinIO、AWS S3、Backblaze B2 及各大云 S3 兼容端点。');
                        $('#td2').show().html('<b>S3 Endpoint 端点地址</b>（含 http:// 或 https://）'); $('#tc2').show().attr('placeholder', '例如 https://xxx.r2.cloudflarestorage.com');
                        $('#td3').show().html('<b>AccessKey ID</b>'); $('#tc3').show();
                        $('#td4').show().html('<b>SecretAccessKey</b>'); $('#tc4').show();
                        $('#td5').show().html('<b>Bucket 存储桶名称</b>'); $('#tc5').show();
                        $('#td6').show().html('<b>Region 区域</b>（Cloudflare R2 可填 auto）'); $('#tc6').show().attr('placeholder', 'auto');
                        $('#td7').show().html('<b>自定义公开访问域名（可选）</b>'); $('#tc7').show().attr('placeholder', '例如 https://cdn.example.com，留空使用端点地址');
                        $('#td8').show().html('<b>存储子目录前缀（可选）</b>'); $('#tc8').show().attr('placeholder', '例如 uploads/images');
                        $('#td9').show().html('<b>Bucket 寻址模式</b>'); $('#tc9Wrap').show(); $('#tc9').val('false');
                        $('#testS3PicBedBtn').show();
                        break;
                }
                var stc = $('#sourceTC').text().split('<<>>');
                if (stc[0] === sel) {
                    for (var i = 1; i < stc.length; i++) {
                        $('#tc' + (i + 1)).val(stc[i]);
                    }
                }
                var secretInput = (sel === 'picuang' || sel === 'qiniu' || sel === 'aliyun' || sel === 'tencent') ? '#tc3' : ((sel === 'upyun' || sel === 's3') ? '#tc4' : '');
                var hasSavedSecret = !!(secretInput && admin.preference && admin.preference.tuChuangSecretSet && admin.preference.tuChuangType === sel);
                if (secretInput) {
                    $(secretInput).attr('type', 'password').attr('autocomplete', 'new-password')
                        .attr('placeholder', hasSavedSecret ? '已设置，留空保留原密钥' : '');
                }
                if (hasSavedSecret) $('#clearPicSecretBtn').show(); else $('#clearPicSecretBtn').hide();
            }
            $('#tcS').change(loadRemind);

            function clear() {
                $('#clearPicSecretBtn').hide();
                $('#testS3PicBedBtn').hide();
                $('#td9').hide();
                $('#tc9Wrap').hide();
                $('#tc9').val('false');
                for (var i = 1; i <= 8; i++) {
                    $('#tc' + i).val('').attr('type', 'text').attr('placeholder', '').attr('autocomplete', 'off').hide();
                    $('#td' + i).hide();
                }
            }

            function clearPicBedSecret() {
                var sel = $('#tcS').val();
                var secretInput = (sel === 'picuang' || sel === 'qiniu' || sel === 'aliyun' || sel === 'tencent') ? '#tc3' : ((sel === 'upyun' || sel === 's3') ? '#tc4' : '');
                if (!secretInput) return;
                $(secretInput).val('__VOLO_CLEAR_SECRET__');
                save();
            }

            function lockPicBed() { clear(); for (var k = 1; k <= 8; k++) $('#tc' + k).val(''); $('#tuChuangCfg').hide(); $('#tcS').attr('disabled', 'disabled'); $('#changeCfgBtn').show(); }
            function unlock() {
                $('#changeCfgBtn').hide();
                $('#tcS').removeAttr("disabled");
                $('#tuChuangCfg').show();
                loadRemind();
            }

            function save() {
                var sel = $('#tcS').val();
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
                    case 's3':
                        $('#sourceTC').text('s3<<>>' + $.trim($('#tc2').val()) + '<<>>' + $.trim($('#tc3').val()) + '<<>>' + $('#tc4').val() + '<<>>' + $.trim($('#tc5').val()) + '<<>>' + ($.trim($('#tc6').val()) || 'auto') + '<<>>' + $.trim($('#tc7').val()) + '<<>>' + $.trim($('#tc8').val()) + '<<>>' + ($('#tc9').val() === 'true' ? 'true' : 'false'));
                        break;
                }
                admin.preference.update(function () {
                    noBtnSwal('${picBed24Label}');
                });
            }

            function checkImageBedConfig() {
                return $.ajax({
                    type: 'POST',
                    url: Label.servePath + '/pic/local/check?path=' + encodeURIComponent($('#tc2').val()),
                    cache: false
                }).then(function (res) {
                    return Boolean(res && res.msg && res.msg.indexOf(':)') !== -1);
                });
            }

            function checkImageBedConfigAndAlert() {
                $.ajax({
                    type: 'POST',
                    url: Label.servePath + '/pic/local/check?path=' + encodeURIComponent($('#tc2').val()),
                    cache: false,
                    success: function (res) {
                        noBtnSwal(res.msg);
                    }
                })
            }
        </script>
        <script>
            function testS3PicBedConfig() {
                var btn = $('#testS3PicBedBtn');
                btn.prop('disabled', true);
                $.ajax({
                    type: 'POST',
                    url: Label.servePath + '/console/s3/check',
                    contentType: 'application/json;charset=UTF-8',
                    data: JSON.stringify({
                        endpoint: $.trim($('#tc2').val()),
                        accessKey: $.trim($('#tc3').val()),
                        secretKey: $('#tc4').val(),
                        bucket: $.trim($('#tc5').val()),
                        region: $.trim($('#tc6').val()) || 'auto',
                        pathStyle: $('#tc9').val() === 'true'
                    }),
                    success: function (res) {
                        admin.showNotice((res && res.msg) || (res && res.sc ? 'S3 连通性测试成功。' : 'S3 连通性测试失败。'), res && res.sc ? 'info' : 'error');
                    },
                    error: function (xhr) {
                        admin.showNotice((xhr.responseJSON && xhr.responseJSON.msg) || 'S3 连通性测试失败，请检查端点与凭证。', 'error');
                    },
                    complete: function () {
                        btn.prop('disabled', false);
                    }
                });
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
            <option value="true" selected="">${enableLabel}</option>
            <option value="false">${disableLabel}</option>
        </select>
        <br><br><br>
        <h3>${mailRemindLabel}</h3>
        <br>
        <div class="console-smtp-grid">
            <label>SMTP 服务器<input id="smtpHost" type="text" maxlength="253" placeholder="smtp.example.com" autocomplete="off"/></label>
            <label>端口<input id="smtpPort" type="number" min="1" max="65535" placeholder="465"/></label>
            <label>连接加密<select id="smtpSecurity"><option value="ssl">SSL / TLS</option><option value="starttls">STARTTLS</option><option value="none">不加密（仅用于可信内部邮件服务器）</option></select></label>
            <label>SMTP 身份验证<select id="smtpAuth"><option value="true">启用</option><option value="false">不需要验证</option></select></label>
            <label>用户名<input id="mailUsername" type="text" maxlength="254" autocomplete="off"/></label>
            <label>密码或授权码<input id="mailPassword" type="password" maxlength="1024" autocomplete="new-password" placeholder="留空保留已有密码"/></label>
            <label>发件人邮箱<input id="smtpFrom" type="email" maxlength="254" placeholder="留空使用用户名"/></label>
        </div>
        <p class="console-form-help">支持通用 SMTP 服务；服务器留空时停用邮件通知。旧版 QQ／企业邮箱／163 设置会自动转换展示，保存后使用上述配置。端口未填时按加密方式选择 465、587 或 25。</p>
        <label for="replyRemind">${BloggerMailboxLabel}</label>
        <input id="replyRemind" type="text" />
        <div>
            ${BloggerMailboxDescribeLabel}
        </div>
        <br><br>
        <h3>${serverJiangRemindLabel}</h3>
        <br>
        <label for="sendKey">${sendKeyLabel}</label>
        <input id="sendKey" type="password" autocomplete="new-password"/>
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
        <input id="b3logKey" type="password" autocomplete="new-password">
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
        <input id="fishpiKey" type="password" autocomplete="new-password">
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
            <label for="showLoginEntry">前端显示登录入口</label>
            <select id="showLoginEntry" aria-describedby="loginEntryDescription"><option value="false">隐藏（默认）</option><option value="true">显示管理员登录入口</option></select>
            <p id="loginEntryDescription" class="console-form-help">控制未登录访客是否能在皮肤导航中看到管理员登录链接。已登录管理员始终保留后台管理和退出登录，不受此开关影响。</p>
            <p class="console-form-help">${managementLoginPathLabel!'当前登录路径'}：<a href="${(adminLoginPath!'/start')?html}"><code>${(adminLoginPath!'/start')?html}</code></a></p>
        </div>
        <div class="console-preference-field">
            <label for="interactiveSwitch">前端显示评论</label>
            <select id="interactiveSwitch" aria-describedby="interactiveDescription">
                <option value="true" selected="">${enableLabel}</option>
                <option value="false">${disableLabel}</option>
            </select>
            <p id="interactiveDescription" class="console-form-help">控制前端评论区及评论相关入口，与登录入口分别设置。细粒度评论权限（游客/仅注册读者/待审队列）可在 <a href="#security/comments">安全 &rarr; 评论防护</a> 中配置。</p>
        </div>
        <div class="console-preference-field">
            <label for="allowRegister">开放前台读者邮箱注册</label>
            <select id="allowRegister" aria-describedby="allowRegisterDescription">
                <option value="false">关闭（默认）</option>
                <option value="true">开放注册（需验证码激活）</option>
            </select>
            <p id="allowRegisterDescription" class="console-form-help">开启前需先在「动态提醒」中配置发信邮箱（SMTP）。开启后读者可通过 6 位邮箱验证码在 <code>/user/register</code> 或文章弹窗注册独立读者身份（严格隔离为访客角色，无任何后台管理权限）。</p>
        </div>
        <div class="console-preference-options" style="margin: 12px 0;">
            <label class="checkbox" for="enableInstantSearch">
                <input id="enableInstantSearch" type="checkbox" class="normalInput"/>
                <span>启用前台 <code>Ctrl / Cmd + K</code> 快捷键即时搜索弹窗</span>
            </label>
            <label class="checkbox" for="enableReadingStats">
                <input id="enableReadingStats" type="checkbox" class="normalInput"/>
                <span>文章页顶部显示全文字数统计与预计阅读时长</span>
            </label>
        </div>
        <div class="console-preference-field">
            <label for="articleOutdatedDays">陈旧技术文章时效提醒阈值（天）</label>
            <input id="articleOutdatedDays" class="normalInput" type="number" min="0" max="3650" placeholder="默认 365，填 0 关闭时效横幅"/>
            <p class="console-form-help">当文章距离最后更新超过该天数时，自动在正文顶部展示温和的时效性甄别提醒；设为 <code>0</code> 则全局停用。</p>
        </div>

        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
    <div id="tabPreferencePanel_myGitHub" class="fn__none form">
        <div class="console-preference-field console-github-account">
            <label for="myGitHubID">${myGitHubIDLabel}</label>
            <input id="myGitHubID" type="text" maxlength="39" autocomplete="off" aria-describedby="githubAccountHelp" placeholder="例如 Willh92"/>
            <p id="githubAccountHelp" class="console-form-help">填写 GitHub 用户名。开源项目页面、仓库列表和统计卡片均使用此账号；头像和统计标题链接到对应的 GitHub 用户主页。</p>
        </div>
        <div class="console-github-auto">
            <div><label class="checkbox"><input id="enableAutoFlushGitHub" type="checkbox" class="normalInput"/> 自动更新开源项目页面</label><p class="console-form-help">约每 24 小时读取公开仓库，也可使用下方“立即同步仓库”。</p></div>
            <div><label class="checkbox"><input id="enableAutoFlushGitHubProfile" type="checkbox" class="normalInput"/> 同步文章至 GitHub 个人资料</label><p class="console-form-help">将最近 5 篇公开文章同步至同名资料仓库，需要配置有仓库读写权限的 GitHub Token。</p></div>
        </div>
        <section class="console-github-card" aria-labelledby="githubRepositoryTitle">
            <h3 id="githubRepositoryTitle">公开仓库展示</h3>
            <label class="checkbox console-github-include"><input id="githubIncludeForks" type="checkbox" class="normalInput" disabled/> 包含 Fork 仓库</label>
            <p class="console-form-help">默认仅展示原创仓库；下方可逐个选择是否显示。Stars 是当前展示仓库收到的 Star 总和，不是你收藏过的仓库数量。隐藏仓库不计入统计，选择会在下次同步时保留。</p>
            <div class="console-github-toolbar"><input id="githubRepositoryQuery" type="search" placeholder="搜索仓库或简介" aria-label="搜索仓库"/><div><button id="githubRepoRefresh" type="button" onclick="admin.githubRepositories.refresh(this)">立即同步仓库</button><button type="button" onclick="admin.githubRepositories.load()">重新载入</button></div></div>
            <p id="githubRepositoryMessage" class="console-form-help" role="status"></p>
            <p id="githubRepositorySummary" class="console-github-summary" aria-live="polite"></p>
            <div id="githubRepositoryList"></div>
            <div id="githubRepositoryPagination" class="console-github-pagination"></div>
        </section>

        <div class="console-form-actions"><button class="fn__right" onclick="admin.preference.update()">${updateLabel}</button></div>
        <div class="fn__clear"></div>
    </div>
</div>
${plugins}
