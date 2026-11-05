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
;(function ($) {
  if (!$ || !$.fn) return;
  $.fn.circleMagic = function (options) {
    var container = this[0];
    if (!container) return this;
    if (typeof container._boloCircleCleanup === 'function') {
      container._boloCircleCleanup();
    }

    var width = 0, height = 0, canvas = null, ctx = null;
    var animateHeader = true, inViewport = true, disposed = false, rafId = 0;
    var circles = [];
    var reducedMedia = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    var observer = null;

    var settings = $.extend({
      color: 'rgba(255,255,255,.5)',
      radius: 10,
      density: 0.3,
      clearOffset: 0.2
    }, options);

    function canRun() {
      return !disposed &&
        animateHeader &&
        inViewport &&
        !document.hidden &&
        !(reducedMedia && reducedMedia.matches) &&
        container.isConnected !== false;
    }

    function syncPowerState() {
      animateHeader = !document.hidden && !(reducedMedia && reducedMedia.matches) && inViewport;
      if (canRun()) {
        if (!rafId) rafId = requestAnimationFrame(animate);
      } else if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
    }

    function initCanvas() {
      canvas = container.querySelector('canvas.bolo-circle-canvas, #canvas');
      if (!canvas) {
        canvas = document.createElement('canvas');
        canvas.id = 'canvas';
        canvas.className = 'bolo-circle-canvas';
        container.appendChild(canvas);
      }
      if (canvas.parentElement) {
        canvas.parentElement.style.overflow = 'hidden';
      }
    }

    function resize() {
      if (!container || !canvas) return;
      width = container.clientWidth || container.offsetWidth || 0;
      height = container.clientHeight || container.offsetHeight || 0;
      container.height = height + 'px';
      canvas.width = width;
      canvas.height = height;
    }

    function animate() {
      rafId = 0;
      if (!canRun() || !ctx) return;
      ctx.clearRect(0, 0, width, height);
      for (var i = 0; i < circles.length; i++) {
        circles[i].draw();
      }
      rafId = requestAnimationFrame(animate);
    }

    function randomColor() {
      var r = Math.floor(Math.random() * 255);
      var g = Math.floor(Math.random() * 255);
      var b = Math.floor(Math.random() * 255);
      var alpha = Math.random().toPrecision(2);
      return 'rgba(' + r + ', ' + g + ', ' + b + ', ' + alpha + ')';
    }

    function Circle() {
      var that = this;
      that.pos = {};
      init();

      function init() {
        that.pos.x = Math.random() * width;
        that.pos.y = height + Math.random() * 100;
        that.alpha = 0.1 + Math.random() * settings.clearOffset;
        that.scale = 0.1 + Math.random() * 0.3;
        that.speed = Math.random();
        that.color = settings.color === 'random' ? randomColor() : settings.color;
      }

      this.draw = function () {
        if (that.alpha <= 0) init();
        that.pos.y -= that.speed;
        that.alpha -= 0.0005;
        ctx.beginPath();
        ctx.arc(that.pos.x, that.pos.y, that.scale * settings.radius, 0, 2 * Math.PI, false);
        ctx.fillStyle = that.color;
        ctx.fill();
        ctx.closePath();
      };
    }

    width = container.offsetWidth || 0;
    height = container.offsetHeight || 0;
    initCanvas();
    canvas.width = width;
    canvas.height = height;
    canvas.style.position = 'absolute';
    canvas.style.left = '0';
    canvas.style.bottom = '0';
    canvas.style.pointerEvents = 'none';
    ctx = canvas.getContext('2d');

    var count = Math.min(240, Math.max(0, Math.floor(width * settings.density)));
    for (var x = 0; x < count; x++) {
      circles.push(new Circle());
    }

    window.addEventListener('resize', resize, { passive: true });
    document.addEventListener('visibilitychange', syncPowerState);
    if (reducedMedia) {
      if (reducedMedia.addEventListener) reducedMedia.addEventListener('change', syncPowerState);
      else if (reducedMedia.addListener) reducedMedia.addListener(syncPowerState);
    }
    if (typeof IntersectionObserver === 'function') {
      observer = new IntersectionObserver(function (entries) {
        for (var i = 0; i < entries.length; i++) {
          if (entries[i].target === container) {
            inViewport = entries[i].isIntersecting;
            syncPowerState();
          }
        }
      });
      observer.observe(container);
    }

    function cleanup() {
      disposed = true;
      animateHeader = false;
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      window.removeEventListener('resize', resize);
      document.removeEventListener('visibilitychange', syncPowerState);
      if (reducedMedia) {
        if (reducedMedia.removeEventListener) reducedMedia.removeEventListener('change', syncPowerState);
        else if (reducedMedia.removeListener) reducedMedia.removeListener(syncPowerState);
      }
      if (observer) {
        observer.disconnect();
        observer = null;
      }
      if (container._boloCircleCleanup === cleanup) {
        container._boloCircleCleanup = null;
      }
    }

    container._boloCircleCleanup = cleanup;
    if (window.BoloNavigation) window.BoloNavigation.onLeave(cleanup);
    syncPowerState();
    return this;
  };
})(window.jQuery || window.$);

