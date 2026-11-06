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
 * page list for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* page-list 相关操作 */
admin.pageList = {
    tablePagination: new TablePaginate("page"),
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
                index: "pageOrder",
                width: 68
            }, {
                text: Label.titleLabel,
                index: "pageTitle",
                width: 220
            }, {
                text: Label.permalinkLabel,
                index: "pagePermalink",
                minWidth: 180
            }, {
                text: Label.openMethodLabel,
                index: "pageTarget",
                width: 110
            }]);
        this.tablePagination.initPagination();
        this.getList(page);
    },
    /* 
     * 根据当前页码获取列表
     * @pagNum 当前页码
     */
    getList: function (pageNum) {
        $("#loadMsg").text(Label.loadingLabel);
        $("#tipMsg").text("");
        var that = this;

        $.ajax({
            url: Label.servePath + "/console/pages/" + pageNum + "/" + Label.PAGE_SIZE + "/" + Label.WINDOW_SIZE + this.tablePagination.keywordQuery(),
            type: "GET",
            cache: false,
            success: function (result, textStatus) {
                $("#tipMsg").text(result.msg);
                if (!result.sc) {
                    $("#loadMsg").text("");
                    return;
                }

                var pages = result.pages;
                var pageData = [];
                var escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
                admin.pageList.pageInfo.currentCount = pages.length;
                admin.pageList.pageInfo.pageCount = result.pagination.paginationPageCount === 0 ? 1 : result.pagination.paginationPageCount;
                for (var i = 0; i < pages.length; i++) {
                    var safeId = encodeURIComponent(pages[i].oId).replace(/'/g, '%27');
                    pageData[i] = {};
                    if (i === 0) {
                        if (pages.length === 1) {
                            pageData[i].pageOrder = "";
                        } else {
                            pageData[i].pageOrder = '<div class="table-center" style="width:14px">\
                                        <span onclick="admin.pageList.changeOrder(\'' + safeId + '\', ' + i + ', \'down\');" \
                                        class="icon-move-down"></span></div>';
                        }
                    } else if (i === pages.length - 1) {
                        pageData[i].pageOrder = '<div class="table-center" style="width:14px">\
                                    <span onclick="admin.pageList.changeOrder(\'' + safeId + '\', ' + i + ', \'up\');" class="icon-move-up"></span>\
                                    </div>';
                    } else {
                        pageData[i].pageOrder = '<div class="table-center" style="width:38px">\
                                    <span onclick="admin.pageList.changeOrder(\'' + safeId + '\', ' + i + ', \'up\');" class="icon-move-up"></span>\
                                    <span onclick="admin.pageList.changeOrder(\'' + safeId + '\', ' + i + ', \'down\');" class="icon-move-down"></span>\
                                    </div>';
                    }

                    var pageIcon = '';
                    var rawAddr = String(pages[i].pagePermalink == null ? '' : pages[i].pagePermalink).trim();
                    var title = escape(pages[i].pageTitle), address = escape(rawAddr);
                    var safeHref = (/^https?:\/\//i.test(rawAddr) || rawAddr.indexOf('/') === 0) ? address : '#';
                    var encodedTitle = encodeURIComponent(pages[i].pageTitle).replace(/'/g, '%27');
                    if (pages[i].pageIcon !== '') {
                      var rawIcon = String(pages[i].pageIcon).trim();
                      if (/^https?:\/\//i.test(rawIcon) || rawIcon.indexOf('/') === 0) {
                        pageIcon = "<img class='navigation-icon' src='" + escape(rawIcon) + "' alt=''/> ";
                      }
                    }
                    pageData[i].pageTitle = pageIcon + "<a class='no-underline' href='" + safeHref + "' target='_blank' rel='noopener noreferrer'>" + title + "</a>";
                    pageData[i].pagePermalink = "<a class='no-underline' href='" + safeHref + "' target='_blank' rel='noopener noreferrer'>" + address + "</a>";
                    pageData[i].pageTarget = escape(pages[i].pageOpenTarget);
                    pageData[i].expendRow = "<span><a href='" + safeHref + "' target='_blank' rel='noopener noreferrer'>" + Label.viewLabel + "</a>  \
                                <a href='javascript:void(0)' onclick=\"admin.pageList.get('" + safeId + "')\">" + Label.updateLabel + "</a>\
                                <a href='javascript:void(0)' onclick=\"admin.pageList.del('" + safeId + "', '" + encodedTitle + "')\">" + Label.removeLabel + "</a></span>";
                }

                that.tablePagination.updateTablePagination(pageData, pageNum, result.pagination);

                $("#loadMsg").text("");
            }
        });
    },
    /*
     * 获取自定义页面
     * @id 自定义页面 id
     */
    get: function (id) {
        $("#loadMsg").text(Label.loadingLabel);
        $("#tipMsg").text("");

        $.ajax({
            url: Label.servePath + "/console/page/" + id,
            type: "GET",
            cache: false,
            success: function (result, textStatus) {
                $("#tipMsg").text(result.msg);
                if (!result.sc) {
                    $("#loadMsg").text("");
                    return;
                }

                admin.pageList.id = id;

                $("#pageTitle").val(result.page.pageTitle);
                $("#pagePermalink").val(result.page.pagePermalink);
                $("#pageTarget").val(result.page.pageOpenTarget);
                $("#pageIcon").val(result.page.pageIcon);
                document.getElementById('pageEditorTitle').textContent = Label.updateLabel + ' ' + Label.navLabel;
                document.getElementById('pageCancelEdit').hidden = false;
                document.getElementById('pageSave').textContent = Label.updateLabel;
                document.getElementById('pageEditor').scrollIntoView({block: 'start', behavior: 'smooth'});
                document.getElementById('pageTitle').focus({preventScroll: true});
                $("#loadMsg").text("");
            }
        });
    },
    /* 
     * 删除自定义页面
     * @id 自定义页面 id
     * @title 自定义页面标题
     */
    del: function (id, title) {
        var isDelete = confirm(Label.confirmRemoveLabel + Label.navLabel + '"' + decodeURIComponent(title) + '"?');
        if (isDelete) {
            $("#loadMsg").text(Label.loadingLabel);
            $("#tipMsg").text("");

            $.ajax({
                url: Label.servePath + "/console/page/" + id,
                type: "DELETE",
                cache: false,
                success: function (result, textStatus) {
                    $("#tipMsg").text(result.msg);
                    if (!result.sc) {
                        $("#loadMsg").text("");
                        return;
                    }

                    var pageNum = admin.pageList.pageInfo.currentPage;
                    if (admin.pageList.pageInfo.currentCount === 1 && admin.pageList.pageInfo.pageCount !== 1 &&
                            admin.pageList.pageInfo.currentPage === admin.pageList.pageInfo.pageCount) {
                        admin.pageList.pageInfo.pageCount--;
                        pageNum = admin.pageList.pageInfo.pageCount;
                    }
                    var hashList = window.location.hash.split("/");
                    if (pageNum == hashList[hashList.length - 1]) {
                        admin.pageList.getList(pageNum);
                    } else {
                        admin.setHashByPage(pageNum);
                    }

                    $("#loadMsg").text("");
                }
            });
        }
    },
    /*
     * 添加自定义页面
     */
    add: function () {
        if (this.validate()) {
            $("#loadMsg").text(Label.loadingLabel);
            $("#tipMsg").text("");

            var pagePermalink = $("#pagePermalink").val().replace(/(^\s*)|(\s*$)/g, "");

            var requestJSONObject = {
                "page": {
                    "pageTitle": $("#pageTitle").val(),
                    "pagePermalink": pagePermalink,
                    "pageOpenTarget": $("#pageTarget").val(),
                    "pageIcon": $("#pageIcon").val()
                }
            };

            $.ajax({
                url: Label.servePath + "/console/page/",
                type: "POST",
                cache: false,
                data: JSON.stringify(requestJSONObject),
                success: function (result, textStatus) {
                    $("#tipMsg").text(result.msg);
                    if (!result.sc) {
                        $("#loadMsg").text("");
                        return;
                    }

                    admin.pageList.id = "";
                    $("#pagePermalink").val("");
                    $("#pageTitle").val("");
                    $("#pageIcon").val("");
                    $("#pageTarget").val("_self");

                    if (admin.pageList.pageInfo.currentCount === Label.PAGE_SIZE &&
                            admin.pageList.pageInfo.currentPage === admin.pageList.pageInfo.pageCount) {
                        admin.pageList.pageInfo.pageCount++;
                    }
                    var hashList = window.location.hash.split("/");
                    if (admin.pageList.pageInfo.pageCount == hashList[hashList.length - 1]) {
                        admin.pageList.getList(admin.pageList.pageInfo.pageCount);
                    } else {
                        admin.setHashByPage(admin.pageList.pageInfo.pageCount);
                    }

                    $("#loadMsg").text("");
                }
            });
        }
    },
    /*
     * 更新自定义页面
     */
    update: function () {
        if (this.validate()) {
            $("#loadMsg").text(Label.loadingLabel);
            $("#tipMsg").text("");

            var pagePermalink = $("#pagePermalink").val().replace(/(^\s*)|(\s*$)/g, "");

            var requestJSONObject = {
                "page": {
                    "pageTitle": $("#pageTitle").val(),
                    "oId": this.id,
                    "pagePermalink": pagePermalink,
                    "pageOpenTarget": $("#pageTarget").val(),
                    "pageIcon": $("#pageIcon").val()
                }
            };

            $.ajax({
                url: Label.servePath + "/console/page/",
                type: "PUT",
                cache: false,
                data: JSON.stringify(requestJSONObject),
                success: function (result, textStatus) {
                    $("#tipMsg").text(result.msg);

                    if (!result.sc) {
                        $("#loadMsg").text("");
                        return;
                    }
                    admin.pageList.cancelEdit();
                    admin.pageList.getList(admin.pageList.pageInfo.currentPage);
                    $("#loadMsg").text("");
                }
            });
        }
    },
    cancelEdit: function () {
        this.id = '';
        $("#pageTitle,#pageIcon,#pagePermalink").val('');
        $("#pageTarget").val('_self');
        var title = document.getElementById('pageEditorTitle');
        title.textContent = title.dataset.createLabel;
        document.getElementById('pageCancelEdit').hidden = true;
        document.getElementById('pageSave').textContent = Label.saveLabel || '保存';
    },
    /*
     * 验证字段
     */
    validate: function () {
        if ($("#pageTitle").val().replace(/\s/g, "") === "") {
            $("#tipMsg").text(Label.titleEmptyLabel);
            $("#pageTitle").focus();
        } else if ($("#pagePermalink").val().replace(/\s/g, "") === "") {
            $("#tipMsg").text(Label.linkEmptyLabel);
        } else {
            return true;
        }
        return false;
    },
    /*
     * 提交自定义页面
     */
    submit: function () {
        if (this.id !== "") {
            this.update();
        } else {
            this.add();
        }
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
            url: Label.servePath + "/console/page/order/",
            type: "PUT",
            cache: false,
            data: JSON.stringify(requestJSONObject),
            success: function (result, textStatus) {
                $("#tipMsg").text(result.msg);

                // Refershes the page list
                admin.pageList.getList(admin.pageList.pageInfo.currentPage);

                $("#loadMsg").text("");
            }
        });
    }
};

/*
 * 注册到 admin 进行管理 
 */
admin.register["page-list"] = {
    "obj": admin.pageList,
    "init": admin.pageList.init,
    "refresh": function (page) {
        this.cancelEdit();
        this.getList(page);
    }
}
