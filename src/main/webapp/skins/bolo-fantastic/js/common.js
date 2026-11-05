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
// burger
(function () {
    var burger = document.querySelector('.burger');
    if (!burger || !burger.dataset.target) return;
    var menu = document.querySelector('#' + burger.dataset.target);
    if (!menu) return;
    burger.addEventListener('click', function () {
        burger.classList.toggle('is-active');
        menu.classList.toggle('is-active');
        burger.setAttribute('aria-expanded', String(menu.classList.contains('is-active')));
    });
})();

// backtotop
$(document).ready(function () {
    var $button = $('#back-to-top');
    var $footer = $('footer.footer');
    var $mainColumn = $('.column-main');
    var $leftSidebar = $('.column-left');
    var $rightSidebar = $('.column-right');
    var lastScrollTop = 0;
    var rightMargin = 20;
    var bottomMargin = 20;
    var lastState = null;
    var lastControlWidth = null;
    var lastControlRadius = null;
    var lastControlHeight = null;
    var updateRaf = 0;
    var state = {
        base: {
            classname: 'card has-text-centered',
            left: '',
            width: 64,
            bottom: bottomMargin,
            'border-radius': 4
        }
    };
    state['desktop-hidden'] = Object.assign({}, state.base, {
        classname: state.base.classname + ' rise-up',
    });
    state['desktop-visible'] = Object.assign({}, state['desktop-hidden'], {
        classname: state['desktop-hidden'].classname + ' fade-in',
    });
    state['desktop-dock'] = Object.assign({}, state['desktop-visible'], {
        classname: state['desktop-visible'].classname + ' fade-in',
        width: 40,
        'border-radius': '50%'
    });
    state['mobile-hidden'] = Object.assign({}, state.base, {
        classname: state.base.classname + ' fade-in',
        right: rightMargin
    });
    state['mobile-visible'] = Object.assign({}, state['mobile-hidden'], {
        classname: state['mobile-hidden'].classname + ' rise-up',
    });

    function isStateEquals(prev, next) {
        for (var prop in prev) {
            if (!Object.prototype.hasOwnProperty.call(next, prop) || next[prop] !== prev[prop]) {
                return false;
            }
        }
        for (var nextProp in next) {
            if (!Object.prototype.hasOwnProperty.call(prev, nextProp) || prev[nextProp] !== next[nextProp]) {
                return false;
            }
        }
        return true;
    }

    function applyState(next) {
        next = Object.assign({}, next);
        var mobile = window.innerWidth <= 600;
        var appearance = document.querySelector('.bolo-appearance--floating');
        if (mobile) next.bottom += (document.querySelector('.mobile-reading-toc') ? 58 : 0) + (appearance ? 58 : 0);
        var rootStyle = document.documentElement.style;
        var nextWidth = next.width + 'px';
        if (nextWidth !== lastControlWidth) {
            lastControlWidth = nextWidth;
            rootStyle.setProperty('--fantastic-control-width', nextWidth);
        }
        var nextRadius = typeof next['border-radius'] === 'number' ? next['border-radius'] + 'px' : String(next['border-radius']);
        if (nextRadius !== lastControlRadius) {
            lastControlRadius = nextRadius;
            rootStyle.setProperty('--fantastic-control-radius', nextRadius);
        }
        if (appearance) {
            var besideReturn = next.classname.includes('fade-in') && next.classname.includes('rise-up');
            var nextAppLeft = besideReturn && typeof next.left === 'number' ? next.left + 'px' : 'auto';
            var nextAppRight = besideReturn && typeof next.left === 'number' ? 'auto' : (next.right || rightMargin) + 'px';
            var nextAppBottom = (mobile ? 78 : besideReturn ? next.bottom + 54 : 80) + 'px';
            if (appearance.style.left !== nextAppLeft) appearance.style.left = nextAppLeft;
            if (appearance.style.right !== nextAppRight) appearance.style.right = nextAppRight;
            if (appearance.style.bottom !== nextAppBottom) appearance.style.bottom = nextAppBottom;
        }
        if (lastState !== null && isStateEquals(lastState, next)) {
            return;
        }
        $button.attr('class', next.classname);
        for (let prop in next) {
            if (prop === 'classname') {
                continue;
            }
            $button.css(prop, next[prop]);
        }
        var nextHeight = $button.outerHeight() + 'px';
        if (nextHeight !== lastControlHeight) {
            lastControlHeight = nextHeight;
            rootStyle.setProperty('--fantastic-control-height', nextHeight);
        }
        lastState = next;
    }

    function isDesktop() {
        return window.innerWidth >= 1078;
    }

    function isTablet() {
        return window.innerWidth >= 768 && !isDesktop();
    }

    function isScrollUp(scrollTop) {
        return scrollTop < lastScrollTop && scrollTop > 0;
    }

    function hasLeftSidebar() {
        return $leftSidebar.length > 0;
    }

    function hasRightSidebar() {
        return $rightSidebar.length > 0;
    }

    function getRightSidebarBottom() {
        if (!hasRightSidebar()) {
            return 0;
        }
        var widgets = $rightSidebar.find('.widget');
        if (!widgets.length) return 0;
        return Math.max.apply(null, widgets.map(function () {
            return $(this).offset().top + $(this).outerHeight(true);
        }));
    }

    function update() {
        updateRaf = 0;
        if (!$button.length) return;
        var scrollTop = $(window).scrollTop();
        var winHeight = $(window).height();
        var scrollBottom = scrollTop + winHeight;
        // desktop mode or tablet mode with only right sidebar enabled
        if ((isDesktop() || (isTablet() && !hasLeftSidebar() && hasRightSidebar())) && $mainColumn.length && $footer.length) {
            var nextState;
            var mainOuterWidth = $mainColumn.outerWidth();
            var padding = (mainOuterWidth - $mainColumn.width()) / 2;
            var btnWidth = $button.outerWidth(true);
            var btnHeight = $button.outerHeight(true);
            var maxLeft = $(window).width() - btnWidth - rightMargin;
            var maxBottom = $footer.offset().top + btnHeight / 2 + bottomMargin;
            if (scrollTop == 0 || scrollBottom < getRightSidebarBottom() + padding + btnHeight) {
                nextState = state['desktop-hidden'];
            } else if (scrollBottom < maxBottom) {
                nextState = state['desktop-visible'];
            } else {
                nextState = Object.assign({}, state['desktop-dock'], {
                    bottom: scrollBottom - maxBottom + bottomMargin
                });
            }

            var left = $mainColumn.offset().left + mainOuterWidth + padding;
            nextState = Object.assign({}, nextState, {
                left: Math.min(left, maxLeft)
            });
            applyState(nextState);
        } else {
            // mobile and tablet mode
            if (!isScrollUp(scrollTop)) {
                applyState(state['mobile-hidden']);
            } else {
                applyState(state['mobile-visible']);
            }
            lastScrollTop = scrollTop;
        }
    }

    function scheduleUpdate() {
        if (!updateRaf) {
            updateRaf = requestAnimationFrame(update);
        }
    }

    update();
    $(window).off('.boloFantastic').on('resize.boloFantastic scroll.boloFantastic', scheduleUpdate);
    if (window.BoloNavigation) {
        window.BoloNavigation.onLeave(function () {
            if (updateRaf) {
                cancelAnimationFrame(updateRaf);
                updateRaf = 0;
            }
            $(window).off('.boloFantastic');
        });
    }

    $('#back-to-top').on('click', function () {
        $('body, html').animate({ scrollTop: 0 }, 400);
    });
});

