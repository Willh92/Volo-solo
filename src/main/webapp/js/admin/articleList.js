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
 * article list for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

/* article-list 相关操作 */
admin.articleList = {
  tablePagination: new TablePaginate('article'),

  /*
   * 初始化 table, pagination, comments dialog
   */
  init: function (page) {
    this.tablePagination.buildTable([
      {
        text: Label.titleLabel,
        index: 'title',
        minWidth: 180,
        style: 'font-size: 14px;',
      }, {
        text: Label.categoryLabel,
        index: 'category',
        width: 108,
      }, {
        text: Label.authorLabel,
        index: 'author',
        width: 100,
      }, {
        text: Label.commentLabel,
        index: 'comments',
        width: 68,
      }, {
        text: Label.viewLabel,
        width: 84,
        index: 'articleViewCount',
      }, {
        text: Label.dateLabel,
        index: 'date',
        width: 112,
      }])
    this.tablePagination.initPagination()
    this.tablePagination.initCommentsDialog()
    this.getList(page)

  },

  /**
   * 同步到链滴社区
   * @param id 文章 id
   */
  syncToHacpai: function (id) {
    $.ajax({
      url: Label.servePath + '/console/article/push2rhy?id=' + encodeURIComponent(id),
      type: 'POST',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text((result && result.msg) || Label.pushSuccLabel)
      },
    })
  },

  /*
   * 根据当前页码获取列表
   * @pagNum 当前页码
   */
  getList: function (pageNum) {
    var that = this
    $('#loadMsg').text(Label.loadingLabel)
    $.ajax({
      url: Label.servePath + '/console/articles/status/published/' +
        pageNum + '/' + Label.PAGE_SIZE + '/' + Label.WINDOW_SIZE + this.tablePagination.keywordQuery(),
      type: 'GET',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        var escapeHtml = (typeof Util !== 'undefined' && Util.escapeHtml) || function (v) {
          return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]
          })
        }
        var encodeQuote = (typeof Util !== 'undefined' && Util.encodeSingleQuote) || function (v) {
          return encodeURIComponent(String(v == null ? '' : v)).replace(/'/g, '%27')
        }
        var decodeEntities = function (v) {
          return String(v == null ? '' : v).replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, function (m, e) {
            if (e === 'amp') return '&'
            if (e === 'lt') return '<'
            if (e === 'gt') return '>'
            if (e === 'quot') return '"'
            if (e === 'apos') return "'"
            var cp = e.charAt(1) === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)
            return cp > 0 && cp <= 0x10ffff ? String.fromCodePoint(cp) : m
          })
        }
        var articles = result.articles,
          articleData = []
        for (var i = 0; i < articles.length; i++) {
          var rawPermalink = String(articles[i].articlePermalink || '')
          var safeHref = (/^https?:\/\//i.test(rawPermalink) ? escapeHtml(rawPermalink) : escapeHtml(Label.servePath + (rawPermalink.charAt(0) === '/' ? rawPermalink : '/' + rawPermalink)))
          var safeTitle = escapeHtml(decodeEntities(articles[i].articleTitle))
          var safeId = encodeQuote(articles[i].oId)
          var encodedTitle = encodeQuote(articles[i].articleTitle)
          articleData[i] = {}
          articleData[i].title = '<a href="' + safeHref +
            '" target=\'_blank\' rel=\'noopener noreferrer\' title=\'' +
            safeTitle + '\' class=\'no-underline console-article-title\'>'
            + safeTitle + '</a><span class=\'table-tag\'>' +
            escapeHtml(articles[i].articleTags) + '</span>'
          articleData[i].date = $.bowknot.getDate(articles[i].articleCreateTime)
          articleData[i].comments = escapeHtml(articles[i].articleCommentCount)
          articleData[i].articleViewCount = escapeHtml(articles[i].articleViewCount)
          articleData[i].author = escapeHtml(articles[i].authorName)
          articleData[i].category = escapeHtml(articles[i].articleCategory)
          if (articles[i].articlePutTop) articleData[i].title = '<span class="console-post-status">' + Label.putTopLabel + '</span>' + articleData[i].title

          var topClass = articles[i].articlePutTop
            ? Label.cancelPutTopLabel
            : Label.putTopLabel

          if (Label.b3logEnabled === "true" || Label.fishpiEnabled === "true") {
            articleData[i].expendRow = '<a href=\'javascript:void(0)\' onclick="admin.article.get(\'' +
                safeId + '\', true)">' + Label.updateLabel + '</a>  \
                                <a href=\'javascript:void(0)\' onclick="admin.article.del(\'' +
                safeId + '\', \'article\', \'' +
                encodedTitle + '\')">' +
                Label.removeLabel + '</a>  \
                              <a href=\'javascript:void(0)\' onclick="admin.articleList.syncToHacpai(\'' +
                safeId + '\')">' + Label.pushToHacpaiLabel + '</a>  \
                              <a href=\'javascript:void(0)\' onclick="admin.articleList.popTop(this, \'' +
                safeId + '\')">' + topClass + '</a>  \
                              <a href=\'javascript:void(0)\' onclick="admin.comment.open(\'' +
                safeId + '\', \'article\')">' + Label.commentLabel + '</a>'
          } else {
            articleData[i].expendRow = '<a href=\'javascript:void(0)\' onclick="admin.article.get(\'' +
                safeId + '\', true)">' + Label.updateLabel + '</a>  \
                                <a href=\'javascript:void(0)\' onclick="admin.article.del(\'' +
                safeId + '\', \'article\', \'' +
                encodedTitle + '\')">' +
                Label.removeLabel + '</a>  \
                              <a href=\'javascript:void(0)\' onclick="admin.articleList.popTop(this, \'' +
                safeId + '\')">' + topClass + '</a>  \
                              <a href=\'javascript:void(0)\' onclick="admin.comment.open(\'' +
                safeId + '\', \'article\')">' + Label.commentLabel + '</a>'
          }
        }

        that.tablePagination.updateTablePagination(articleData, pageNum,
          result.pagination)

        $('#loadMsg').text('')
      },
    })
  }

  ,

  /*
   * 制定或者取消置顶
   * @it 触发事件的元素本身
   * @id 草稿 id
   */
  popTop: function (it, id) {
    $('#loadMsg').text(Label.loadingLabel)
    $('#tipMsg').text('')

    var $it = $(it),
      ajaxUrl = 'canceltop',
      tip = Label.putTopLabel

    if ($it.html() === Label.putTopLabel) {
      ajaxUrl = 'puttop'
      tip = Label.cancelPutTopLabel
    }

    $.ajax({
      url: Label.servePath + '/console/article/' + ajaxUrl + '/' + id,
      type: 'PUT',
      cache: false,
      success: function (result, textStatus) {
        $('#tipMsg').text(result.msg)
        if (!result.sc) {
          $('#loadMsg').text('')
          return
        }

        $it.html(tip)
        admin.articleList.getList(admin.articleList.tablePagination.currentPage)
        $('#loadMsg').text('')
      },
    })
  },
}

/*
 * 注册到 admin 进行管理
 */
admin.register['article-list'] = {
  'obj': admin.articleList,
  'init': admin.articleList.init,
  'refresh': admin.articleList.getList,
}
