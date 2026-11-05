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
 * @fileoverview util and every page should be used.
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 */

/**
 * @description 皮肤脚本
 * @static
 */
var Skin = {
  init: function () {
    Util.initPjax()
  },
  initArticle: function () {
    page.share()

    $(window).off('scroll.boloCasper')
    var $articleProgress = $('.article__progress')
    var $articleTop = $('.article__top')
    var lastTopVisible = null
    var rafId = 0

    function updateProgress () {
      rafId = 0
      if ($articleProgress.length === 0) {
        return false
      }

      var scrollTop = $(window).scrollTop()
      var max = Math.max(0, parseInt($('body').outerHeight() - $(window).height(), 10) || 0)
      $articleProgress.attr('value', parseInt(scrollTop, 10) || 0).attr('max', max)

      var visible = scrollTop > 236
      if (visible !== lastTopVisible) {
        lastTopVisible = visible
        $articleTop.css('top', visible ? 0 : -61)
      }
    }

    function onScroll () {
      var visible = $(window).scrollTop() > 236
      if (visible !== lastTopVisible) {
        lastTopVisible = visible
        $articleTop.css('top', visible ? 0 : -61)
      }
      if (!rafId) {
        if (window.requestAnimationFrame) {
          rafId = window.requestAnimationFrame(updateProgress)
        } else {
          updateProgress()
        }
      }
    }

    $(window).on('scroll.boloCasper', onScroll)
    if (window.BoloNavigation) {
      BoloNavigation.onLeave(function () {
        if (rafId && window.cancelAnimationFrame) {
          window.cancelAnimationFrame(rafId)
        }
        rafId = 0
        $(window).off('scroll.boloCasper')
      })
    }
    updateProgress()
  },
}
Skin.init()
