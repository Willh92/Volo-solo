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
        if (typeof LazyLoad !== 'undefined') {
            window.sakuraLazyLoad = new LazyLoad({elements_selector: '.lazyload'});
        }
        document.querySelectorAll('.github-card').forEach(function (card) {
            var user = card.dataset.user || '';
            var repo = card.dataset.repo || '';
            if (!/^[A-Za-z0-9-]{1,39}$/.test(user) || (repo && !/^[A-Za-z0-9_.-]{1,100}$/.test(repo))) return;
            var link = document.createElement('a');
            link.href = 'https://github.com/' + user + (repo ? '/' + repo : '');
            link.textContent = 'GitHub · ' + user + (repo ? '/' + repo : '');
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            card.replaceChildren(link);
        });
        Skin._initToc()
        // The WordPress bundle hid posts until its remote integrations initialized.
        // Keep core navigation and reading independent of those optional services.
        document.querySelectorAll('.post-list-thumb').forEach(function (post) { post.style.opacity = '1'; });
        var header = document.querySelector('.site-header');
        var top = document.querySelector('#moblieGoTop');
        var lastHeaderScrolled = null;
        var lastTopVisible = null;
        var updateHeader = function () {
            var headerScrolled = window.scrollY > 30;
            if (header && headerScrolled !== lastHeaderScrolled) {
                lastHeaderScrolled = headerScrolled;
                header.classList.toggle('yya', headerScrolled);
            }
            var topEl = top || document.querySelector('#moblieGoTop');
            if (topEl) {
                var visible = window.scrollY > 200;
                if (visible !== lastTopVisible) {
                    lastTopVisible = visible;
                    topEl.style.display = visible ? 'grid' : 'none';
                }
            }
        };
        window.addEventListener('scroll', updateHeader, {passive: true});
        updateHeader();
        var menuButtons = document.querySelectorAll('.openNav .iconflat');
        var setMenuOpen = function (open, restoreFocus) {
            document.body.classList.toggle('sakura-menu-open', open);
            menuButtons.forEach(function (button) { button.setAttribute('aria-expanded', String(open)); });
            if (!open && restoreFocus && menuButtons[0]) menuButtons[0].focus();
        };
        menuButtons.forEach(function (button) {
            button.setAttribute('role', 'button');
            button.setAttribute('tabindex', '0');
            button.setAttribute('aria-controls', 'mo-nav');
            button.setAttribute('aria-expanded', 'false');
            var toggle = function () {
                setMenuOpen(!document.body.classList.contains('sakura-menu-open'), false);
            };
            button.addEventListener('click', toggle);
            button.addEventListener('keydown', function (event) {
                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggle(); }
            });
        });
        document.querySelectorAll('.sakura-menu-backdrop, #mo-nav a').forEach(function (button) {
            button.addEventListener('click', function () { setMenuOpen(false, false); });
        });
        document.querySelectorAll('#show-nav').forEach(function (button) {
            button.addEventListener('click', function () {
                var nav = document.querySelector('.site-top nav');
                if (nav) nav.classList.toggle('hide');
            });
        });
        var search = document.querySelector('.js-search');
        var searchTrigger;
        var closeSearch = function () {
            if (!search || !search.classList.contains('is-visible')) return;
            search.classList.remove('is-visible');
            if (searchTrigger && searchTrigger.isConnected) searchTrigger.focus();
        };
        document.querySelectorAll('.js-toggle-search, .search_close').forEach(function (button) {
            button.addEventListener('click', function () {
                if (!search) return;
                if (button.classList.contains('search_close')) { closeSearch(); return; }
                searchTrigger = button;
                search.classList.add('is-visible');
                search.querySelector('input').focus();
            });
        });
        var onKeyDown = function (event) {
            if (event.key === 'Escape') closeSearch();
            if (event.key === 'Escape' && document.body?.classList.contains('sakura-menu-open')) setMenuOpen(false, true);
        };
        document.addEventListener('keydown', onKeyDown);
        if (window.BoloNavigation) {
            window.BoloNavigation.onLeave(function () {
                if (window.removeEventListener) window.removeEventListener('scroll', updateHeader);
                if (document.removeEventListener) document.removeEventListener('keydown', onKeyDown);
            });
        }
        document.querySelectorAll('.entry-content img').forEach(function (img) {
            if (img.parentElement.tagName !== 'A' && !img.closest('.volo-bookshelf, .volo-lock-card') && window.jQuery && jQuery.fn.fancybox) {
                var url;
                try { url = new URL(img.currentSrc || img.src, location.href); } catch (error) { return; }
                if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
                var link = document.createElement('a');
                link.href = url.href;
                link.dataset.fancybox = 'article';
                img.replaceWith(link); link.appendChild(img);
            }
        });
        document.querySelectorAll('img').forEach(function (img) {
            if (img.complete && img.naturalWidth === 0 && img.getAttribute('src')) window.imgError(img);
        });
    },
    _initToc: function () {
        // Every author image remains visible; the cover uses a separate element.
    },
    _initShare: function () {
    },
    initArticle: function () {
        Skin._initShare()
        Skin._initToc()
    },
}
window.headertop_down = function () {
    var content = document.querySelector('#content');
    if (content) content.scrollIntoView({behavior: 'smooth'});
};
window.topFunction = function () { window.scrollTo({top: 0, behavior: 'smooth'}); };
window.imgError = function (image) {
    if (image.dataset.voloFallback) return;
    image.dataset.voloFallback = 'true';
    image.src = (window.Label ? Label.staticServePath : '') + '/images/volo-mark.svg';
};
Skin.init()
