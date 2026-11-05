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
/* Bolo Studio, AGPL-3.0-or-later. */
var Skin = {init: function () {
  if (window.BoloTheme) BoloTheme.refresh();
  if (window.VoloStudioCleanup) window.VoloStudioCleanup();
  document.querySelectorAll('.studio-post-cover img').forEach(function (image) {
    if (image.dataset.studioCoverBound) return;
    image.dataset.studioCoverBound = 'true';
    function discardBrokenCover () {
      var cover = image.closest('.studio-post-cover');
      if (cover) cover.classList.add('studio-post-cover--empty');
      image.remove();
    }
    image.addEventListener('error', discardBrokenCover, {once: true});
    if (image.complete && image.naturalWidth === 0) discardBrokenCover();
  });
  document.querySelectorAll('.studio-nav-icon,.studio-friend-link img').forEach(function (image) {
    if (image.dataset.studioIconBound) return;
    image.dataset.studioIconBound = 'true';
    image.addEventListener('error', function () { image.remove(); }, {once: true});
    if (image.complete && image.naturalWidth === 0) image.remove();
  });
  var top = document.querySelector('#studioBackToTop');
  if (!top) return;
  var search = document.querySelector('.studio-header-search');
  var more = document.querySelector('.studio-nav-more');
  var mobileNavigation = window.matchMedia('(max-width: 680px)');
  function closeHeaderPanels (event) {
    [search, more].forEach(function (panel) {
      if (!panel?.open) return;
      if (event.type === 'click' && !panel.contains(event.target)) panel.open = false;
      if (event.key === 'Escape') { panel.open = false; panel.querySelector('summary').focus(); }
    });
  }
  function openSearch () {
    if (search.open) {
      if (more) more.open = false;
      window.BoloTheme?.closeMenu?.();
      requestAnimationFrame(function () { if (search.open && search.isConnected) search.querySelector('input').focus(); });
    }
  }
  function openNavigation () {
    if (!more.open) return;
    if (search) search.open = false;
    window.BoloTheme?.closeMenu?.();
  }
  function resetNavigation () { if (more) more.open = false; }
  function followNavigation (event) { if (event.target.closest('a')) resetNavigation(); }
  search?.addEventListener('toggle', openSearch);
  more?.addEventListener('toggle', openNavigation);
  more?.addEventListener('click', followNavigation);
  mobileNavigation.addEventListener('change', resetNavigation);
  document.addEventListener('click', closeHeaderPanels, true);
  document.addEventListener('keydown', closeHeaderPanels);
  var animation = 0;
  var lastHidden = null;
  function cancelScroll () { if (animation) cancelAnimationFrame(animation); animation = 0; }
  function update () {
    var nextHidden = window.scrollY < 320;
    if (nextHidden === lastHidden) return;
    lastHidden = nextHidden;
    top.hidden = nextHidden;
  }
  function goTop () {
    cancelScroll();
    var start = window.scrollY;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) { window.scrollTo(0, 0); return; }
    var duration = Math.min(560, Math.max(240, 200 + Math.log2(start / 300 + 1) * 70));
    var began;
    function step (time) {
      if (began === undefined) began = time;
      var progress = Math.min(1, (time - began) / duration);
      window.scrollTo(0, Math.round(start * Math.pow(1 - progress, 3)));
      if (progress < 1) animation = requestAnimationFrame(step);
      else animation = 0;
    }
    animation = requestAnimationFrame(step);
  }
  top.addEventListener('click', goTop);
  window.addEventListener('scroll', update, {passive: true});
  ['wheel', 'touchstart', 'pointerdown', 'keydown'].forEach(function (name) { window.addEventListener(name, cancelScroll, {passive: true}); });
  window.VoloStudioCleanup = function () {
    search?.removeEventListener('toggle', openSearch);
    more?.removeEventListener('toggle', openNavigation);
    more?.removeEventListener('click', followNavigation);
    mobileNavigation.removeEventListener('change', resetNavigation);
    document.removeEventListener('click', closeHeaderPanels, true);
    document.removeEventListener('keydown', closeHeaderPanels);
    cancelScroll(); top.removeEventListener('click', goTop);
    window.removeEventListener('scroll', update);
    ['wheel', 'touchstart', 'pointerdown', 'keydown'].forEach(function (name) { window.removeEventListener(name, cancelScroll); });
  };
  if (window.BoloNavigation) BoloNavigation.onLeave(window.VoloStudioCleanup);
  update();
}}; Skin.init();