// gallery
document.addEventListener('DOMContentLoaded', function () {
    if (typeof ($.fn.lightGallery) === 'function') {
        $('.article').lightGallery({ selector: '.gallery-item' });
    }
    if (typeof ($.fn.justifiedGallery) === 'function') {
        if ($('.justified-gallery > p > .gallery-item').length) {
            $('.justified-gallery > p > .gallery-item').unwrap();
        }
        $('.justified-gallery').justifiedGallery();
    }
});

// main
(function ($) {
    $('.article img:not(".not-gallery-item")').each(function () {
        // wrap images with link and add caption if possible
        if ($(this).parent('a').length === 0 && !$(this).closest('.volo-bookshelf, .volo-lock-card').length) {
            $(this).wrap('<a class="gallery-item" href="' + $(this).attr('src') + '"></a>');
            if (this.alt) {
                $(this).after('<div class="has-text-centered is-size-6 has-text-grey caption">' + this.alt + '</div>');
            }
        }
    });

    if (typeof (moment) === 'function') {
        $('.article-meta time').each(function () {
            $(this).text(moment($(this).attr('datetime')).fromNow());
        });
    }

    $('.article > .content > table').each(function () {
        if ($(this).width() > $(this).parent().width()) {
            $(this).wrap('<div class="table-overflow"></div>');
        }
    });

    function adjustNavbar() {
        const navbarWidth = $('.navbar-main .navbar-start').outerWidth() + $('.navbar-main .navbar-end').outerWidth();
        if ($(document).outerWidth() < navbarWidth) {
            $('.navbar-main .navbar-menu').addClass('is-flex-start');
        } else {
            $('.navbar-main .navbar-menu').removeClass('is-flex-start');
        }
    }
    adjustNavbar();
    $(window).on('resize.boloFantasticNav', adjustNavbar);
    if (window.BoloNavigation) {
        window.BoloNavigation.onLeave(function () {
            $(window).off('.boloFantasticNav');
        });
    }

    $('figure.highlight table').wrap('<div class="highlight-body">');
    if (typeof (IcarusThemeSettings) !== 'undefined' &&
        typeof (IcarusThemeSettings.article) !== 'undefined' &&
        typeof (IcarusThemeSettings.article.highlight) !== 'undefined') {
        if (typeof (ClipboardJS) !== 'undefined' && IcarusThemeSettings.article.highlight.clipboard) {
            $('figure.highlight').each(function () {
                var id = 'code-' + Date.now() + (Math.random() * 1000 | 0);
                var button = '<a href="javascript:;" class="copy" title="Copy" data-clipboard-target="#' + id + ' .code"><i class="fas fa-copy"></i></a>';
                $(this).attr('id', id);
                if ($(this).find('figcaption').length) {
                    $(this).find('figcaption').prepend(button);
                } else {
                    $(this).prepend('<figcaption>' + button + '</figcaption>');
                }
            });
            new ClipboardJS('.highlight .copy');
        }
        var fold = IcarusThemeSettings.article.highlight.fold;
        if (fold.trim()) {
            var button = '<span class="fold">' + (fold === 'unfolded' ? '<i class="fas fa-angle-down"></i>' : '<i class="fas fa-angle-right"></i>') + '</span>';
            $('figure.highlight').each(function () {
                if ($(this).find('figcaption').length) {
                    $(this).find('figcaption').prepend(button);
                } else {
                    $(this).prepend('<figcaption>' + button + '</figcaption>');
                }
            });

            function toggleFold(codeBlock, isFolded) {
                var $toggle = $(codeBlock).find('.fold i');
                !isFolded ? $(codeBlock).removeClass('folded') : $(codeBlock).addClass('folded');
                !isFolded ? $toggle.removeClass('fa-angle-right') : $toggle.removeClass('fa-angle-down');
                !isFolded ? $toggle.addClass('fa-angle-down') : $toggle.addClass('fa-angle-right');
            }

            $('figure.highlight').each(function () {
                toggleFold(this, fold === 'folded');
            });
            $('figure.highlight figcaption .fold').click(function () {
                var $code = $(this).closest('figure.highlight');
                toggleFold($code.eq(0), !$code.hasClass('folded'));
            });
        }
    }

    var $toc = $('#toc');
    if ($toc.length > 0) {
        var $mask = $('#toc-mask');
        if (!$mask.length) {
            $mask = $('<div>').attr('id', 'toc-mask');
            $('body').append($mask);
        }

        function toggleToc() {
            if (window.innerWidth > 1023) return;
            $toc.toggleClass('is-active');
            $mask.toggleClass('is-active');
        }

        $toc.on('click', 'a, .fantastic-toc-close', toggleToc);
        $mask.on('click', toggleToc);
        $('.navbar-main .catalogue').on('click', toggleToc);
    }
})(jQuery);

// widget_pin
if (typeof Swiper === 'function' && document.querySelector('.blog-slider')) {
    var swiper = new Swiper('.blog-slider', {
        spaceBetween: 30,
        effect: 'fade',
        pagination: {
            el: '.blog-slider__pagination',
            clickable: true,
        }
    });
}
