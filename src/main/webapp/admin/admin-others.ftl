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
<div id="tabOthers" class="sub-tabs">
    <ul>
        <li>
            <div id="tabOthers_maintenance">
                <a href="#tools/others/maintenance">维护工具</a>
            </div>
        </li>
        <li>
            <div id="tabOthers_data">
                <a href="#tools/others/data">${exportDataLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabOthers_import">
                <a href="#tools/others/import">${importDataLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabOthers_log">
                <a href="#tools/others/log">${viewLogLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabOthers_tag">
                <a href="#tools/others/tag">${clearDataLabel}</a>
            </div>
        </li>
        <li>
            <div id="tabOthers_media">
                <a href="#tools/others/media" onclick="admin.others.loadMediaLibrary();">本地图床媒体库</a>
            </div>
        </li>
        <li>
            <div id="tabOthers_backups">
                <a href="#tools/others/backups" onclick="admin.others.loadLocalBackups();">本地滚动备份</a>
            </div>
        </li>
        <li>
            <div id="tabOthers_webhooks">
                <a href="#tools/others/webhooks" onclick="admin.others.loadWebhooks();">Webhook 推送</a>
            </div>
        </li>
        <#if b3logEnabled || fishpiEnabled>
            <li>
                <div id="tabOthers_commentSync">
                    <a href="#tools/others/commentSync">${syncLabel}</a>
                </div>
            </li>
        </#if>
        <#if b3logEnabled>
            <li>
                <div id="tabOthers_move">
                    <a href="#tools/others/move">${picBedMigrationLabel}</a>
                </div>
            </li>
        </#if>
    </ul>
</div>
<div id="tabOthersPanel" class="sub-tabs-main">
    <div id="tabOthersPanel_tag" class="fn__none">
        <button class="fn__margin12" onclick="admin.others.removeUnusedTags();">${removeUnusedTagsLabel}</button>
        <button class="fn__margin12" onclick="admin.others.removeUnusedArchives();">${removeUnusedArchivesLabel}</button>
    </div>
    <div id="tabOthersPanel_media" class="fn__none form">
        <section class="console-utility-section">
            <h3>本地图床媒体库</h3>
            <p class="console-form-help" id="mediaLibrarySummary">浏览本地图床目录中的已上传图片，一键复制 Markdown 引用，或全表扫描清理孤儿图片。</p>
            <div class="console-inline-bar">
                <input id="mediaSearchInput" type="search" placeholder="按文件名过滤..." aria-label="按文件名过滤" oninput="admin.others.loadMediaLibrary()"/>
                <button type="button" onclick="admin.others.loadMediaLibrary()">刷新列表</button>
                <button type="button" onclick="admin.others.scanOrphanMedia(this)">扫描未引用孤儿图片</button>
                <button id="deleteAllOrphansBtn" type="button" class="fn__none" onclick="admin.others.deleteAllOrphans(this)">一键清理全部孤儿图片</button>
            </div>
            <div id="mediaLibraryGrid" class="console-media-grid"></div>
        </section>
    </div>
    <div id="tabOthersPanel_backups" class="fn__none form">
        <section class="console-utility-section">
            <h3>定时本地滚动备份</h3>
            <p class="console-form-help">按日自动归档整站 JSON 数据与全部 Markdown 文稿（含 Front Matter），自动清理超出保留份数的历史归档。</p>
            <div class="console-form-grid console-form-grid--3">
                <div class="console-form-cell">
                    <label for="localBackupKeepCountInput">最多保留历史份数（1~90）</label>
                    <input id="localBackupKeepCountInput" type="number" min="1" max="90" value="7"/>
                </div>
                <div class="console-form-cell">
                    <label for="localBackupDirInput">归档存储目录</label>
                    <input id="localBackupDirInput" type="text" value="backups" placeholder="相对或绝对路径，默认 backups"/>
                </div>
                <div class="console-form-cell">
                    <label class="checkbox" for="localBackupAutoInput">
                        <input id="localBackupAutoInput" type="checkbox"/>
                        <span>启用每日自动滚动备份</span>
                    </label>
                </div>
            </div>
            <div class="console-inline-bar">
                <button type="button" onclick="admin.others.saveLocalBackupConfig(this)">保存备份策略</button>
                <button type="button" onclick="admin.others.runLocalBackupNow(this)">立即生成一份归档</button>
                <button type="button" onclick="admin.others.loadLocalBackups()">刷新归档列表</button>
            </div>
            <div class="console-simple-table-wrap"><table id="localBackupsTable" class="console-simple-table"></table></div>
            <div class="console-subcard console-subcard--surface" style="margin-top: 16px;">
                <div class="console-section-head">
                    <div class="console-section-head__copy">
                        <h4>备份校验与一键整站恢复</h4>
                        <p>可在上方列表点击“预览恢复”，或上传外部备份 <code>.zip</code>。执行前会自动创建一份 <code>pre-restore</code> 本地快照；默认安全恢复模式保留当前管理员密码、TOTP 与通行密钥。</p>
                    </div>
                </div>
                <div class="console-inline-bar">
                    <input id="restoreZipUploadInput" type="file" accept=".zip"/>
                    <button type="button" onclick="admin.others.inspectUploadedBackup(this)">解析外部备份 ZIP</button>
                </div>
                <div id="backupRestoreConfirmBox" class="console-restore-summary" style="display: none;">
                    <div id="backupRestoreSummaryMeta" class="console-restore-summary__meta"></div>
                    <div class="console-checkbox-group" style="margin: 10px 0;">
                        <label class="checkbox" for="backupRestoreOverwriteAuth">
                            <input id="backupRestoreOverwriteAuth" type="checkbox"/>
                            <span>强制使用归档内的管理员账号密码、TOTP 与通行密钥覆盖当前凭据（勾选后需用备份内凭据重新登录）</span>
                        </label>
                    </div>
                    <div class="console-inline-bar" style="margin: 0;">
                        <button type="button" id="confirmBackupRestoreBtn" onclick="admin.others.executeBackupRestore(this)">确认执行整站恢复</button>
                        <button type="button" onclick="admin.others.cancelBackupRestore()">取消</button>
                    </div>
                </div>
            </div>
            <div class="console-subcard console-subcard--surface">
                <div class="console-section-head">
                    <div class="console-section-head__copy">
                        <h4>异地备份自动同步（WebDAV / S3 兼容存储）</h4>
                        <p>每日滚动归档或手动备份完成后，自动异步上传至坚果云 / Nextcloud / OpenList（WebDAV）或 Cloudflare R2 / MinIO / AWS S3。</p>
                    </div>
                </div>
                <div class="console-form-grid">
                    <div class="console-form-cell">
                        <label for="remoteBackupEnabled">自动异地同步</label>
                        <select id="remoteBackupEnabled">
                            <option value="false">关闭（默认）</option>
                            <option value="true">开启归档后自动同步</option>
                        </select>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteBackupProtocol">存储协议</label>
                        <select id="remoteBackupProtocol" onchange="admin.others.onRemoteProtocolChange()">
                            <option value="webdav">WebDAV（坚果云 / Nextcloud / OpenList）</option>
                            <option value="s3">S3 兼容存储（Cloudflare R2 / MinIO / AWS S3）</option>
                        </select>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteBackupKeepCount">远端保留份数（1~90）</label>
                        <input id="remoteBackupKeepCount" type="number" min="1" max="90" value="14"/>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteBackupPrefix">远端目录 / 对象前缀</label>
                        <input id="remoteBackupPrefix" type="text" value="volo-backups" placeholder="例如 volo-backups"/>
                    </div>
                </div>
                <div id="remoteWebdavFields" class="console-form-grid">
                    <div class="console-form-cell console-form-cell--full">
                        <label for="remoteWebdavUrl">WebDAV 服务地址（仅限公网 HTTPS/HTTP）</label>
                        <input id="remoteWebdavUrl" type="url" placeholder="https://dav.jianguoyun.com/dav/"/>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteWebdavUsername">WebDAV 用户名</label>
                        <input id="remoteWebdavUsername" type="text" autocomplete="off" placeholder="账号或邮箱"/>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteWebdavPassword">WebDAV 应用密码</label>
                        <input id="remoteWebdavPassword" type="password" autocomplete="new-password" placeholder="留空则保留已保存密码"/>
                    </div>
                </div>
                <div id="remoteS3Fields" class="console-form-grid" style="display: none;">
                    <div class="console-form-cell">
                        <label for="remoteS3Endpoint">S3 Endpoint 地址</label>
                        <input id="remoteS3Endpoint" type="url" placeholder="https://<accountid>.r2.cloudflarestorage.com"/>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteS3Bucket">Bucket 桶名称</label>
                        <input id="remoteS3Bucket" type="text" placeholder="my-blog-backups"/>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteS3Region">Region 区域</label>
                        <input id="remoteS3Region" type="text" value="auto" placeholder="auto 或 us-east-1"/>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteS3PathStyle">寻址风格</label>
                        <select id="remoteS3PathStyle">
                            <option value="false">Virtual-Hosted 虚拟主机风格（默认）</option>
                            <option value="true">Path-Style 路径风格（MinIO 推荐）</option>
                        </select>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteS3AccessKey">AccessKey ID</label>
                        <input id="remoteS3AccessKey" type="text" autocomplete="off"/>
                    </div>
                    <div class="console-form-cell">
                        <label for="remoteS3SecretKey">SecretAccessKey</label>
                        <input id="remoteS3SecretKey" type="password" autocomplete="new-password" placeholder="留空则保留已保存密钥"/>
                    </div>
                </div>
                <div class="console-inline-bar" style="margin-bottom: 0;">
                    <button type="button" onclick="admin.others.saveRemoteBackupConfig(this)">保存异地同步配置</button>
                    <button type="button" onclick="admin.others.testRemoteBackup(this)">测试远端连通性</button>
                    <button type="button" onclick="admin.others.syncRemoteBackupNow(this)">立即同步最新归档</button>
                </div>
            </div>
        </section>
    </div>
    <div id="tabOthersPanel_webhooks" class="fn__none form">
        <section class="console-utility-section">
            <h3>通用 Webhook 消息推送</h3>
            <p class="console-form-help">支持飞书、企业微信、钉钉（支持加签）、Bark、Telegram 与自定义 JSON（含 HMAC-SHA256 签名头），内置公网地址校验防止 SSRF，已保存密钥全程脱敏。</p>
            <input id="webhookEditId" type="hidden" value=""/>
            <div class="console-form-grid">
                <div class="console-form-cell">
                    <label for="webhookName">通道名称</label>
                    <input id="webhookName" type="text" placeholder="例如：运维飞书群"/>
                </div>
                <div class="console-form-cell">
                    <label for="webhookProvider">平台类型</label>
                    <select id="webhookProvider">
                        <option value="feishu">飞书机器人 (Feishu)</option>
                        <option value="wecom">企业微信机器人 (WeCom)</option>
                        <option value="dingtalk">钉钉机器人 (DingTalk)</option>
                        <option value="bark">Bark (iOS 推送)</option>
                        <option value="telegram">Telegram Bot</option>
                        <option value="custom">自定义 JSON (X-Volo-Signature)</option>
                    </select>
                </div>
                <div class="console-form-cell console-form-cell--full">
                    <label for="webhookUrl">Webhook URL（仅限公网 HTTP/HTTPS）</label>
                    <input id="webhookUrl" type="url" placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/..."/>
                </div>
                <div class="console-form-cell">
                    <label for="webhookSecret">签名密钥 / Secret</label>
                    <input id="webhookSecret" type="password" autocomplete="new-password" placeholder="可选加签密钥（编辑时留空则保持原密钥不变）"/>
                </div>
                <div class="console-form-cell">
                    <label for="webhookChatId">Telegram Chat ID</label>
                    <input id="webhookChatId" type="text" placeholder="仅 Telegram 需要，例如：-100123456789"/>
                </div>
            </div>
            <div class="console-checkbox-group">
                <label class="checkbox" for="webhookEnabled"><input id="webhookEnabled" type="checkbox" checked="checked"/><span>启用该通道</span></label>
                <label class="checkbox"><input type="checkbox" name="webhookEvent" value="comment" checked="checked"/><span>新评论通知</span></label>
                <label class="checkbox"><input type="checkbox" name="webhookEvent" value="moderation" checked="checked"/><span>待审评论提醒</span></label>
                <label class="checkbox"><input type="checkbox" name="webhookEvent" value="register" checked="checked"/><span>新读者注册</span></label>
                <label class="checkbox"><input type="checkbox" name="webhookEvent" value="security" checked="checked"/><span>安全封禁告警</span></label>
                <label class="checkbox"><input type="checkbox" name="webhookEvent" value="backup" checked="checked"/><span>自动备份完成</span></label>
            </div>
            <div class="console-inline-bar">
                <button type="button" onclick="admin.others.saveWebhook(this)">保存 Webhook 通道</button>
                <button type="button" onclick="admin.others.resetWebhookForm()">清空表单</button>
            </div>
            <div class="console-simple-table-wrap"><table id="webhooksTable" class="console-simple-table"></table></div>
        </section>
    </div>
    <div id="tabOthersPanel_data" class="fn__none">
        <#if supportExport>
        <button class="fn__margin12" onclick="admin.others.exportSQL();">${exportSQLLabel}</button>
        </#if>
        <button class="fn__margin12" onclick="admin.others.exportJSON();">${exportJSONLabel}</button>
        <button class="fn__margin12" onclick="admin.others.exportHexo();">${exportHexoLabel}</button>
    </div>
    <div id="tabOthersPanel_maintenance" class="fn__none">
        <section class="console-utility-section"><h3>远程备份</h3><div class="console-utility-action"><p>将公开文章备份至 GitHub。使用偏好设定中的 GitHub Token，与“我的 GitHub”公开仓库展示分别管理。</p><button type="button" onclick="admin.others.maintenance(this, '/prop/backup/github/do/upload')">备份至 GitHub</button></div>
        <#if b3logEnabled><div class="console-utility-action"><p>将公开文章备份至链滴，需已配置链滴用户名和 B3log Key。</p><button type="button" onclick="admin.others.maintenance(this, '/prop/backup/hacpai/do/upload')">备份至链滴</button></div><div class="console-utility-action"><p>重新获取链滴社区的站点数据，使用当前的链滴账号配置。</p><button type="button" onclick="admin.others.maintenance(this, '/admin/usite/refresh')">获取站点数据</button></div></#if>
        </section>
        <section class="console-utility-section"><h3>邮件提醒数据</h3><div class="console-utility-action"><p>清除旧评论关联的邮箱记录后，这些评论将无法再收到邮件提醒。此操作不会删除评论，请确认不再需要旧通知记录后执行。</p><button type="button" onclick="admin.others.clearMailCache(this)">清除邮箱缓存</button></div></section>
    </div>
    <div id="tabOthersPanel_move" class="fn__none">
        ${others1Label}
    </div>
    <script>
        $(function () {
            function refreshMigrationStatus() {
                if (!window.location.hash.startsWith('#tools/others/move')) return;
                $.ajax({
                    url: Label.servePath + "/PBC/status",
                    type: "GET",
                    cache: false,
                    success: function (result, textStatus) {
                        $("#status").text(result.msg);
                    }
                });
            }
            refreshMigrationStatus();
            const migrationStatusTimer = setInterval(refreshMigrationStatus, 10000);
            window.addEventListener('hashchange', refreshMigrationStatus);
            if (window.BoloNavigation) BoloNavigation.onLeave(function () {
                clearInterval(migrationStatusTimer);
                window.removeEventListener('hashchange', refreshMigrationStatus);
            });
        });
    </script>
    <div id="tabOthersPanel_log" class="fn__none form">
        <div class="console-log-summary">
            <span>${others2Label} <strong id="memFree"></strong></span>
            <div class="console-log-actions"><span><span id="now"></span> ${others3Label}</span><button type="button" onclick="admin.others.getLog(true)">${RefreshLabel}</button></div>
        </div>
        <p class="console-form-help" id="logRetention"></p>
        <div class="console-log-output"><table id="logList"></table></div>
        <div id="logPagination"></div>
    </div>
    <div id="tabOthersPanel_import" class="fn__none form">
        <section class="console-utility-section">
            <h3>批量拖拽导入 Markdown / ZIP</h3>
            <p class="console-form-help">支持一次拖入或选择多个 <code>.md</code> 文稿与 <code>.zip</code> 压缩包，自动解析 YAML Front Matter（标题、标签、日期、固定链接、摘要与密码）。</p>
            <div id="batchImportDropzone" class="console-dropzone" tabindex="0" role="button"
                 onclick="document.getElementById('batchImportFileInput').click()"
                 onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();document.getElementById('batchImportFileInput').click();}">
                <div class="console-dropzone__title">拖拽多个 .md 或 .zip 文件到此处，或点击选择文件</div>
                <div class="console-dropzone__hint">单次最多上传 100 篇文稿，自动防重并生成唯一归档与分类关联</div>
                <input id="batchImportFileInput" type="file" multiple accept=".md,.markdown,.zip" style="display: none;" onchange="admin.others.onBatchFilesSelected(this.files)"/>
            </div>
            <div id="batchImportFileSummary" class="console-form-help" style="display: none;"></div>
            <div class="console-form-grid">
                <div class="console-form-cell">
                    <label for="batchImportPublishStatus">导入后发布状态</label>
                    <select id="batchImportPublishStatus">
                        <option value="published">直接公开为已发布文章</option>
                        <option value="draft">存入草稿箱待审</option>
                    </select>
                </div>
                <div class="console-form-cell">
                    <label for="batchImportDuplicateMode">同名或同链接冲突策略</label>
                    <select id="batchImportDuplicateMode">
                        <option value="skip">跳过已存在的同名/同链接文章（推荐）</option>
                        <option value="copy">保留副本并自动生成新链接</option>
                    </select>
                </div>
            </div>
            <div class="console-inline-bar">
                <button type="button" id="batchImportSubmitBtn" onclick="admin.others.submitBatchImport(this)">开始批量导入</button>
                <button type="button" onclick="admin.others.clearBatchImport()">清空已选文件</button>
            </div>
            <div id="batchImportResultBox" class="console-subcard" style="display: none;"></div>
        </section>
        <section class="console-utility-section">
            <h3>${others4Label}（兼容模式）</h3>
            <p class="console-form-help">${others5Label}</p>
            <form id="fileUploadForm" enctype="multipart/form-data" style="margin-bottom: 12px;">
                <input name="file" type="file" id="backupUpload" accept=".xml,.zip,.dat">
            </form>
            <div class="console-inline-bar">${others8Label}</div>
        </section>
        <script type="text/javascript">
            function uploadFile(name) {
                if (uploadFile.pending) return;
                if ($("#backupUpload").val() !== "") {
                    const button = $("#" + name), original = button.text();
                    const buttons = $("#cnblogs, #markdown");
                    uploadFile.pending = true;
                    buttons.prop("disabled", true);
                    button.text("${others6Label?js_string}");
                    let formData = new FormData($("#fileUploadForm")[0]);
                    let options = {
                        url: Label.servePath + "/import/" + name,
                        type: 'POST',
                        data: formData,
                        dataType: 'json',
                        cache: false,
                        processData: false,
                        contentType: false,
                        success: function (result) {
                            noBtnSwal(result.msg);
                        },
                        error: function (xhr) {
                            noBtnSwal(xhr.responseJSON && xhr.responseJSON.msg || xhr.status + ": " + xhr.statusText);
                        },
                        complete: function () {
                            uploadFile.pending = false;
                            buttons.prop("disabled", false);
                            button.text(original);
                        }
                    };
                    $.ajax(options);
                } else {
                    noBtnSwal("${others7Label?js_string}");
                }
            }

            $("#cnblogs").click(function () {
                uploadFile("cnblogs");
            });


            $("#markdown").click(function () {
                uploadFile("markdown");
            });
        </script>

    </div>
    <div id="tabOthersPanel_commentSync" class="fn__none form">
        <script type="text/javascript">
            $.ajax({
                url: Label.servePath + '/article/commentSync/getList',
                type: 'GET',
                success: function(res) {
                    let data = res.data || [];
                    for(let i = 0; i < data.length; i++) {
                        let oId = data[i].oId;
                        let title = data[i].title;
                        <#if b3logEnabled>
                        $("#localArticleList").append($("<option>").val(oId).text(title));
                        </#if>
                        <#if fishpiEnabled>
                        $("#localArticleList2").append($("<option>").val(oId).text(title));
                         </#if>
                    }
                }
            });
            function requestCommentSync(button, url, payload) {
                if (button.disabled) return;
                if (!/^\d+$/.test(payload.localArticleId || '') || !/^\d+$/.test(payload.remoteArticleId || '')
                        || (payload.symphony !== undefined && !payload.symphony.trim())) {
                    noBtnSwal("${syncFieldsRequiredLabel?js_string}");
                    return;
                }
                button.disabled = true;
                const original = button.textContent;
                button.textContent = "${loadingLabel?js_string}";
                $.ajax({
                    url: Label.servePath + url,
                    type: 'POST',
                    contentType: 'application/json',
                    dataType: 'json',
                    data: JSON.stringify(payload),
                    success: function (res) { noBtnSwal(res.msg); },
                    error: function (xhr) { noBtnSwal(xhr.responseJSON && xhr.responseJSON.msg || xhr.status + ": " + xhr.statusText); },
                    complete: function () { button.disabled = false; button.textContent = original; }
                });
            }
            <#if b3logEnabled>
            function commentSync(button) {
                requestCommentSync(button, '/article/commentSync', {
                    localArticleId: $("#localArticleList").val(),
                    remoteArticleId: $("#remoteArticleID").val(),
                    symphony: $("#symphony").val()
                });
            }
            </#if>
            <#if fishpiEnabled>
            function commentFishpiSync(button) {
                requestCommentSync(button, '/article/fishpi/commentSync', {
                    localArticleId: $("#localArticleList2").val(),
                    remoteArticleId: $("#remoteArticleID2").val()
                });
            }
            </#if>
        </script>
        <#if b3logEnabled>
            <section class="console-sync-section">
                <div class="console-sync-help">${others9Label}</div>
                <div class="console-sync-field">
                    <label for="remoteArticleID">${remoteArticleIdLabel!'远端文章编号'}</label>
                    <div class="console-sync-remote"><span>https://${hacpaiDomain}/article/</span><input id="remoteArticleID" type="text" inputmode="numeric"></div>
                </div>
                <div class="console-sync-field"><label for="localArticleList">${others11Label}</label><select id="localArticleList"></select></div>
                <div class="console-sync-help" id="symphonyHelp">${others13Label}</div>
                <div class="console-sync-row">
                    <div class="console-sync-field"><label for="symphony">${others12Label}</label><input id="symphony" type="password" autocomplete="off" aria-describedby="symphonyHelp"></div>
                    <button id="syncB3log" onclick="commentSync(this)">${others14Label}</button>
                </div>
            </section>
        </#if>
        <#if fishpiEnabled>
            <section class="console-sync-section">
                <div class="console-sync-help">${others91Label}</div>
                <div class="console-sync-field">
                    <label for="remoteArticleID2">${remoteArticleIdLabel!'远端文章编号'}</label>
                    <div class="console-sync-remote"><span>https://${fishpiDomain}/article/</span><input id="remoteArticleID2" type="text" inputmode="numeric"></div>
                </div>
                <div class="console-sync-row">
                    <div class="console-sync-field"><label for="localArticleList2">${others11Label}</label><select id="localArticleList2"></select></div>
                    <button id="syncFishpi" onclick="commentFishpiSync(this)">${others14Label}</button>
                </div>
            </section>
        </#if>

    </div>
</div>
${plugins}