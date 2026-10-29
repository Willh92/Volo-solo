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

    var $articleProgress = $('.article__progress')
    function updateProgress () {
      if ($articleProgress.length === 0) {
        return false
      }

      $articleProgress.attr('value', parseInt($(window).scrollTop())).
        attr('max', parseInt($('body').outerHeight() -
          $(window).height()))

      if ($(window).scrollTop() > 236) {
        $('.article__top').css('top', 0)
      } else {
        $('.article__top').css('top', -61)
      }

    }
    $(window).on('scroll', updateProgress)
    updateProgress()
  },
}
Skin.init()
