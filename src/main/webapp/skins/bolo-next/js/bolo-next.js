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
 * @description next 皮肤脚本
 * @static
 */
var NexT = {
  init: function () {
    this.initBackToTop();
    function closeSidebarImmediate () {
      var $toggle = $('.sidebar-toggle');
      var $sidebar = $('.sidebar');
      if ($toggle.hasClass('sidebar-active')) {
        $toggle.removeClass('sidebar-active');
        $sidebar.stop(true, true).css('right', -320);
        $sidebar.find('section').stop(true, true).css('opacity', 0);
      }
      $('body').stop(true, true).css('padding-right', '');
    }
    $('.sidebar-toggle').click(function () {
      var $sidebar = $('.sidebar');
      if ($(this).hasClass('sidebar-active')) {
        $(this).removeClass('sidebar-active');

        $('body').animate({
          'padding-right': 0
        });
        $sidebar.animate({
          right: -320
        });
        $sidebar.find('section').css('opacity', 0);
      } else {
        $(this).addClass('sidebar-active');
        $('body').animate({
          'padding-right': 320
        });
        $sidebar.animate({
          right: 0
        }, function () {
          $sidebar.find('section:first').animate({
            'opacity': 1
          });
        });
      }
    });

    $(window).off('resize.boloNextSidebar').on('resize.boloNextSidebar', function () {
      if ($(window).width() < 1200) {
        closeSidebarImmediate();
      }
    });
    window.BoloNavigation?.onLeave(function () {
      $(window).off('resize.boloNextSidebar');
      closeSidebarImmediate();
    });

    $('.site-nav-toggle').click(function () {
      $('.site-nav').slideToggle();
    });

    $(document).ready(function () {
      // Fade the content without moving chapters after the reader starts navigating.
      $('.main').animate({opacity: 1}, 400, function () {
        if ($('.article__toc li').length > 0 && $(window).width() >= 1200) {
          $('.sidebar-toggle').click();
        }
      });
      setTimeout(function () {
        // logo animate
        $('.logo-wrap').css('opacity', 1);
        $('.logo-line-before i').animate({
          'left': '0'
        }, function () {
          $('.site-title').css('opacity', 1).animate({
            'top': 0
          }, function () {
            $('.menu').css('opacity', 1).animate({
              'margin-top': '15px'
            });
          });
        });

        $('.logo-line-after i').animate({
          'right': '0'
        });
      }, 500);
    });
  },
  initBackToTop: function () {
    var control = document.querySelector('.back-to-top');
    if (!control) return;
    control.setAttribute('role', 'button');
    control.setAttribute('aria-label', '返回顶部');
    var lastVisible = null;
    function update () {
      var visible = window.scrollY > 240;
      if (visible === lastVisible) return;
      lastVisible = visible;
      control.classList.toggle('is-visible', visible);
      control.setAttribute('aria-hidden', String(!visible));
      control.tabIndex = visible ? 0 : -1;
      control.inert = !visible;
    }
    function onKey (event) {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); Util.goTop(); }
    }
    window.addEventListener('scroll', update, {passive: true});
    control.addEventListener('keydown', onKey);
    update();
    window.BoloNavigation?.onLeave(function () {
      window.removeEventListener('scroll', update);
      control.removeEventListener('keydown', onKey);
    });
  },
  initArticle: function () {
    if ($('.article__toc li').length > 0 && $(window).width() >= 1200) {
      // add color to sidebar menu
      $('.sidebar-toggle').addClass('has-toc');
      this.initToc();
    }
  },
  initToc: function () {
    var $articleTocs = $('.vditor-reset [id^=b3_solo_h]'),
      $articleToc = $('.article__toc'),
      tocRaf = 0;

    function syncToc () {
      tocRaf = 0;
      if ($('.article__toc li').length === 0) {
        return false;
      }

      var toc = [];
      $articleTocs.each(function () {
        toc.push({
          id: this.id,
          offsetTop: this.offsetTop
        });
      });
      if (!toc.length) return false;

      var scrollTop = $(window).scrollTop();
      for (var i = 0, iMax = toc.length; i < iMax; i++) {
        if (scrollTop < toc[i].offsetTop + 200) {
          $articleToc.find('li').removeClass('current');
          var index = i > 0 ? i - 1 : 0;
          $articleToc.find('a[href="#' + toc[index].id + '"]').parent().addClass('current');
          break;
        }
      }
      if (scrollTop >= toc[toc.length - 1].offsetTop + 200) {
        $articleToc.find('li').removeClass('current');
        $articleToc.find('li:last').addClass('current');
      }
    }

    $(window).off('scroll.boloNextToc').on('scroll.boloNextToc', function () {
      if (!tocRaf) {
        tocRaf = requestAnimationFrame(syncToc);
      }
    });

    syncToc();
    window.BoloNavigation?.onLeave(function () {
      if (tocRaf) {
        cancelAnimationFrame(tocRaf);
        tocRaf = 0;
      }
      $(window).off('scroll.boloNextToc');
    });
  }
};
NexT.init();
