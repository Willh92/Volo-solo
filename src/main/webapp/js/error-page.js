/* Cancelable return-home countdown for the forbidden page. */
(function () {
  var notice = document.querySelector('[data-home-countdown]');
  if (!notice) return;
  var remaining = Number(notice.dataset.homeCountdown);
  var destination = new URL(notice.dataset.homeUrl, location.href);
  if (destination.origin !== location.origin || !Number.isFinite(remaining) || remaining <= 0) return;
  var value = notice.querySelector('[data-countdown-value]');
  var timer = setInterval(function () {
    if (document.hidden) return;
    remaining -= 1;
    value.textContent = remaining;
    if (remaining <= 0) { clearInterval(timer); location.assign(destination.href); }
  }, 1000);
  notice.querySelector('[data-countdown-cancel]').addEventListener('click', function () {
    clearInterval(timer);
    notice.textContent = '已取消自动跳转，你可以留在此页。';
  });
  window.BoloNavigation?.onLeave(function () { clearInterval(timer); });
})();
