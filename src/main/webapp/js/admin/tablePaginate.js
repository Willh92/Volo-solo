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
 * table and paginate util
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 */

var TablePaginate = function (id) {
    this.id = id;
    this.currentPage = 1;
};

$.extend(TablePaginate.prototype, {
    /*
     * 构建 table 框架
     * @colModel table 列宽，标题等数据
     */
    buildTable: function (colModel, noExpend) {
        this.columnModel = colModel;
        var tableData = {
            colModel: colModel,
            noDataTip: Label.noDataLabel
        }
        if (!noExpend) {
            tableData.expendRow = {
                index: "expendRow"
            }
        }
        $("#" + this.id + "Table").addClass('console-card-list').table(tableData);
    
    },
    
    /*
     * 初始化分页
     */
    initPagination: function () {
        var id = this.id;
        $("#" + id + "Pagination").paginate({
            "bind": function(currentPage, errorMessage) {
                if (errorMessage) {
                    $("#tipMsg").text(errorMessage);
                } else {
                    admin.setHashByPage(currentPage);
                }
            },
            "currentPage": 1,
            "errorMessage": Label.inputErrorLabel,
            "nextPageText": '>',
            "previousPageText": '<',
            "goText": Label.gotoLabel,
            "type": "custom",
            "custom": [1],
            "pageCount": 1
        });
    },

    /*
     * 初始化评论对话框
     */
    initCommentsDialog: function () {
        var that = this;
        $("#" + this.id + "Comments").dialog({
            "title": Label.commentLabel,
            "modal": true,
            "hideFooter": true,
            "close": function () {
                admin[that.id + "List"].getList(that.currentPage);
                return true;
            }
        });
    },
    
    /*
     * 更新 table & paginateion
     */
    updateTablePagination: function (data, currentPage, pageInfo) {
        currentPage = parseInt(currentPage);
        if (currentPage > pageInfo.paginationPageCount && currentPage > 1) {
            $("#tipMsg").text(Label.pageLabel + currentPage + Label.notFoundLabel);
            $("#loadMsg").text("");
            return;
        }
        $("#" + this.id + "Table").table("update", {
            data: [{
                groupName: "all",
                groupData: data
            }]
        });
        this.labelFields();
                    
        if (pageInfo.paginationPageCount === 0) {
            pageInfo.paginationPageCount = 1;
        }
        
        $("#" + this.id + "Pagination").paginate("update", {
            pageCount: pageInfo.paginationPageCount,
            currentPage: currentPage,
            custom: pageInfo.paginationPageNums
        });
        this.currentPage = currentPage;
    },

    // Mobile cards retain the same translated column names and action links as the desktop table.
    labelFields: function () {
        var columns = this.columnModel || [];
        var primary = columns.findIndex(function (column) { return Boolean(column.text); });
        var plugin = this.id === 'plugin';
        var host = document.getElementById(this.id + 'Table');
        if (!host) return;
        host.querySelectorAll('.table-body tr:not(.table-expendRow)').forEach(function (row) {
            var cells = Array.from(row.children);
            if (cells.length !== columns.length || cells.some(function (cell) { return cell.tagName !== 'TD' || cell.colSpan > 1; })) return;
            row.classList.add('console-data-row');
            row.parentElement.classList.add('console-data-card');
            cells.forEach(function (cell, index) {
                cell.dataset.label = columns[index].text || '';
                cell.dataset.column = columns[index].index || '';
                var hasControl = Boolean(cell.querySelector('input,button,select,img,a,[onclick],[role="button"],[class*="icon-"],.fa'));
                cell.dataset.empty = String(!cell.textContent.trim() && !hasControl);
                if (columns[index].text && !cell.textContent.trim() && !hasControl) {
                    var content = cell.firstElementChild || cell;
                    content.textContent = columns[index].index === 'category' ? (Label.uncategorizedLabel || '未分类') : '—';
                }
                if (index === primary) cell.classList.add('console-data-primary');
                if (/content|desc|url|uri|address/i.test(columns[index].index || '') || (plugin && columns[index].index === 'author')) cell.classList.add('console-data-wide');
            });
        });
    }
});