/**
 * Ribbons Class File.
 * Creates low-poly ribbons background effect with tab/reduced-motion power saving.
 */
(function (name, factory) {
  if (typeof window === 'object') {
    window[name] = factory();
  }
})('Ribbons', function () {
  var _w = window,
    _d = document.documentElement;

  var random = function () {
    if (arguments.length === 1) {
      if (Array.isArray(arguments[0])) {
        var index = Math.round(random(0, arguments[0].length - 1));
        return arguments[0][index];
      }
      return random(0, arguments[0]);
    } else if (arguments.length === 2) {
      return Math.random() * (arguments[1] - arguments[0]) + arguments[0];
    }
    return 0;
  };

  var screenInfo = function () {
    var _b = document.body;
    var width = Math.max(0, _w.innerWidth || _d.clientWidth || (_b && _b.clientWidth) || 0),
      height = Math.max(0, _w.innerHeight || _d.clientHeight || (_b && _b.clientHeight) || 0),
      scrollx = Math.max(0, _w.pageXOffset || _d.scrollLeft || (_b && _b.scrollLeft) || 0) - (_d.clientLeft || 0),
      scrolly = Math.max(0, _w.pageYOffset || _d.scrollTop || (_b && _b.scrollTop) || 0) - (_d.clientTop || 0);

    return {
      width: width,
      height: height,
      ratio: width / (height || 1),
      centerx: width / 2,
      centery: height / 2,
      scrollx: scrollx,
      scrolly: scrolly
    };
  };

  var Point = function (x, y) {
    this.x = 0;
    this.y = 0;
    this.set(x, y);
  };
  Point.prototype = {
    constructor: Point,
    set: function (x, y) { this.x = x || 0; this.y = y || 0; },
    copy: function (point) { this.x = point.x || 0; this.y = point.y || 0; return this; },
    multiply: function (x, y) { this.x *= x || 1; this.y *= y || 1; return this; },
    divide: function (x, y) { this.x /= x || 1; this.y /= y || 1; return this; },
    add: function (x, y) { this.x += x || 0; this.y += y || 0; return this; },
    subtract: function (x, y) { this.x -= x || 0; this.y -= y || 0; return this; },
    clampX: function (min, max) { this.x = Math.max(min, Math.min(this.x, max)); return this; },
    clampY: function (min, max) { this.y = Math.max(min, Math.min(this.y, max)); return this; },
    flipX: function () { this.x *= -1; return this; },
    flipY: function () { this.y *= -1; return this; }
  };

  var Factory = function (options) {
    if (_w._boloActiveRibbons && typeof _w._boloActiveRibbons.destroy === 'function') {
      _w._boloActiveRibbons.destroy();
    }
    this._canvas = null;
    this._context = null;
    this._sto = null;
    this._rafId = 0;
    this._disposed = false;
    this._width = 0;
    this._height = 0;
    this._scroll = 0;
    this._ribbons = [];
    this._reducedMedia = _w.matchMedia ? _w.matchMedia('(prefers-reduced-motion: reduce)') : null;
    this._options = {
      colorSaturation: '80%',
      colorBrightness: '60%',
      colorAlpha: 0.65,
      colorCycleSpeed: 6,
      verticalPosition: 'center',
      horizontalSpeed: 150,
      ribbonCount: 3,
      strokeSize: 0,
      parallaxAmount: -0.5,
      animateSections: true
    };
    this._onDraw = this._onDraw.bind(this);
    this._onResize = this._onResize.bind(this);
    this._onScroll = this._onScroll.bind(this);
    this._syncPower = this._syncPower.bind(this);
    this.destroy = this.destroy.bind(this);
    this.setOptions(options);
    this.init();
    _w._boloActiveRibbons = this;
  };

  Factory.prototype = {
    constructor: Factory,

    setOptions: function (options) {
      if (typeof options === 'object') {
        for (var key in options) {
          if (Object.prototype.hasOwnProperty.call(options, key)) {
            this._options[key] = options[key];
          }
        }
      }
    },

    _canAnimate: function () {
      return !this._disposed &&
        !document.hidden &&
        !(this._reducedMedia && this._reducedMedia.matches);
    },

    _syncPower: function () {
      if (this._canAnimate()) {
        if (!this._rafId) {
          this._rafId = requestAnimationFrame(this._onDraw);
        }
      } else if (this._rafId) {
        cancelAnimationFrame(this._rafId);
        this._rafId = 0;
      }
    },

    init: function () {
      try {
        var existing = document.getElementById('bolo-ribbons-canvas');
        this._canvas = existing || document.createElement('canvas');
        this._canvas.id = 'bolo-ribbons-canvas';
        this._canvas.style.display = 'block';
        this._canvas.style.position = 'fixed';
        this._canvas.style.margin = '0';
        this._canvas.style.padding = '0';
        this._canvas.style.border = '0';
        this._canvas.style.outline = '0';
        this._canvas.style.left = '0';
        this._canvas.style.top = '0';
        this._canvas.style.width = '100%';
        this._canvas.style.height = '100%';
        this._canvas.style.pointerEvents = 'none';
        this._canvas.style['z-index'] = '-1';
        this._onResize();

        this._context = this._canvas.getContext('2d');
        this._context.clearRect(0, 0, this._width, this._height);
        this._context.globalAlpha = this._options.colorAlpha;

        _w.addEventListener('resize', this._onResize, { passive: true });
        document.addEventListener('visibilitychange', this._syncPower);
        if (this._reducedMedia) {
          if (this._reducedMedia.addEventListener) this._reducedMedia.addEventListener('change', this._syncPower);
          else if (this._reducedMedia.addListener) this._reducedMedia.addListener(this._syncPower);
        }
        if (!this._canvas.isConnected && document.body) {
          document.body.appendChild(this._canvas);
        }
        if (_w.BoloNavigation) {
          _w.BoloNavigation.onLeave(this.destroy);
        }
      } catch (e) {
        console.warn('Canvas Context Error: ' + e.toString());
        return;
      }
      this._syncPower();
    },

    destroy: function () {
      if (this._disposed) return;
      this._disposed = true;
      if (this._rafId) {
        cancelAnimationFrame(this._rafId);
        this._rafId = 0;
      }
      _w.removeEventListener('resize', this._onResize);
      document.removeEventListener('visibilitychange', this._syncPower);
      if (this._reducedMedia) {
        if (this._reducedMedia.removeEventListener) this._reducedMedia.removeEventListener('change', this._syncPower);
        else if (this._reducedMedia.removeListener) this._reducedMedia.removeListener(this._syncPower);
      }
      if (_w._boloActiveRibbons === this) {
        _w._boloActiveRibbons = null;
      }
    },

    addRibbon: function () {
      var dir = Math.round(random(1, 9)) > 5 ? 'right' : 'left',
        stop = 1000,
        hide = 200,
        min = 0 - hide,
        max = this._width + hide,
        movex = 0,
        movey = 0,
        startx = dir === 'right' ? min : max,
        starty = Math.round(random(0, this._height));

      if (/^(top|min)$/i.test(this._options.verticalPosition)) {
        starty = hide;
      } else if (/^(middle|center)$/i.test(this._options.verticalPosition)) {
        starty = this._height / 2;
      } else if (/^(bottom|max)$/i.test(this._options.verticalPosition)) {
        starty = this._height - hide;
      }

      var ribbon = [],
        point1 = new Point(startx, starty),
        point2 = new Point(startx, starty),
        point3 = null,
        color = Math.round(random(0, 360)),
        delay = 0;

      while (stop > 0) {
        stop--;
        movex = Math.round((Math.random() - 0.2) * this._options.horizontalSpeed);
        movey = Math.round((Math.random() - 0.5) * (this._height * 0.25));

        point3 = new Point();
        point3.copy(point2);

        if (dir === 'right') {
          point3.add(movex, movey);
          if (point2.x >= max) break;
        } else if (dir === 'left') {
          point3.subtract(movex, movey);
          if (point2.x <= min) break;
        }

        ribbon.push({
          point1: new Point(point1.x, point1.y),
          point2: new Point(point2.x, point2.y),
          point3: point3,
          color: color,
          delay: delay,
          dir: dir,
          alpha: 0,
          phase: 0
        });

        point1.copy(point2);
        point2.copy(point3);

        delay += 4;
        color += this._options.colorCycleSpeed;
      }
      this._ribbons.push(ribbon);
    },

    _drawRibbonSection: function (section) {
      if (section) {
        if (section.phase >= 1 && section.alpha <= 0) {
          return true;
        }
        if (section.delay <= 0) {
          section.phase += 0.02;
          section.alpha = Math.sin(section.phase);
          section.alpha = section.alpha <= 0 ? 0 : section.alpha >= 1 ? 1 : section.alpha;

          if (this._options.animateSections) {
            var mod = Math.sin(1 + section.phase * Math.PI / 2) * 0.1;
            if (section.dir === 'right') {
              section.point1.add(mod, 0);
              section.point2.add(mod, 0);
              section.point3.add(mod, 0);
            } else {
              section.point1.subtract(mod, 0);
              section.point2.subtract(mod, 0);
              section.point3.subtract(mod, 0);
            }
            section.point1.add(0, mod);
            section.point2.add(0, mod);
            section.point3.add(0, mod);
          }
        } else {
          section.delay -= 0.5;
        }

        var s = this._options.colorSaturation,
          l = this._options.colorBrightness,
          c = 'hsla(' + section.color + ', ' + s + ', ' + l + ', ' + section.alpha + ' )';

        this._context.save();
        if (this._options.parallaxAmount !== 0) {
          this._context.translate(0, this._scroll * this._options.parallaxAmount);
        }
        this._context.beginPath();
        this._context.moveTo(section.point1.x, section.point1.y);
        this._context.lineTo(section.point2.x, section.point2.y);
        this._context.lineTo(section.point3.x, section.point3.y);
        this._context.fillStyle = c;
        this._context.fill();

        if (this._options.strokeSize > 0) {
          this._context.lineWidth = this._options.strokeSize;
          this._context.strokeStyle = c;
          this._context.lineCap = 'round';
          this._context.stroke();
        }
        this._context.restore();
      }
      return false;
    },

    _onDraw: function () {
      this._rafId = 0;
      if (!this._canAnimate() || !this._context) return;

      for (var i = this._ribbons.length - 1; i >= 0; --i) {
        if (!this._ribbons[i]) {
          this._ribbons.splice(i, 1);
        }
      }

      this._context.clearRect(0, 0, this._width, this._height);

      for (var a = 0; a < this._ribbons.length; ++a) {
        var ribbon = this._ribbons[a],
          numSections = ribbon.length,
          numDone = 0;

        for (var b = 0; b < numSections; ++b) {
          if (this._drawRibbonSection(ribbon[b])) {
            numDone++;
          }
        }
        if (numDone >= numSections) {
          this._ribbons[a] = null;
        }
      }
      if (this._ribbons.length < this._options.ribbonCount) {
        this.addRibbon();
      }
      this._rafId = requestAnimationFrame(this._onDraw);
    },

    _onResize: function (e) {
      var screen = screenInfo(e);
      this._width = screen.width;
      this._height = screen.height;

      if (this._canvas) {
        this._canvas.width = this._width;
        this._canvas.height = this._height;
        if (this._context) {
          this._context.globalAlpha = this._options.colorAlpha;
        }
      }
    },

    _onScroll: function (e) {
      var screen = screenInfo(e);
      this._scroll = screen.scrolly;
    }
  };

  return Factory;
});
