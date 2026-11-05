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
    this.keyword = '';
};

$.extend(TablePaginate.prototype, {
    /*
     * 构建 table 框架
     * @colModel table 列宽，标题等数据
     */
    buildTable: function (colModel, noExpend) {
        this.columnModel = colModel.slice();
        if (!noExpend && !colModel.some(function (column) { return column.index === 'actions' || column.index === 'expendRow'; })) {
            this.columnModel.push({text: Label.operationsLabel || '操作', index: 'expendRow', width: this.id === 'article' ? 230 : 170});
        }
        var tableData = {
            colModel: this.columnModel,
            noDataTip: Label.noDataLabel
        }
        // Keep actions in normal cells: the legacy hover rows depend on the removed $.browser API.
        $("#" + this.id + "Table").addClass('console-card-list').table(tableData);
        var host = typeof document !== 'undefined' && document && document.getElementById
            ? document.getElementById(this.id + 'Table')
            : null;
        if (host && host.addEventListener && !host._voloHoverCleanupBound) {
            host._voloHoverCleanupBound = true;
            host.addEventListener('mouseleave', function (event) {
                var target = event && event.target;
                if (!target || !target.classList) return;
                if (target.tagName === 'TBODY') {
                    target.classList.remove('table-lineHover');
                } else if (target === host || target.classList.contains('table-body')) {
                    host.querySelectorAll('tbody.table-lineHover').forEach(function (tb) {
                        tb.classList.remove('table-lineHover');
                    });
                }
            }, true);
        }
    
    },
    
    /*
     * 初始化分页
     */
    initPagination: function () {
        var id = this.id;
        this.initSearch();
        admin.pagination.render(id + 'Pagination', 1, 1, admin.setHashByPage);

    },

    initSearch: function () {
        if (!['article', 'draft', 'category', 'page', 'link', 'follow', 'comment'].includes(this.id)) return;
        var host = document.getElementById(this.id + 'Table'), that = this;
        if (!host) return;
        var input = document.getElementById(this.id + 'ListInput');
        var form = input ? input.closest('form') : null;
        if (!form) {
            form = document.createElement('form');
            form.className = 'form fn__margin12 console-list-search console-list-filter';
            input = document.createElement('input');
            input.id = this.id + 'ListInput'; input.type = 'search';
            var button = document.createElement('button');
            button.type = 'submit'; button.className = 'search-btn'; button.textContent = Label.searchLabel || '搜索';
            form.append(input, button); host.before(form);
        }
        input.maxLength = 200; input.value = this.keyword;
        input.setAttribute('aria-label', Label.searchLabel || '搜索');
        input.placeholder = this.id === 'comment' ? '内容、昵称、邮箱或 IP' : (Label.searchLabel || '搜索');
        form.onsubmit = function (event) {
            event.preventDefault(); that.keyword = input.value.trim();
            var previousHash = window.location.hash;
            admin.setHashByPage(1);
            if (window.location.hash === previousHash) admin[that.id + 'List'].getList(1);
        };
    },

    keywordQuery: function () { return this.keyword ? '?k=' + encodeURIComponent(this.keyword) : ''; },

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
        var filtering = Boolean(this.keyword);
        $("#" + this.id + "Table").table("update", {
            data: [{
                groupName: "all",
                groupData: data.map(function (row) {
                    var item = Object.assign({}, row);
                    if (filtering && Object.prototype.hasOwnProperty.call(item, 'linkOrder')) item.linkOrder = '';
                    ['expendRow', 'actions'].forEach(function (key) {
                        if (typeof item[key] === 'string' && item[key].trim()) {
                            item[key] = '<div class="console-row-actions">' + item[key] + '</div>';
                        }
                    });
                    return item;
                })
            }]
        });
        this.labelFields();
                    
        if (pageInfo.paginationPageCount === 0) {
            pageInfo.paginationPageCount = 1;
        }
        
        admin.pagination.render(this.id + 'Pagination', currentPage, pageInfo.paginationPageCount, admin.setHashByPage);
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
                if (columns[index].index === 'expendRow' || columns[index].index === 'actions') cell.classList.add('console-data-actions');
                if (/content|desc|url|uri|address/i.test(columns[index].index || '') || (plugin && columns[index].index === 'author')) cell.classList.add('console-data-wide');
            });
        });
    }
});
