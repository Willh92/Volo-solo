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
!function (t, e) {
    "object" == typeof exports && "object" == typeof module ? module.exports = e() : "function" == typeof define && define.amd ? define([], e) : "object" == typeof exports ? exports.POWERMODE = e() : t.POWERMODE = t.POWERMODE || e()
}(this, (function () {
    return function (t) {
        var e = {};

        function o(n) {
            if (e[n]) return e[n].exports;
            var r = e[n] = {exports: {}, id: n, loaded: !1};
            return t[n].call(r.exports, r, r.exports, o), r.loaded = !0, r.exports
        }

        return o.m = t, o.c = e, o.p = "", o(0)
    }([function (t, e, o) {
        "use strict";
        var n = document.getElementById("bolo-power-mode-canvas");
        if (!n) {
            n = document.createElement("canvas");
            n.id = "bolo-power-mode-canvas";
            n.style.cssText = "position:fixed;top:0;left:0;pointer-events:none;z-index:999999;will-change:transform";
            if (document.body) document.body.appendChild(n);
            else document.addEventListener("DOMContentLoaded", function () { document.body && !n.isConnected && document.body.appendChild(n) }, {once: !0});
        }
        function syncSize() {
            n.width = window.innerWidth;
            n.height = window.innerHeight;
        }
        syncSize();
        if (!window._boloPowerModeResizeBound) {
            window._boloPowerModeResizeBound = !0;
            window.addEventListener("resize", syncSize, {passive: !0});
        }
        var r = n.getContext("2d"), i = [], d = !1, joltTimer = 0;
        var reducedMotion = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : null;

        function l(t, e) {
            return Math.random() * (e - t) + t
        }

        function c(t) {
            if (p.colorful) {
                var e = l(0, 360);
                return "hsla(" + l(e - 10, e + 10) + ", 100%, " + l(50, 80) + "%, 1)"
            }
            return window.getComputedStyle(t).color
        }

        function u(t, e, o) {
            return {x: t, y: e, alpha: 1, color: o, velocity: {x: 2 * Math.random() - 1, y: 2 * Math.random() - 3.5}}
        }

        function p() {
            if (document.hidden || (reducedMotion && reducedMotion.matches)) return;
            if (!n.isConnected && document.body) document.body.appendChild(n);
            for (var t = function () {
                var t, e = document.activeElement;
                if (e && ("TEXTAREA" === e.tagName || "INPUT" === e.tagName && "text" === e.getAttribute("type"))) {
                    var n = o(1)(e, e.selectionEnd);
                    return t = e.getBoundingClientRect(), {x: n.left + t.left, y: n.top + t.top, color: c(e)}
                }
                var r = window.getSelection && window.getSelection();
                if (r && r.rangeCount) {
                    var i = r.getRangeAt(0), a = i.startContainer;
                    return a.nodeType === document.TEXT_NODE && (a = a.parentNode), {
                        x: (t = i.getBoundingClientRect()).left,
                        y: t.top,
                        color: c(a)
                    }
                }
                return {x: 0, y: 0, color: "transparent"}
            }(), e = 5 + Math.round(10 * Math.random()); e--;) {
                if (i.length >= 500) i.shift();
                i.push(u(t.x, t.y, t.color));
            }
            if (p.shake) {
                var s = 1 + 2 * Math.random(), x = s * (Math.random() > .5 ? -1 : 1),
                    y = s * (Math.random() > .5 ? -1 : 1);
                n.style.transform = "translate3d(" + x.toFixed(1) + "px," + y.toFixed(1) + "px,0)";
                clearTimeout(joltTimer);
                joltTimer = setTimeout((function () {
                    n.style.transform = ""
                }), 75)
            }
            d || (d = !0, requestAnimationFrame(f))
        }

        function f() {
            if (document.hidden || 0 === i.length) {
                d = !1;
                i.length = 0;
                r && r.clearRect(0, 0, n.width, n.height);
                return;
            }
            r.clearRect(0, 0, n.width, n.height);
            var writeIdx = 0;
            for (var o = 0; o < i.length; ++o) {
                var a = i[o];
                if (a.alpha > .1) {
                    a.velocity.y += .075;
                    a.x += a.velocity.x;
                    a.y += a.velocity.y;
                    a.alpha *= .96;
                    r.globalAlpha = a.alpha;
                    r.fillStyle = a.color;
                    r.fillRect(Math.round(a.x - 1.5), Math.round(a.y - 1.5), 3, 3);
                    i[writeIdx++] = a;
                }
            }
            i.length = writeIdx;
            i.length > 0 ? requestAnimationFrame(f) : (d = !1, r.clearRect(0, 0, n.width, n.height))
        }

        p.shake = !0, p.colorful = !1, t.exports = p
    }, function (t, e) {
        !function () {
            var e = ["direction", "boxSizing", "width", "height", "overflowX", "overflowY", "borderTopWidth", "borderRightWidth", "borderBottomWidth", "borderLeftWidth", "borderStyle", "paddingTop", "paddingRight", "paddingBottom", "paddingLeft", "fontStyle", "fontVariant", "fontWeight", "fontStretch", "fontSize", "fontSizeAdjust", "lineHeight", "fontFamily", "textAlign", "textTransform", "textIndent", "textDecoration", "letterSpacing", "wordSpacing", "tabSize", "MozTabSize"],
                o = null != window.mozInnerScreenX;

            function n(t, n, r) {
                var i = r && r.debug || !1;
                if (i) {
                    var a = document.querySelector("#input-textarea-caret-position-mirror-div");
                    a && a.parentNode.removeChild(a)
                }
                var d = document.createElement("div");
                d.id = "input-textarea-caret-position-mirror-div", document.body.appendChild(d);
                var l = d.style, c = window.getComputedStyle ? getComputedStyle(t) : t.currentStyle;
                l.whiteSpace = "pre-wrap", "INPUT" !== t.nodeName && (l.wordWrap = "break-word"), l.position = "absolute", i || (l.visibility = "hidden"), e.forEach((function (t) {
                    l[t] = c[t]
                })), o ? t.scrollHeight > parseInt(c.height) && (l.overflowY = "scroll") : l.overflow = "hidden", d.textContent = t.value.substring(0, n), "INPUT" === t.nodeName && (d.textContent = d.textContent.replace(/\s/g, " "));
                var u = document.createElement("span");
                u.textContent = t.value.substring(n) || ".", d.appendChild(u);
                var p = {
                    top: u.offsetTop + parseInt(c.borderTopWidth),
                    left: u.offsetLeft + parseInt(c.borderLeftWidth)
                };
                return i ? u.style.backgroundColor = "#aaa" : document.body.removeChild(d), p
            }

            void 0 !== t && void 0 !== t.exports ? t.exports = n : window.getCaretCoordinates = n
        }()
    }])
}));
