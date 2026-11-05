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
    $(window).off('.boloBubble')

    $('#headerDown').off('click.boloBubble').on('click.boloBubble', function () {
      $('html, body').animate({scrollTop: $(window).height()}, 300)
    })

    var scrollRaf = 0
    var lastEdge = null
    var lastTopVisible = null
    var $progress = $('.fn__progress')
    var $sideMenu = $('.side__menu')
    var $sideTop = $('#sideTop')

    function syncScrollState () {
      scrollRaf = 0
      var scrollTop = $(window).scrollTop()
      var winHeight = $(window).height()
      if ($progress.length) {
        $progress.attr('value', parseInt(scrollTop, 10)).
          attr('max', Math.max(1, parseInt($('body').outerHeight() - winHeight, 10)))
      }
      var edge = scrollTop > winHeight / 2 - 20
      if (edge !== lastEdge) {
        lastEdge = edge
        $sideMenu.toggleClass('side__menu--edge', edge)
      }
      var topVisible = scrollTop > 180
      if (topVisible !== lastTopVisible) {
        lastTopVisible = topVisible
        $sideTop.toggleClass('is-visible', topVisible)
      }
    }

    $(window).on('scroll.boloBubble', function () {
      if (!scrollRaf) {
        scrollRaf = requestAnimationFrame(syncScrollState)
      }
    })

    var closeMenuTimer = 0
    $sideMenu.off('click.boloBubble').on('click.boloBubble', function () {
      clearTimeout(closeMenuTimer)
      $(this).attr('aria-expanded', 'true')
      $('.side__main').addClass('side__main--show').show()
      $('.side__close').trigger('focus')
    })
    function closeMenu () {
      window.BoloTheme?.closeMenu?.()
      $('.side__menu').attr('aria-expanded', 'false').trigger('focus')
      $('.side__main').removeClass('side__main--show')
      clearTimeout(closeMenuTimer)
      closeMenuTimer = setTimeout(function () {
        $('.side__main').hide()
      }, 250)
    }
    $('.side__bg, .side__close').off('click.boloBubble').on('click.boloBubble', closeMenu)
    function onKeyDown (event) {
      if (event.key === 'Escape' && $('.side__main').hasClass('side__main--show')) closeMenu()
    }
    document.addEventListener('keydown', onKeyDown)
    $sideTop.off('click.boloBubble').on('click.boloBubble', function () {
      Util.goTop()
    })

    var timer = 0
    var blogTitle = $('.header__h1').text() || document.title
    function onVisibilityChange () {
      if (timer) clearTimeout(timer)
      if (document.hidden) {
        timer = setTimeout(function () {
          document.title = '(◍´꒳`◍)' + ' - ' + blogTitle
        }, 500)
      } else {
        document.title = '(*´∇｀*) 欢迎回来！'
        timer = setTimeout(function () {
          document.title = blogTitle
        }, 1000)
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange, false)

    var ribbonsInstance = new Ribbons({
      colorSaturation: '60%',
      colorBrightness: '50%',
      colorAlpha: 0.5,
      colorCycleSpeed: 5,
      verticalPosition: 'random',
      horizontalSpeed: 200,
      ribbonCount: 3,
      strokeSize: 0,
      parallaxAmount: -0.2,
      animateSections: true,
    })

    if (window.BoloNavigation) {
      window.BoloNavigation.onLeave(function () {
        if (scrollRaf) {
          cancelAnimationFrame(scrollRaf)
          scrollRaf = 0
        }
        clearTimeout(closeMenuTimer)
        clearTimeout(timer)
        $(window).off('.boloBubble')
        document.removeEventListener('keydown', onKeyDown)
        document.removeEventListener('visibilitychange', onVisibilityChange, false)
        if (ribbonsInstance && typeof ribbonsInstance.destroy === 'function') {
          ribbonsInstance.destroy()
        }
      })
    }

    if ($('#comments').length === 1) {
      syncScrollState()
      return
    }
    syncScrollState()

    $('.header').circleMagic({
      clearOffset: 0.3,
      color: 'rgba(255,255,255, .2)',
      density: 0.2,
      radius: 15,
    })
  },
  initArticle: function () {
    page.share()

    initCanvas('articleTop')

    var contentEl = document.querySelector('.article__content')
    if ($(window).width() >= 1400 && contentEl) {
      $('.post__toc').css({
        left: contentEl.getBoundingClientRect().right + 20,
        right: 'auto',
        display: 'block',
      })
    }

    var $articleTocs = $('.vditor-reset [id^=b3_solo_h]')
    var $articleToc = $('.article__toc')

    $articleToc.find('a').off('click.boloBubble').on('click.boloBubble', function (event) {
      var id = $(this).attr('href')
      window.location.hash = id
      $(window).scrollTop($(id).offset().top)
      if ($(window).width() < 768) {
        $('.post__toc').slideToggle()
      }
      event.preventDefault()
      event.stopPropagation()
      return false
    })

    var articleTocRaf = 0
    function syncArticleToc () {
      articleTocRaf = 0
      if ($('.article__toc li').length === 0) {
        return false
      }

      var toc = []
      $articleTocs.each(function () {
        toc.push({
          id: this.id,
          offsetTop: $(this).offset().top,
        })
      })
      if (!toc.length) return false

      var scrollTop = $(window).scrollTop() + 10
      for (var i = 0, iMax = toc.length; i < iMax; i++) {
        if (scrollTop < toc[i].offsetTop) {
          $articleToc.find('li').removeClass('current')
          var index = i > 0 ? i - 1 : 0
          $articleToc.find('a[href="#' + toc[index].id + '"]').
            parent().
            addClass('current')
          break
        }
      }
      if (scrollTop >= toc[toc.length - 1].offsetTop) {
        $articleToc.find('li').removeClass('current')
        $articleToc.find('li:last').addClass('current')
      }
    }

    $(window).off('scroll.boloBubbleArticle').on('scroll.boloBubbleArticle', function () {
      if (!articleTocRaf) {
        articleTocRaf = requestAnimationFrame(syncArticleToc)
      }
    })
    syncArticleToc()
    if (window.BoloNavigation) {
      window.BoloNavigation.onLeave(function () {
        if (articleTocRaf) {
          cancelAnimationFrame(articleTocRaf)
          articleTocRaf = 0
        }
        $(window).off('.boloBubbleArticle')
      })
    }
  },
}

$(document).ready(function () {
  Skin.init()
})
