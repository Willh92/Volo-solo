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
 * draft list for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* draft-list 相关操作 */
admin.draftList = {
    tablePagination:  new TablePaginate("draft"),
    
    /* 
     * 初始化 table, pagination, comments dialog 
     */
    init: function (page) {
        this.tablePagination.buildTable([{
            text: Label.titleLabel,
            index: "title",
            minWidth: 180,
            style: "font-size: 14px;"
        }, {
            text: Label.authorLabel,
            index: "author",
            width: 108
        }, {
            text: Label.commentLabel,
            index: "comments",
            width: 68
        }, {
            text: Label.viewLabel,
            width: 84,
            index: "articleViewCount"
        }, {
            text: Label.dateLabel,
            index: "date",
            width: 112
        }]);
        this.tablePagination.initPagination();
        this.tablePagination.initCommentsDialog();
        this.getList(page);
    },

    /* 
     * 根据当前页码获取列表
     * @pagNum 当前页码
     */
    getList: function (pageNum) {
        $("#loadMsg").text(Label.loadingLabel);
        var that = this;
        
        $.ajax({
            url: Label.servePath + "/console/articles/status/unpublished/" + pageNum + "/" + Label.PAGE_SIZE + "/" +  Label.WINDOW_SIZE + this.tablePagination.keywordQuery(),
            type: "GET",
            cache: false,
            success: function(result, textStatus){
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
                var decodeEntities = function (v) {
                    return String(v == null ? '' : v).replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, function (m, e) {
                        if (e === 'amp') return '&';
                        if (e === 'lt') return '<';
                        if (e === 'gt') return '>';
                        if (e === 'quot') return '"';
                        if (e === 'apos') return "'";
                        var cp = e.charAt(1) === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
                        return cp > 0 && cp <= 0x10ffff ? String.fromCodePoint(cp) : m;
                    });
                };
                var articles = result.articles,
                articleData = [];
                for (var i = 0; i < articles.length; i++) {
                    var rawPermalink = String(articles[i].articlePermalink || '');
                    var safeHref = (/^https?:\/\//i.test(rawPermalink) ? escapeHtml(rawPermalink) : escapeHtml(Label.servePath + (rawPermalink.charAt(0) === '/' ? rawPermalink : '/' + rawPermalink)));
                    var safeTitle = escapeHtml(decodeEntities(articles[i].articleTitle));
                    var safeId = encodeQuote(articles[i].oId);
                    var encodedTitle = encodeQuote(articles[i].articleTitle);
                    articleData[i] = {};
                    articleData[i].tags = escapeHtml(articles[i].articleTags);
                    articleData[i].date = $.bowknot.getDate(articles[i].articleCreateTime);
                    articleData[i].comments = escapeHtml(articles[i].articleCommentCount);
                    articleData[i].articleViewCount = escapeHtml(articles[i].articleViewCount);
                    articleData[i].author = escapeHtml(articles[i].authorName);
                    var schedMs = Number(articles[i].articleScheduleTime || 0);
                    var schedBadge = "";
                    if (schedMs > 0) {
                        var sd = new Date(schedMs);
                        var pad = function (n) { return n < 10 ? '0' + n : String(n); };
                        var schedStr = sd.getFullYear() + '-' + pad(sd.getMonth() + 1) + '-' + pad(sd.getDate()) + ' ' + pad(sd.getHours()) + ':' + pad(sd.getMinutes());
                        schedBadge = "<span class='table-tag' style='background:rgba(245,158,11,0.16);color:#d97706;border-radius:4px;padding:1px 6px;margin-right:6px;' title='到期将自动公开'>⏰ 定时 " + escapeHtml(schedStr) + "</span>";
                    }
                    articleData[i].title = "<a class='no-underline console-article-title' href='" + safeHref +
                    "' target='_blank' rel='noopener noreferrer'>" +
                    safeTitle + "</a>" + schedBadge + "<span class='table-tag'>" + escapeHtml(articles[i].articleTags) + "</span>";
                    articleData[i].expendRow = "<a href='javascript:void(0)' onclick=\"admin.article.get('" + safeId + "', false);\">" + Label.updateLabel + "</a>  \
                                <a href='javascript:void(0)' onclick=\"admin.article.del('" + safeId + "', 'draft', '" + encodedTitle + "')\">" + Label.removeLabel + "</a>  \
                                <a href='javascript:void(0)' onclick=\"admin.comment.open('" + safeId + "', 'draft')\">" + Label.commentLabel + "</a>";
                }
                    
                that.tablePagination.updateTablePagination(articleData, pageNum, result.pagination);
                
                $("#loadMsg").text("");
            }
        });
    }
};

/*
 * 注册到 admin 进行管理 
 */
admin.register["draft-list"] =  {
    "obj": admin.draftList,
    "init": admin.draftList.init,
    "refresh": admin.draftList.getList
};
