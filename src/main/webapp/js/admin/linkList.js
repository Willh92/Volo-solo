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
 * link list for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* link-list 相关操作 */
admin.linkList = {
    tablePagination:  new TablePaginate("link"),
    pageInfo: {
        currentCount: 1,
        pageCount: 1,
        currentPage: 1
    },
    id: "",
    /*
     * 初始化 table, pagination
     */
    init: function (page) {
        this.tablePagination.buildTable([{
            text: "",
            index: "linkOrder",
            width: 68
        },{
            text: Label.linkTitleLabel,
            index: "linkTitle",
            width: 156
        }, {
            text: Label.urlLabel,
            index: "linkAddress",
            minWidth: 180
        }, {
            text: "存活状态",
            index: "linkHealthBadge",
            width: 150
        }, {
            text: Label.linkDescriptionLabel,
            index: "linkDescription",
            width: 140
        }]);
    
        this.tablePagination.initPagination();
        this.getList(page);
        
        $("#updateLink").dialog({
            title:  $("#updateLink").data('title'),
            width: 700,
            height: 350,
            "modal": true,
            "hideFooter": true
        });
    },

    /* 
     * 根据当前页码获取链接列表
     * 
     * @pagNum 当前页码
     */
    getList: function (pageNum) {
        $("#loadMsg").text(Label.loadingLabel);
        if (pageNum === 0) {
            pageNum = 1;
        }
        this.pageInfo.currentPage = pageNum;
        var that = this;
        
        $.ajax({
            url: Label.servePath + "/console/links/" + pageNum + "/" + Label.PAGE_SIZE + "/" +  Label.WINDOW_SIZE + this.tablePagination.keywordQuery(),
            type: "GET",
            cache: false,
            success: function(result, textStatus){
                $("#tipMsg").text(result.msg);
                if (!result.sc) {
                    $("#loadMsg").text("");
                    return;
                }
                
                var links = result.links;
                var linkData = [];
                admin.linkList.pageInfo.currentCount = links.length;
                admin.linkList.pageInfo.pageCount = result.pagination.paginationPageCount === 0 ? 1 : result.pagination.paginationPageCount;

                var escapeHtml = (typeof Util !== 'undefined' && Util.escapeHtml) || function (v) {
                    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
                        return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
                    });
                };
                var encodeQuote = (typeof Util !== 'undefined' && Util.encodeSingleQuote) || function (v) {
                    return encodeURIComponent(String(v == null ? '' : v)).replace(/'/g, '%27');
                };
                for (var i = 0; i < links.length; i++) {
                    var safeId = encodeQuote(links[i].oId);
                    var encodedTitle = encodeQuote(links[i].linkTitle);
                    var rawAddr = String(links[i].linkAddress == null ? '' : links[i].linkAddress).trim();
                    var safeAddrText = escapeHtml(rawAddr);
                    var safeHref = /^https?:\/\//i.test(rawAddr) ? safeAddrText : '#';
                    linkData[i] = {};
                    if (i === 0) {
                        if (links.length === 1) {
                            linkData[i].linkOrder = "";
                        } else {
                            linkData[i].linkOrder = '<div class="table-center" style="width:14px">\
                                <span onclick="admin.linkList.changeOrder(\'' + safeId + '\', ' + i + ', \'down\');" class="icon-move-down"></span>\
                            </div>';
                        }
                    } else if (i === links.length - 1) {
                        linkData[i].linkOrder = '<div class="table-center" style="width:14px">\
                                <span onclick="admin.linkList.changeOrder(\'' + safeId + '\', ' + i + ', \'up\');" class="icon-move-up"></span>\
                            </div>';
                    } else {
                        linkData[i].linkOrder = '<div class="table-center" style="width:38px">\
                                <span onclick="admin.linkList.changeOrder(\'' + safeId + '\', ' + i + ', \'up\');" class="icon-move-up"></span>\
                                <span onclick="admin.linkList.changeOrder(\'' + safeId + '\', ' + i + ', \'down\');" class="icon-move-down"></span>\
                            </div>';
                    }
                    
                    linkData[i].linkTitle = escapeHtml(links[i].linkTitle);
                    linkData[i].linkAddress = "<a target='_blank' rel='noopener noreferrer' class='no-underline' href='" + safeHref + "'>"
                    + safeAddrText + "</a>";
                    var h = links[i].linkHealth;
                    if (!h) {
                        linkData[i].linkHealthBadge = '<span style="color:#888;font-size:12px;">未巡检</span>';
                    } else if (h.ok) {
                        linkData[i].linkHealthBadge = '<span style="color:#1b7f4b;font-weight:600;font-size:12px;">● 正常 (' + Number(h.status || 200) + ' · ' + Number(h.latencyMs || 0) + 'ms)</span>';
                    } else {
                        var errTxt = escapeHtml(h.error || ('HTTP ' + Number(h.status || 0)));
                        linkData[i].linkHealthBadge = '<span style="color:#b42318;font-weight:600;font-size:12px;" title="' + errTxt + '">● 异常 (' + errTxt + ')</span>';
                    }
                    linkData[i].linkDescription = escapeHtml(links[i].linkDescription);
                    linkData[i].linkIcon = escapeHtml(links[i].linkIcon);
                    linkData[i].expendRow = "<span><a href='" + safeHref + "' target='_blank' rel='noopener noreferrer'>" + Label.viewLabel + "</a>  \
                                <a href='javascript:void(0)' onclick=\"admin.linkList.get('" + safeId + "')\">" + Label.updateLabel + "</a>\
                                <a href='javascript:void(0)' onclick=\"admin.linkList.del('" + safeId + "', '" + encodedTitle + "')\">" + Label.removeLabel + "</a></span>";
                }

                that.tablePagination.updateTablePagination(linkData, pageNum, result.pagination);
                
                $("#loadMsg").text("");
            }
        });
    },
    
    /*
     * 添加链接
     */
    add: function () {
        if (this.validate()) {
            $("#loadMsg").text(Label.loadingLabel);
            $("#tipMsg").text("");
            var requestJSONObject = {
                "link": {
                    "linkTitle": $("#linkTitle").val(),
                    "linkAddress": $("#linkAddress").val(),
                    "linkDescription": $("#linkDescription").val(),
                    "linkIcon": $("#linkIcon").val()
                }
            };
            
            $.ajax({
                url: Label.servePath + "/console/link/",
                type: "POST",
                cache: false,
                data: JSON.stringify(requestJSONObject),
                success: function(result, textStatus){
                    $("#tipMsg").text(result.msg);
                    if (!result.sc) {
                        $("#loadMsg").text("");
                        return;
                    }
                    
                    $("#linkTitle").val("");
                    $("#linkAddress").val("");
                    $("#linkDescription").val("");
                    $("#linkIcon").val("");
                    if (admin.linkList.pageInfo.currentCount === Label.PAGE_SIZE &&
                        admin.linkList.pageInfo.currentPage === admin.linkList.pageInfo.pageCount) {
                        admin.linkList.pageInfo.pageCount++;
                    }
                    var hashList = window.location.hash.split("/");
                    if (admin.linkList.pageInfo.pageCount !== parseInt(hashList[hashList.length - 1])) {
                        admin.setHashByPage(admin.linkList.pageInfo.pageCount);
                    }
                    
                    admin.linkList.getList(admin.linkList.pageInfo.pageCount);
                    
                    $("#loadMsg").text("");
                }
            });
        }
    },
    
    /*
     * 获取链接
     * @id 链接 id
     */
    get: function (id) {
        $("#loadMsg").text(Label.loadingLabel);
        this.id = "";
        $("#linkTitleUpdate,#linkAddressUpdate,#linkDescriptionUpdate,#linkIconUpdate").val("");
        $("#updateLink").dialog("open");
        
        $.ajax({
            url: Label.servePath + "/console/link/" + id,
            type: "GET",
            cache: false,
            success: function(result, textStatus){
                $("#tipMsg").text(result.msg);
                if (!result.sc) {
                    $("#loadMsg").text("");
                    return;
                }
                
                admin.linkList.id = id;
                
                $("#linkTitleUpdate").val(result.link.linkTitle);
                $("#linkAddressUpdate").val(result.link.linkAddress);
                $("#linkDescriptionUpdate").val(result.link.linkDescription);
                $("#linkIconUpdate").val(result.link.linkIcon);

                $("#loadMsg").text("");
            }
        });
    },
    
    /*
     * 更新链接
     */
    update: function () {
        if (!this.id) {
            return;
        }
        if (this.validate("Update")) {
            $("#loadMsg").text(Label.loadingLabel);
            $("#tipMsg").text("");
            var requestJSONObject = {
                "link": {
                    "linkTitle": $("#linkTitleUpdate").val(),
                    "oId": this.id,
                    "linkAddress": $("#linkAddressUpdate").val(),
                    "linkDescription": $("#linkDescriptionUpdate").val(),
                    "linkIcon": $("#linkIconUpdate").val()
                }
            };
            
            $.ajax({
                url: Label.servePath + "/console/link/",
                type: "PUT",
                cache: false,
                data: JSON.stringify(requestJSONObject),
                success: function(result, textStatus){
                    $("#updateLink").dialog("close");
                    $("#tipMsg").text(result.msg);
                    if (!result.sc) {
                        $("#loadMsg").text("");
                        return;
                    }
                    
                    admin.linkList.getList(admin.linkList.pageInfo.currentPage);
                    
                    $("#loadMsg").text("");
                }
            });
        }
    },
    
    /*
     * 删除链接
     * @id 链接 id
     * @title 链接标题
     */
    del: function (id, title) {
        var isDelete = confirm(Label.confirmRemoveLabel + (Label.linkTitleLabel || '链接') + '"' + Util.htmlDecode(title) + '"?');
        if (isDelete) {
            $("#loadMsg").text(Label.loadingLabel);
            $("#tipMsg").text("");
            
            $.ajax({
                url: Label.servePath + "/console/link/" + id,
                type: "DELETE",
                cache: false,
                success: function(result, textStatus){
                    $("#tipMsg").text(result.msg);
                    if (!result.sc) {
                        $("#loadMsg").text("");
                        return;
                    }
                    
                    var pageNum = admin.linkList.pageInfo.currentPage;
                    if (admin.linkList.pageInfo.currentCount === 1 && admin.linkList.pageInfo.pageCount !== 1 &&
                        admin.linkList.pageInfo.currentPage === admin.linkList.pageInfo.pageCount) {
                        admin.linkList.pageInfo.pageCount--;
                        pageNum = admin.linkList.pageInfo.pageCount;
                    }
                    
                    var hashList = window.location.hash.split("/");
                    if (pageNum !== parseInt(hashList[hashList.length - 1])) {
                        admin.setHashByPage(pageNum);
                    }
                    
                    admin.linkList.getList(pageNum);
                    
                    $("#loadMsg").text("");
                }
            });
        }
    },
    
    /*
     * 验证字段
     * @status 更新或者添加时进行验证
     */
    validate: function (status) {
        if (!status) {
            status = "";
        }
        if ($("#linkTitle" + status).val().replace(/\s/g, "") === "") {
            $("#tipMsg").text(Label.titleEmptyLabel);
            $("#linkTitle" + status).focus().val("");
        } else if ($("#linkAddress" + status).val().replace(/\s/g, "") === "") {
            $("#tipMsg").text(Label.addressEmptyLabel);
            $("#linkAddress" + status).focus().val("");
        } else if (!/^https?:\/\//i.test($("#linkAddress" + status).val().trim())) {
            $("#tipMsg").text(Label.addressInvalidLabel);
            $("#linkAddress" + status).focus().val("");
        } else {
            return true;
        }
        return false;
    },
    
    /*
     * 调换顺序
     */
    changeOrder: function (id, order, status) {
        $("#loadMsg").text(Label.loadingLabel);
        $("#tipMsg").text("");
        
        var requestJSONObject = {
            "oId": id.toString(),
            "direction": status
        };
        
        $.ajax({
            url: Label.servePath + "/console/link/order/",
            type: "PUT",
            cache: false,
            data: JSON.stringify(requestJSONObject),
            success: function(result, textStatus){
                $("#tipMsg").text(result.msg);
                
                // Refershes the link list
                admin.linkList.getList(admin.linkList.pageInfo.currentPage);
                
                $("#loadMsg").text("");
            }
        });
    },

    checkHealth: function (btn) {
        if (btn && btn.disabled) return;
        var origText = btn ? btn.textContent : '';
        if (btn) {
            btn.disabled = true;
            btn.textContent = "巡检中…";
        }
        $("#loadMsg").text(Label.loadingLabel);
        $("#tipMsg").text("");
        $.ajax({
            url: Label.servePath + "/console/links/check-health",
            type: "POST",
            contentType: "application/json",
            data: "{}",
            timeout: 60000,
            success: function (res) {
                $("#tipMsg").text(res.msg || "巡检完成");
                admin.linkList.getList(admin.linkList.pageInfo.currentPage || 1);
            },
            error: function (xhr) {
                $("#tipMsg").text((xhr.responseJSON && xhr.responseJSON.msg) || "友链巡检失败");
            },
            complete: function () {
                $("#loadMsg").text("");
                if (btn) {
                    btn.disabled = false;
                    btn.textContent = origText;
                }
            }
        });
    }
};

/*
 * 注册到 admin 进行管理 
 */
admin.register["link-list"] =  {
    "obj": admin.linkList,
    "init": admin.linkList.init,
    "refresh": admin.linkList.getList
}