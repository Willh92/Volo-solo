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
function Emiya() {
  this.listen()
}

function initPageShare() {
  var $this = $('.J_share')
  var $qrCode = $('.J_share_wechat')
  var shareURL = $qrCode.data('url')
  var avatarURL = $qrCode.data('avatar')
  var title = encodeURIComponent($qrCode.data('title') + ' - ' +
    $qrCode.data('blogtitle')),
    url = encodeURIComponent(shareURL)

  var urls = {}
  urls.weibo = 'http://v.t.sina.com.cn/share/share.php?title=' + title + '&url=' + url + '&pic=' + avatarURL
  urls.qzone = 'https://sns.qzone.qq.com/cgi-bin/qzshare/cgi_qzshare_onekey?url=' + url + '&sharesource=qzone&title=' + title + '&pics=' + avatarURL
  urls.twitter = 'https://twitter.com/intent/tweet?status=' + title + ' ' + url

  $this.on('click', function () {
    var key = $(this).data('type')

    if (!key) {
      return
    }

    if (key === 'wechat') {
      if ($qrCode.find('canvas').length === 0) {
        $.ajax({
          method: 'GET',
          url: Label.staticServePath +
          '/js/lib/jquery.qrcode.min.js',
          dataType: 'script',
          cache: true,
          success: function () {
            $qrCode.qrcode({
              width: 128,
              height: 128,
              text: shareURL,
            })
          },
        })
      } else {
        $qrCode.find('canvas').slideToggle()
      }
      return false
    }

    window.open(urls[key], '_blank', 'top=100,left=200,width=648,height=618')
  })
}

function ScrollManagerCreator(_now) {
  var nowScroll = _now
  var $nav = $('.J_navbar')
  var $backToTop = $('.toTop')
  var showBackToTopHeight = 100
  var backToTopVisible

  function checkFixed(nextScroll) {
    var offsetTop = $nav.height() + 1
    if (nextScroll > offsetTop) {
      $nav.addClass('is-fixed').css('top', -1 * offsetTop);
    } else if (nextScroll <= 0) {
      $nav.removeClass('is-fixed').css('top', 0);
    }

    if (!$nav.hasClass('is-fixed')) {
      return
    }

    if (nextScroll > nowScroll) {
      $nav.removeClass('show')
    } else {
      $nav.addClass('show')
    }
  }

  function checkBackToTop(nextScroll) {
    var visible = nextScroll > showBackToTopHeight
    if (visible === backToTopVisible) return
    backToTopVisible = visible
    $backToTop.stop(true, true)
    if (visible) {
      $backToTop.fadeIn()
      window.BoloTheme?.syncControls?.()
    } else {
      $backToTop.fadeOut()
    }
  }

  checkFixed(nowScroll)
  checkBackToTop(nowScroll)

  return function(nextScroll) {
    checkFixed(nextScroll)
    checkBackToTop(nextScroll)

    nowScroll = nextScroll
  }
}

function emiyaScrollTo(to) {
  $('html ,body').animate({
    scrollTop: to,
  }, 300);
}

var scrollManager = ScrollManagerCreator($(window).scrollTop())

Emiya.prototype.listen = function() {
  $(".J_navbar_toggle").on("click", function() {
    var targetClass = '.' + $(this).attr('data-for')
    if ($(targetClass).hasClass('show')) {
        $(targetClass).removeClass('show')
    } else {
        $(targetClass).addClass('show')
    }
  });
  $(".J_replyName").on('click', function() {
    var replyName = $(this).attr('data-originalId');
    $('#' + replyName)[0].scrollIntoViewIfNeeded(true);
    $('#' + replyName).addClass('blink');
    setTimeout(function() {
      $('#' + replyName).removeClass('blink');
    }, 3000);
  });
  $('.J_backToTop').on('click keydown', function(e) {
    if (e.type === 'keydown' && !['Enter', ' '].includes(e.key)) return;
    emiyaScrollTo(0);
    e.preventDefault();
  });
  $(window).on('scroll', function() {
    var nowScrollTop = $(window).scrollTop()
    scrollManager(nowScrollTop)
  });
}

Emiya.prototype.initArticle = function() {
  initPageShare();
}

window.Skin = new Emiya();
