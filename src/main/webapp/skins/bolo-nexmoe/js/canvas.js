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
!function() {
    "use strict";
    function get_attribute(node, attr, default_value) {
        return (node && node.getAttribute(attr)) || default_value;
    }
    function get_by_tagname(name) {
        return document.getElementsByTagName(name);
    }
    function get_config_option() {
        var scripts = get_by_tagname("script"),
            script_len = scripts.length,
            script = scripts[script_len - 1];
        return {
            l: script_len,
            z: get_attribute(script, "zIndex", -1),
            o: get_attribute(script, "opacity", 0.5),
            c: get_attribute(script, "color", "0,0,0"),
            n: Math.min(64, Math.max(12, parseInt(get_attribute(script, "count", 48), 10) || 48))
        };
    }

    if (typeof window._boloNexmoeCanvasCleanup === "function") {
        window._boloNexmoeCanvasCleanup();
    }

    var config = get_config_option(),
        the_canvas = document.getElementById("c_n1");
    if (!the_canvas) {
        the_canvas = document.createElement("canvas");
        the_canvas.id = "c_n1";
        the_canvas.style.cssText = "position:fixed;top:0;left:0;pointer-events:none;z-index:" + config.z + ";opacity:" + config.o;
        var bodyEl = get_by_tagname("body")[0];
        if (bodyEl) bodyEl.appendChild(the_canvas);
    } else {
        the_canvas.style.pointerEvents = "none";
    }

    var context = the_canvas.getContext("2d"),
        canvas_width = 0,
        canvas_height = 0,
        raf_id = 0,
        start_timer = 0,
        disposed = false,
        reduced_media = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : null,
        random = Math.random,
        current_point = {
            x: null,
            y: null,
            max: 20000
        },
        random_lines = [],
        all_array;

    function set_canvas_size() {
        canvas_width = the_canvas.width = window.innerWidth || document.documentElement.clientWidth || (document.body && document.body.clientWidth) || 0;
        canvas_height = the_canvas.height = window.innerHeight || document.documentElement.clientHeight || (document.body && document.body.clientHeight) || 0;
    }

    function can_animate() {
        return !disposed &&
            !document.hidden &&
            !(reduced_media && reduced_media.matches) &&
            the_canvas.isConnected !== false;
    }

    function draw_canvas() {
        raf_id = 0;
        if (!can_animate() || !context) return;
        context.clearRect(0, 0, canvas_width, canvas_height);
        var e, i, d, x_dist, y_dist, dist;
        for (var idx = 0, len = random_lines.length; idx < len; idx++) {
            var r = random_lines[idx];
            r.x += r.xa;
            r.y += r.ya;
            r.xa *= r.x > canvas_width || r.x < 0 ? -1 : 1;
            r.ya *= r.y > canvas_height || r.y < 0 ? -1 : 1;
            context.fillRect(r.x - 0.5, r.y - 0.5, 1, 1);
            for (i = idx + 1; i < all_array.length; i++) {
                e = all_array[i];
                if (null !== e.x && null !== e.y) {
                    x_dist = r.x - e.x;
                    y_dist = r.y - e.y;
                    dist = x_dist * x_dist + y_dist * y_dist;
                    if (dist < e.max) {
                        if (e === current_point && dist >= e.max / 2) {
                            r.x -= 0.03 * x_dist;
                            r.y -= 0.03 * y_dist;
                        }
                        d = (e.max - dist) / e.max;
                        context.beginPath();
                        context.lineWidth = d / 2;
                        context.strokeStyle = "rgba(" + config.c + "," + (d + 0.2) + ")";
                        context.moveTo(r.x, r.y);
                        context.lineTo(e.x, e.y);
                        context.stroke();
                    }
                }
            }
        }
        raf_id = requestAnimationFrame(draw_canvas);
    }

    function sync_power() {
        if (can_animate()) {
            if (!raf_id) raf_id = requestAnimationFrame(draw_canvas);
        } else if (raf_id) {
            cancelAnimationFrame(raf_id);
            raf_id = 0;
        }
    }

    function on_mouse_move(e) {
        current_point.x = e.clientX;
        current_point.y = e.clientY;
    }

    function on_mouse_out() {
        current_point.x = null;
        current_point.y = null;
    }

    set_canvas_size();
    window.addEventListener("resize", set_canvas_size, { passive: true });
    window.addEventListener("mousemove", on_mouse_move, { passive: true });
    window.addEventListener("mouseout", on_mouse_out, { passive: true });
    document.addEventListener("visibilitychange", sync_power);
    if (reduced_media) {
        if (reduced_media.addEventListener) reduced_media.addEventListener("change", sync_power);
        else if (reduced_media.addListener) reduced_media.addListener(sync_power);
    }

    for (var k = 0; k < config.n; k++) {
        random_lines.push({
            x: random() * canvas_width,
            y: random() * canvas_height,
            xa: 2 * random() - 1,
            ya: 2 * random() - 1,
            max: 6000
        });
    }
    all_array = random_lines.concat([current_point]);

    var cleanup = function() {
        if (disposed) return;
        disposed = true;
        clearTimeout(start_timer);
        if (raf_id) {
            cancelAnimationFrame(raf_id);
            raf_id = 0;
        }
        window.removeEventListener("resize", set_canvas_size);
        window.removeEventListener("mousemove", on_mouse_move);
        window.removeEventListener("mouseout", on_mouse_out);
        document.removeEventListener("visibilitychange", sync_power);
        if (reduced_media) {
            if (reduced_media.removeEventListener) reduced_media.removeEventListener("change", sync_power);
            else if (reduced_media.removeListener) reduced_media.removeListener(sync_power);
        }
        if (window._boloNexmoeCanvasCleanup === cleanup) {
            window._boloNexmoeCanvasCleanup = null;
        }
    };
    window._boloNexmoeCanvasCleanup = cleanup;
    if (window.BoloNavigation && typeof window.BoloNavigation.onLeave === "function") {
        window.BoloNavigation.onLeave(cleanup);
    }

    start_timer = setTimeout(sync_power, 100);
}();
