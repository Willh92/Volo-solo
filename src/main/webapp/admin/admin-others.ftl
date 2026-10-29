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
    <div id="tabOthersPanel_data" class="fn__none">
        <#if supportExport>
        <button class="fn__margin12" onclick="admin.others.exportSQL();">${exportSQLLabel}</button>
        </#if>
        <button class="fn__margin12" onclick="admin.others.exportJSON();">${exportJSONLabel}</button>
        <button class="fn__margin12" onclick="admin.others.exportHexo();">${exportHexoLabel}</button>
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
            <div class="console-log-actions"><span><span id="now"></span> ${others3Label}</span><button onclick="admin.others.getLog()">${RefreshLabel}</button></div>
        </div>
        <div class="console-log-output"><table id="logList"></table></div>
    </div>
    <div id="tabOthersPanel_import" class="fn__none">
        <h3>${others4Label}</h3>
        <br>
        <p><b>${others5Label}</b></p>
        <br>
        <form id="fileUploadForm" enctype="multipart/form-data">
            <input name="file" type="file" id="backupUpload" accept=".xml,.zip,.dat">
        </form>
        <br>
        ${others8Label}
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
