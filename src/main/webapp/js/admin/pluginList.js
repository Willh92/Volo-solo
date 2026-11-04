/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/**
 * plugin list for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* plugin-list 相关操作 */
admin.pluginList = {
    tablePagination: new TablePaginate("plugin"),
    pageInfo: {
        currentCount: 1,
        pageCount: 1,
        currentPage: 1
    },
    /* 
     * 初始化 table, pagination
     */
    init: function(page) {
        this.tablePagination.buildTable([{
                style: "padding-left: 12px;",
                text: Label.pluginNameLabel,
                index: "name",
                width: 230
            }, {
                style: "padding-left: 12px;",
                text: Label.statusLabel,
                index: "status",
                width: 100
            }, {
                style: "padding-left: 12px;",
                text: Label.authorLabel,
                index: "author",
                minWidth: 240
            }, {
                style: "padding-left: 12px;",
                text: Label.versionLabel,
                index: "version",
                width: 80
            }]);

        this.tablePagination.initPagination();
        $("#pluginSetting").dialog({
            title: Label.settingLabel,
            width: 700,
            height: 180,
            "modal": true,
            "hideFooter": true
        });
        this.getList(page);
    },
    /* 
     * 根据当前页码获取列表
     * @pagNum 当前页码
     */
    getList: function(pageNum) {
        $("#loadMsg").text(Label.loadingLabel);
        $("#tipMsg").text("");
        var that = this;

        $.ajax({
            url: Label.servePath + "/console/plugins/" + pageNum + "/" + Label.PAGE_SIZE + "/" + Label.WINDOW_SIZE,
            type: "GET",
            cache: false,
            success: function(result, textStatus) {
                $("#tipMsg").text(result.msg);
                if (!result.sc) {
                    $("#loadMsg").text("");
                    return;
                }

                var escapeHtml = (typeof Util !== 'undefined' && Util.escapeHtml) || function (v) {
                    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
                        return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
                    });
                };
                var encodeQuote = (typeof Util !== 'undefined' && Util.encodeSingleQuote) || function (v) {
                    return encodeURIComponent(String(v == null ? '' : v)).replace(/'/g, '%27');
                };
                admin.pluginList.pageInfo.currentPage = pageNum;
                var datas = result.plugins, rows = [];
                for (var i = 0; i < datas.length; i++) {
                    var safeId = encodeQuote(datas[i].oId);
                    var safeStatus = encodeQuote(datas[i].status);
                    var row = {
                        name: escapeHtml(datas[i].name),
                        author: escapeHtml(datas[i].author),
                        version: escapeHtml(datas[i].version)
                    };
                    row.expendRow = "<a href='javascript:void(0)' onclick=\"admin.pluginList.changeStatus('" +
                            safeId + "', '" + safeStatus + "')\">";
                    if (datas[i].status === "ENABLED") {
                        row.status = Label.enabledLabel;
                        row.expendRow += Label.disableLabel;
                    } else {
                        row.status = Label.disabledLabel;
                        row.expendRow += Label.enableLabel;
                    }
                    row.expendRow += "</a>  ";

                    if (datas[i].setting != "{}") {
                        row.expendRow += "<a href='javascript:void(0)' onclick=\"admin.pluginList.toSetting('" + safeId + "')\"> " + Label.settingLabel + " </a>  ";
                    }
                    rows.push(row);
                }

                that.tablePagination.updateTablePagination(rows, pageNum, result.pagination);

                $("#loadMsg").text("");
            }
        });
    },
    toSetting: function(pluginId) {
        $("#loadMsg").text(Label.loadingLabel);
        $("#tipMsg").text("");
        var requestJSONObject = {
            "oId": pluginId
        };

        $.ajax({
            url: Label.servePath + "/console/plugin/toSetting",
            type: "POST",
            cache: false,
            data: JSON.stringify(requestJSONObject),
            success: function(result, textStatus) {
                $("#tipMsg").text(result.msg);

                $("#pluginSetting").html(result);
                $("#pluginSetting").dialog("open");

                $("#loadMsg").text("");
            }
        });
    },
    changeStatus: function(pluginId, status) {
        $("#loadMsg").text(Label.loadingLabel);
        $("#tipMsg").text("");
        if (status === "ENABLED") {
            status = "DISABLED";
        } else {
            status = "ENABLED";
        }

        var requestJSONObject = {
            "oId": pluginId,
            "status": status
        };

        $.ajax({
            url: Label.servePath + "/console/plugin/status/",
            type: "PUT",
            cache: false,
            data: JSON.stringify(requestJSONObject),
            success: function(result, textStatus) {
                $("#tipMsg").text(result.msg);
                if (!result.sc) {
                    $("#loadMsg").text("");
                    return;
                }

                $("#loadMsg").text("");
                window.location.reload();
            }
        });
    }
};

/*
 * 注册到 admin 进行管理 
 */
admin.register["plugin-list"] = {
    "obj": admin.pluginList,
    "init": admin.pluginList.init,
    "refresh": function() {
        $("#loadMsg").text("");
    }
};
