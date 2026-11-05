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
  _headerHeadroom: null,
  _sharerHeadroom: null,
  init: function () {
    if (Skin._headerHeadroom && typeof Skin._headerHeadroom.destroy === 'function') {
      Skin._headerHeadroom.destroy()
      Skin._headerHeadroom = null
    }
    var headerEl = $('header')[0]
    if (headerEl && typeof Headroom === 'function') {
      var header = new Headroom(headerEl, {
        tolerance: 0,
        offset: 70,
        classes: {
          initial: 'header',
          pinned: 'header--down',
          unpinned: 'header--up',
          top: 'header',
          notTop: 'header',
          bottom: 'header',
          notBottom: 'header',
        },
      })
      header.init()
      Skin._headerHeadroom = header
      if (window.BoloNavigation && typeof window.BoloNavigation.onLeave === 'function') {
        window.BoloNavigation.onLeave(function () {
          if (Skin._headerHeadroom === header) {
            header.destroy()
            Skin._headerHeadroom = null
          }
        })
      }
    }

    Util.initPjax(function () {
      if ($('.post__fix').length === 0) {
        $('body').addClass('body--gray')
      } else {
        $('body').removeClass('body--gray')
      }
      $('.header__nav a').removeClass('current').each(function () {
        if (this.href === location.href) {
          this.className = 'current'
        }
      })

      Skin._initToc()
    })

    $('.header__nav a').each(function () {
      if (this.href === location.href) {
        this.className = 'current'
      }
    }).click(function () {
      $('.header__nav a').removeClass('current')
      if (this.href === location.href) {
        this.className = 'current'
      }
    })
  },
  _initToc: function () {
    $(window).off('scroll.pinghsuToc')
    if ($('.article__toc').length === 0) {
      return
    }
    var $post = $('.post')
    var $postToc = $('.post__toc')
    if ($post.length) {
      $postToc.css('left', $post.offset().left + $post.outerWidth())
    }

    var $articleTocs = $('.vditor-reset [id^=b3_solo_h]'),
      $articleToc = $('.article__toc'),
      tocRaf = 0,
      lastTocVisible = null;

    function syncToc () {
      tocRaf = 0
      if ($('.article__toc li').length === 0) {
        return false
      }

      var scrollTop = $(window).scrollTop()
      var visible = scrollTop > 72
      if (visible !== lastTocVisible) {
        lastTocVisible = visible
        if (visible) {
          $postToc.show()
        } else {
          $postToc.hide()
        }
      }
      if (!visible) {
        return
      }

      var toc = []
      $articleTocs.each(function () {
        toc.push({
          id: this.id,
          offsetTop: this.offsetTop
        })
      })
      if (!toc.length) return

      for (var i = 0, iMax = toc.length; i < iMax; i++) {
        if (scrollTop < toc[i].offsetTop - 20) {
          $articleToc.find('li').removeClass('current')
          var index = i > 0 ? i - 1 : 0
          $articleToc.find('a[href="#' + toc[index].id + '"]').parent().addClass('current')
          break
        }
      }
      if (scrollTop >= toc[toc.length - 1].offsetTop - 20) {
        $articleToc.find('li').removeClass('current')
        $articleToc.find('li:last').addClass('current')
      }
    }

    $(window).on('scroll.pinghsuToc', function () {
      if (!tocRaf) {
        tocRaf = requestAnimationFrame(syncToc)
      }
    })

    syncToc()
    if (window.BoloNavigation) {
      window.BoloNavigation.onLeave(function () {
        if (tocRaf) {
          cancelAnimationFrame(tocRaf)
          tocRaf = 0
        }
        $(window).off('scroll.pinghsuToc')
      })
    }
  },
  _initShare: function () {
    var $this = $('.post__share')
    var $qrCode = $this.find('.post__code')
    var shareURL = $qrCode.data('url')
    var avatarURL = $qrCode.data('avatar')
    var title = encodeURIComponent($qrCode.data('title') + ' - ' +
      $qrCode.data('blogtitle')),
      url = encodeURIComponent(shareURL)

    var urls = {}
    urls.weibo = 'http://v.t.sina.com.cn/share/share.php?title=' +
      title + '&url=' + url + '&pic=' + avatarURL
    urls.qqz = 'https://sns.qzone.qq.com/cgi-bin/qzshare/cgi_qzshare_onekey?url='
      + url + '&sharesource=qzone&title=' + title + '&pics=' + avatarURL
    urls.twitter = 'https://twitter.com/intent/tweet?status=' + title + ' ' +
      url

    $this.find('span').click(function () {
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
  },
  initArticle: function () {
    if (Skin._sharerHeadroom && typeof Skin._sharerHeadroom.destroy === 'function') {
      Skin._sharerHeadroom.destroy()
      Skin._sharerHeadroom = null
    }
    var fixEl = $('.post__fix')[0]
    if (fixEl && typeof Headroom === 'function') {
      var postSharer = new Headroom(fixEl, {
        tolerance: 0,
        offset: 48,
        classes: {
          initial: 'post__fix',
          pinned: 'post__fix--pinned',
          unpinned: 'post__fix--unpinned',
          top: 'post__fix',
          notTop: 'post__fix',
          bottom: 'post__fix',
          notBottom: 'post__fix',
        },
      })
      postSharer.init()
      Skin._sharerHeadroom = postSharer
      if (window.BoloNavigation && typeof window.BoloNavigation.onLeave === 'function') {
        window.BoloNavigation.onLeave(function () {
          if (Skin._sharerHeadroom === postSharer) {
            postSharer.destroy()
            Skin._sharerHeadroom = null
          }
        })
      }
    }

    Skin._initShare()
    Skin._initToc()
  },
}
Skin.init()
