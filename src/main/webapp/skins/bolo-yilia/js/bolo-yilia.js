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
 * @description yilia 皮肤脚本
 * @static
 */
var Yilia = {
    /**
     * @description 页面初始化
     */
    init: function () {
        Util.killIE();
        
        this._initToc();
        this.resetTags();
        
        var topVisible;
        $(window).scroll(function () {
            if ($("article").length > 0 && $("article.post").length === 0) {
                $("article:not(.show)").each(function () {
                    if ($(this).offset().top <= $(window).scrollTop() + $(window).height() - $(this).height() / 7) {
                        $(this).addClass("show");
                    }
                });
            }

            var visible = $(window).scrollTop() > $(window).height();
            if (visible) {
                $(".icon-goup").show();
            } else {
                $(".icon-goup").hide();
            }
            if (visible !== topVisible) {
                topVisible = visible;
                window.BoloTheme?.syncControls?.();
            }

            if ($("article.post").length === 1) {
                $("article.post").addClass('show');
            }
        });

        $(window).scroll();
    },
    _initToc: function () {
        if ($('.article__toc li').length === 0) {
            return false;
        }
        $('.side .toc-btn').show();
        this.toggleToc(window.innerWidth >= 1200, true);
        var panel = document.querySelector('.side .toc');
        var links = Array.from(panel.querySelectorAll('.article__toc a'));
        if (window.BoloTOC) BoloTOC.refresh();
        var frame = 0;
        function update() {
            frame = 0;
            var range = document.documentElement.scrollHeight - window.innerHeight;
            var progress = range > 0 ? Math.min(100, Math.max(0, Math.round(window.scrollY / range * 100))) : 100;
            panel.querySelector('.yilia-toc-progress').setAttribute('aria-valuenow', progress);
            panel.querySelector('.yilia-toc-progress span').style.width = progress + '%';
        }
        function schedule() { if (!frame) frame = requestAnimationFrame(update); }
        window.addEventListener('scroll', schedule, {passive: true});
        var desktop = window.innerWidth >= 1200;
        function resize() {
            var next = window.innerWidth >= 1200;
            if (next !== desktop) { desktop = next; Yilia.toggleToc(next, true); }
            schedule();
        }
        window.addEventListener('resize', resize);
        links.forEach(function (link) {
            link.addEventListener('click', function () {
                if (window.innerWidth < 1200) Yilia.toggleToc(false);
            });
        });
        if (window.BoloNavigation) BoloNavigation.onLeave(function () {
            window.removeEventListener('scroll', schedule);
            window.removeEventListener('resize', resize);
            if (frame) cancelAnimationFrame(frame);
            if (panel._yiliaTocAnimation) {
                panel._yiliaTocAnimation.onfinish = null;
                panel._yiliaTocAnimation.cancel();
                panel._yiliaTocAnimation = null;
            }
        });
        update();
    },
    toggleToc: function (open, immediate) {
        var side = document.querySelector('.side');
        var panel = side && side.querySelector('.toc');
        if (!panel) return;
        var hidden = panel.hidden;
        var style = !hidden && window.getComputedStyle ? window.getComputedStyle(panel) : null;
        var fromOpacity = style ? style.opacity : '0';
        var fromTransform = style ? style.transform : 'translateY(8px)';
        if (panel._yiliaTocAnimation) {
            panel._yiliaTocAnimation.onfinish = null;
            panel._yiliaTocAnimation.cancel();
            panel._yiliaTocAnimation = null;
        }
        var button = side.querySelector('.toc-btn');
        if (button) button.setAttribute('aria-expanded', String(open));
        function finish() {
            panel.hidden = !open;
            side.classList.toggle('yilia-toc-open', open);
            panel._yiliaTocAnimation = null;
            if (window.BoloTheme) BoloTheme.refresh();
            if (!open && button) button.focus();
        }
        var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (immediate || reduced || !panel.animate || (!open && hidden)) { finish(); return; }
        // Hide the profile before revealing the card; keep it hidden until the closing animation ends.
        panel.hidden = false;
        side.classList.add('yilia-toc-open');
        if (window.BoloTheme) BoloTheme.refresh();
        var animation = panel.animate([
            {opacity: hidden ? '0' : fromOpacity, transform: hidden ? 'translateY(8px)' : fromTransform},
            {opacity: open ? '1' : '0', transform: open ? 'none' : 'translateY(6px)'}
        ], {duration: open ? 180 : 140, easing: 'cubic-bezier(.2,.7,.2,1)', fill: 'both'});
        panel._yiliaTocAnimation = animation;
        animation.onfinish = function () { if (panel._yiliaTocAnimation === animation) { finish(); animation.cancel(); } };
    },
    resetTags: function () {
        $("a.tag").each(function (i) {
            $(this).addClass("color" + Math.ceil(Math.random() * 4));
        });
    },
    share: function () {
      var $this = $('.share .fn-right')
      var $qrCode = $this.find('.icon-wechat')
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
    }
};

Yilia.init();
