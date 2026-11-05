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
 * @fileoverview neoease js.
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 */
var goTopRaf = 0
var goTop = function (acceleration) {
  acceleration = acceleration || 0.1
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    window.scrollTo(0, 0)
    return
  }
  if (goTopRaf) {
    cancelAnimationFrame(goTopRaf)
    goTopRaf = 0
  }
  var speed = 1 + acceleration
  function step () {
    goTopRaf = 0
    var y = $(window).scrollTop()
    var nextY = Math.floor(y / speed)
    window.scrollTo(0, nextY)
    if (nextY > 0) {
      goTopRaf = requestAnimationFrame(step)
    }
  }
  step()
}

var collapseArchive = function (it, year) {
  var tag = true
  if (it.className === 'collapse-ico') {
    it.className = 'expand-ico'
    tag = false
  } else {
    it.className = 'collapse-ico'
  }

  $('#archiveSide li').each(function () {
    var $this = $(this)
    // hide other year month archives
    if ($this.data('year') === year) {
      if (tag) {
        $(this).show()
      } else {
        $(this).hide()
      }
    }
  })
}

var getArticle = function (it, id) {
  var $abstract = $('#abstract' + id),
    $content = $('#content' + id)

  if ($content.html() === '') {
    $.ajax({
      url: '/get-article-content?id=' + id,
      type: 'GET',
      dataType: 'html',
      beforeSend: function () {
        $abstract.css('background',
          'url(/skins/bolo-NeoEase/images/ajax-loader.gif) no-repeat scroll center center transparent')
      },
      success: function (result, textStatus) {
        it.className = 'collapse-ico'
        $content.html(result)
        $abstract.hide().css('background', 'none')
        $content.fadeIn('slow')
        Util.parseMarkdown()
      },
    })
  } else {
    if (it.className === 'expand-ico') {
      $abstract.hide()
      $content.fadeIn()
      it.className = 'collapse-ico'
    } else {
      $content.hide()
      $abstract.fadeIn()
      it.className = 'expand-ico'
    }
  }

  return false
}

var goTranslate = function () {
  window.open('https://translate.google.com/translate?sl=auto&tl=auto&u=' +
    encodeURIComponent(location.href), '_blank', 'noopener,noreferrer')
}

$(document).ready(function () {
  var returnTopRaf = 0
  var lastVisible = null
  var lastControlWidth = null
  var lastTopCss = null

  // go top icon show or hide
  function updateReturnTop () {
    returnTopRaf = 0
    var $button = $('#goTop')
    if (!$button.length) return
    var y = $(window).scrollTop()
    var bodyH = $(window).height()
    var mobile = window.innerWidth <= 600
    var top = y + bodyH - 21
    var $footer = $('.footer')
    if ($footer.length && $('body').height() - 58 <= y + bodyH) {
      top = $footer.offset().top - 21
    }

    var visible = y > 182
    if (visible !== lastVisible) {
      lastVisible = visible
      if (visible) {
        if (!$button.is(':visible')) $button.fadeIn('slow')
      } else {
        $button.stop(true, true).hide()
      }
    }

    var nextTopCss = mobile ? '' : top + 'px'
    if (nextTopCss !== lastTopCss) {
      lastTopCss = nextTopCss
      $button.css('top', mobile ? '' : top)
    }

    if (visible || lastControlWidth === null) {
      var widthStr = $button.outerWidth() + 'px'
      if (widthStr !== lastControlWidth) {
        lastControlWidth = widthStr
        document.documentElement.style.setProperty('--neoease-control-width', widthStr)
      }
    }

    var appearance = document.querySelector('.bolo-appearance--floating')
    if (appearance) {
      var nextRight = mobile ? '18px' : '56px'
      var nextBottom = (mobile ? 78 : visible ? bodyH - (top - y) + 12 : 33) + 'px'
      if (appearance.style.right !== nextRight) appearance.style.right = nextRight
      if (appearance.style.bottom !== nextBottom) appearance.style.bottom = nextBottom
    }
  }

  function scheduleReturnTop () {
    if (!returnTopRaf) {
      returnTopRaf = requestAnimationFrame(updateReturnTop)
    }
  }

  $(window).off('.boloNeoEase').on('scroll.boloNeoEase resize.boloNeoEase', scheduleReturnTop)
  updateReturnTop()
  if (window.BoloNavigation) {
    window.BoloNavigation.onLeave(function () {
      if (returnTopRaf) {
        cancelAnimationFrame(returnTopRaf)
        returnTopRaf = 0
      }
      if (goTopRaf) {
        cancelAnimationFrame(goTopRaf)
        goTopRaf = 0
      }
      $(window).off('.boloNeoEase')
    })
  }

  // archive
  var currentYear = (new Date()).getFullYear(),
    year = currentYear
  $('#archiveSide li').each(function (i) {
    var $this = $(this)

    // hide other year month archives
    if ($this.data('year') !== currentYear) {
      $(this).hide()
    }

    // append year archive
    if (year !== $this.data('year')) {
      year = $this.data('year')
      $this.before('<li class=\'archive-year\'><div onclick=\'collapseArchive(this, ' +
        year + ')\' class=\'expand-ico\'>' + year + '&nbsp;\u5e74</div></li>')
    }
  })

  // recent comment mouse click
  $('.recent-comments .expand-ico').click(function () {
    if (this.className === 'expand-ico') {
      $(this).parent().next().css({
        'height': 'auto',
        'white-space': 'normal',
      })
      this.className = 'collapse-ico'
    } else {
      $(this).parent().next().animate({
        'height': '18px',
      }, function () {
        $(this).css('white-space', 'nowrap')
      })
      this.className = 'expand-ico'
    }
  })

  // nav current
  $('.nav ul li').each(function () {
    var $a = $(this).find('a')
    if ($a.attr('href') === Label.servePath + location.pathname) {
      $(this).addClass('current')
    } else if (/\/[0-9]+$/.test(location.pathname)) {
      $('.nav ul li')[0].className = 'current'
    }
  })

  Util.setTopBar()
  Util.replaceSideEm($('.recent-comments-content'))
  Util.buildTags('tagsSide')

  // recent comments
  $('.recent-comments .recent-comments-main').each(function () {
    if ($(this).find('.recent-comments-content>a').height() < 30) {
      $(this).find('.expand-ico').remove()
    } else {
      $(this).find('.expand-ico').parent().next().css({
        'white-space': 'nowrap',
      })
    }
  })
})
