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
  _lastSideFixed: null,
  _initCommon: function ($goTop) {
    $(window).off('scroll.boloNijigen')
    var lastVisible = null
    Skin._lastSideFixed = null

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

      if ($('.side .article__toc').length > 0 && $(window).width() > 768) {
        var fixed = scrollTop > 50
        if (fixed !== Skin._lastSideFixed) {
          Skin._lastSideFixed = fixed
          $('.side').css('position', fixed ? 'fixed' : 'initial')
        }
      }
    }

    $(window).on('scroll.boloNijigen', onScroll)
    if (window.BoloNavigation) {
      BoloNavigation.onLeave(function () {
        $(window).off('scroll.boloNijigen')
      })
    }
  },
  _initAnimation: function () {
    if (!('IntersectionObserver' in window)) {
      $('.item').addClass('item--active')
      return false
    }

    if (window.imageIntersectionObserver) {
      window.imageIntersectionObserver.disconnect()
      $('.item').each(function () {
        window.imageIntersectionObserver.observe(this)
      })
    } else {
      window.imageIntersectionObserver = new IntersectionObserver(
        function (entries) {
          entries.forEach(function (entrie) {
            if (typeof entrie.isIntersecting === 'undefined'
              ? entrie.intersectionRatio !== 0 : entrie.isIntersecting) {
              $(entrie.target).addClass('item--active')
            } else {
              if ($(entrie.target).closest('.side').length === 1 ||
                $(entrie.target).closest('#articlePage').length === 1 ||
                $(entrie.target).outerHeight() > 768) {
                return
              }
              $(entrie.target).removeClass('item--active')
            }
          })
        })
      $('.item').each(function () {
        window.imageIntersectionObserver.observe(this)
      })
    }
  },
  init: function () {
    Util.initPjax(function () {
      Skin._initAnimation()
    })

    Skin._initAnimation()

    this._initCommon($('.icon__up'))

    $('.header__nav a, .header__m a').each(function () {
      if (this.href === location.href) {
        this.className = 'current'
      }
    }).click(function () {
      $('.header__nav a, .header__m a').removeClass('current')
      this.className = 'current'
      $('.header__m .module__list').hide()
    })

    $('.header__logo').click(function () {
      $('.header__nav a, .header__m a').removeClass('current')
    })
  },
  _initArticleCommon: function () {
    if ($(window).width() > 768) {
      Skin._lastSideFixed = null
      if ($('.article__toc li').length === 0) {
        $('.side').css({
          height: 'auto',
          position: 'initial',
        })
        return
      }

      $('#articlePage').width($('.main').width() - 310)
      $('.side').css({
        right: ($(window).width() - $('.main').width()) / 2,
        position: 'fixed',
        overflow: 'auto',
        height: $(window).height() - 30,
        top: 30,
      })
      $(window).scroll()
      $('.side').scrollTop(0)
    }
  },
  initArticle: function () {
    this._initArticleCommon()
  },
}
Skin.init()
