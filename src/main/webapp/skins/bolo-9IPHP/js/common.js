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
  _initCommon: function ($goTop) {
    $(window).off('scroll.bolo9iphp')
    var $banner = $('header .banner'),
      $navbar = $('header .navbar'),
      $mainWrapParent = $('.main-wrap').parent(),
      lastVisible = null,
      lastPinned = null

    function onScroll () {
      var scrollTop = $(window).scrollTop()
      var visible = scrollTop > 125
      if (visible !== lastVisible) {
        lastVisible = visible
        if (visible) {
          $goTop.show()
        } else {
          $goTop.hide()
        }
      }

      if ($(window).width() < 701) {
        return false
      }

      var pinned = scrollTop > $banner.height()
      if (pinned !== lastPinned) {
        lastPinned = pinned
        if (pinned) {
          $navbar.addClass('pin')
          $mainWrapParent.css('margin-top', '81px')
        } else {
          $navbar.removeClass('pin')
          $mainWrapParent.css('margin-top', '0')
        }
      }
    }

    $(window).on('scroll.bolo9iphp', onScroll)
    if (window.BoloNavigation) {
      BoloNavigation.onLeave(function () {
        $(window).off('scroll.bolo9iphp')
      })
    }

    onScroll()
  },
  init: function () {
    this._initCommon($('.icon-up'))

    $('.navbar nav a').each(function () {
      if (this.href === location.href) {
        this.className = 'current'
      }
    })

    $('.responsive .list a').each(function () {
      if (this.href === location.href) {
        $(this).parent().addClass('current')
      }
    })

    $('.theme9-header-tools .icon-list').click(function () {
      var button = $(this)
      $('.responsive .list').stop(true, true).slideToggle(160, function () {
        button.attr('aria-expanded', $(this).is(':visible') ? 'true' : 'false')
      })
    })
  },
  initToc: function () {
    var $articleToc = $('.article__toc')
    if ($articleToc.length === 0) {
      return false
    }

    $articleToc.css({
      width: $articleToc.parent().width(),
      left: $articleToc.parent().offset().left,
    }).find('a').click(function () {
      $articleToc.find('li').removeClass('toc--current')
      $(this).parent().addClass('toc--current')
      var id = $(this).attr('href')
      setTimeout(function () {
        $(window).scrollTop($(id).offset().top - 60)
      })
    })
  },
}

$(document).ready(function () {
  Skin.init()
})
