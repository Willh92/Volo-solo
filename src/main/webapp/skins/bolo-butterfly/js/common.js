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
// Theme behavior uses the shared jQuery and rendering libraries.
(function () {
  "use strict";

    1 == !1 ? $("#archives span.al_mon").click((function() {
        return $(this).next().slideToggle(400), !1
    })) : function() {
        $("#al_expand_collapse,#archives span.al_mon").css({
            cursor: "pointer"
        }), $("#archives span.al_mon").each((function() {
            var e = $(this).next().children("li").length;
            $(this).children("#post-num").text(e)
        }));
        var e = $("#archives ul.al_post_list"),
            t = $("#archives ul.al_post_list:first");
        e.hide(1, (function() {
            t.show()
        })), $("#archives span.al_mon").click((function() {
            return $(this).next().slideToggle(400), !1
        })), document.body.clientWidth > 860 && $("#archives li.al_li").mouseover((function() {
            return $(this).children(".al_post_list").show(400), !1
        }));
        var n = 0;
        $("#al_expand_collapse").click((function() {
            0 == n ? (e.show(), n++) : 1 == n && (e.hide(), n--)
        }))
    }(), window.Skin = {
        init: function() {
            var e, t, n = function() {
                    document.documentElement.setAttribute("data-theme", "dark"), null !== document.querySelector('meta[name="theme-color"]') && document.querySelector('meta[name="theme-color"]').setAttribute("content", "#000")
                },
                r = function() {
                    document.documentElement.setAttribute("data-theme", "light"), null !== document.querySelector('meta[name="theme-color"]') && document.querySelector('meta[name="theme-color"]').setAttribute("content", "#fff")
                },
                o = function(e) {
                    const t = ("; " + document.cookie).split(`; ${e}=`);
                    if (2 === t.length) return t.pop().split(";").shift()
                }("theme");
            if (window.BoloTheme) document.documentElement.setAttribute("data-theme", BoloTheme.current());
            else { t = (e = (new Date).getHours()) <= 6 || e >= 18; void 0 === o ? t ? n() : r() : "light" === o ? r() : n(); }
            window.addEventListener("load", (function() {
                if (!document.querySelector("#subtitle")) return;
                loadScript("https://sdk.jinrishici.com/v2/browser/jinrishici.js", (function() {
                    if (!document.querySelector("#subtitle") || typeof jinrishici === "undefined") return;
                    jinrishici.load((function(e) {
                        if (!document.querySelector("#subtitle") || !e || !e.data || !e.data.content || typeof Typed === "undefined") return;
                        var t = 0 == "May the god bless you.,It is better to burn out than to fade away.".length ? new Array : "May the god bless you.,It is better to burn out than to fade away.".split(","),
                            n = e.data.content;
                        t.unshift(n), new Typed("#subtitle", {
                            strings: t,
                            startDelay: 300,
                            typeSpeed: 150,
                            loop: !0,
                            backSpeed: 50
                        })
                    }))
                }))
            }));
            const i = void 0 !== GLOBAL_CONFIG.Snackbar,
                a = $("#nav"),
                s = $("#rightside"),
                c = $("body");
            function updateRailVisibility(position) {
                var visible = position > 56;
                s.toggleClass('is-visible', visible).attr('aria-hidden', String(!visible)).prop('inert', !visible);
            }
            updateRailVisibility($(window).scrollTop());
            var l = $("#blog_name").width(),
                u = $(".menus").width(),
                d = $("#sidebar").width();

            function f(e) {
                var t;
                1 === e ? t = l + u > a.width() - d - 20 : 2 === e && (t = l + u > a.width() - 20), t ? p() : (a.find(".toggle-menu").removeClass("is-visible-inline"), a.find(".menus_items").removeClass("is-invisible"), a.find("#search_button span").removeClass("is-invisible"))
            }

            function p() {
                a.find(".toggle-menu").addClass("is-visible-inline"), a.find(".menus_items").addClass("is-invisible"), a.find("#search_button span").addClass("is-invisible")
            }

            function h() {
                window.innerWidth < 768 ? p() : f(2)
            }
            h(), $("#nav").css({
                opacity: "1",
                animation: "headerNoOpacity 1s"
            }), $(window).on("resize", (function() {
                $("#sidebar").hasClass("tocOpenPc") && a.hasClass("fixed") ? f(1) : h()
            }));
            var m = $(".toggle-menu"),
                g = $("#mobile-sidebar-menus"),
                v = $("#mobile-toc-button"),
                y = $("#menu_mask");

            function b(e) {
                if ("closeMenu" === e) {
                    x("menu");
                    return;
                }
                if (sidebarPaddingR(), y.fadeIn(), "menu" === e) {
                    m.removeClass("close").addClass("open").attr("aria-expanded", "true"), g.css("transform", "translate3d(-100%,0,0)");
                    var t = g.children();
                    for (let e = 0; e <= t.length; e++) {
                        const n = e / 5 + .2;
                        t.eq(e).css("animation", "sidebarItem " + n + "s")
                    }
                }
                "toc" === e && (v.removeClass("close").addClass("open"), $("#sidebar").addClass("tocOpenMobile"), $("#sidebar").css({
                    transform: "translate3d(-100%,0,0)",
                    left: ""
                }))
            }

            function x(e) {
                $("body").css({
                    overflow: "",
                    "padding-right": ""
                }), y.fadeOut(), "menu" === e && (m.removeClass("open").addClass("close").attr("aria-expanded", "false"), g.css("transform", ""), $("#mobile-sidebar-menus > div,#mobile-sidebar-menus > hr").css("animation", "")), "toc" === e && (v.removeClass("open").addClass("close"), $("#sidebar").removeClass("tocOpenMobile"), $("#sidebar").css({
                    transform: ""
                }))
            }
            m.on("click", (function() {
                if(m.hasClass("close")) {
                    b("menu")
                } else {
                    b("closeMenu")
                }
            })), v.on("click", (function() {
                b("toc")
            })), y.on("click touchstart", (function(e) {
                m.hasClass("open") && x("menu"), v.hasClass("open") && x("toc")
            })), $(window).on("resize", (function(e) {
                m.is(":visible") || m.hasClass("open") && x("menu")
            }));
            var sidebarMedia = window.matchMedia("(max-width: 1024px)");
            var updateSidebarMedia = function(e) {
                e.matches ? $("#sidebar").hasClass("tocOpenPc") && closeSidebar() : ($("#toggle-sidebar").hasClass("on") && openSidebar(), v.hasClass("open") && x("toc"))
            };
            sidebarMedia.addListener(updateSidebarMedia);
            var closeOnEscape = function (event) {
                if (event.key === "Escape" && m.hasClass("open")) {
                    x("menu");
                    document.querySelector("#nav .toggle-menu")?.focus();
                }
            };
            document.addEventListener("keydown", closeOnEscape);
            if (window.BoloNavigation) BoloNavigation.onLeave(function () {
                sidebarMedia.removeListener(updateSidebarMedia);
                document.removeEventListener("keydown", closeOnEscape);
                x("menu");
            });
            $("#scroll_down").on("click", (function() {
                scrollToDest("#content-inner")
            })), $("#bookmark-it").on("click", (function() {
                if (window.sidebar && window.sidebar.addPanel) window.sidebar.addPanel(document.title, window.location.href, "");
                else if (window.external && "AddFavorite" in window.external) window.external.AddFavorite(location.href, document.title);
                else {
                    if (window.opera && window.print) return this.title = document.title, !0;
                    if (i) {
                        var e = GLOBAL_CONFIG.Snackbar.bookmark.message_prev + " " + (-1 !== navigator.userAgent.toLowerCase().indexOf("mac") ? "Command/Cmd" : "CTRL") + "+ D " + GLOBAL_CONFIG.Snackbar.bookmark.message_next + ".";
                        snackbarShow(e)
                    } else alert(GLOBAL_CONFIG.bookmark.message_prev + " " + (-1 !== navigator.userAgent.toLowerCase().indexOf("mac") ? "Command/Cmd" : "CTRL") + "+ D " + GLOBAL_CONFIG.bookmark.message_next + ".")
                }
            }));
            var w = 0,
                T = !0,
                C = "function" == typeof chatBtnHide,
                k = "function" == typeof chatBtnShow;
            if ($(window).scroll(throttle((function(e) {
                var t = $(this).scrollTop(),
                    n = function(e) {
                        var t = e > w;
                        return w = e, t
                    }(t);
                updateRailVisibility(t);
                t > 56 ? (n ? (a.hasClass("visible") && a.removeClass("visible"), k && !0 === T && (chatBtnHide(), T = !1)) : (a.hasClass("visible") || a.addClass("visible"), C && !1 === T && (window.chatBtnShow(), T = !0)), a.addClass("fixed"), "0" === s.css("opacity") && s.css({
                    opacity: "1",
                    transform: "none"
                })) : (0 === t && a.removeClass("fixed").removeClass("visible"), s.css({
                    opacity: "",
                    transform: ""
                }))
            }), 200)), $(".body-index")[0] ? ($("#readmode").hide(), $("#to_comment").hide(), $("#font_plus").hide(), $("#font_minus").hide()) : ($("#readmode").show(), $("#to_comment").show(), $("#font_plus").show(), $("#font_minus").show()), $("#go-up").on("click", (function() {
                scrollToDest("body")
            })), GLOBAL_CONFIG_SITE.isPost && GLOBAL_CONFIG_SITE.isSidebar) {
                $(".toc-child").hide(), $(window).scroll(throttle((function(e) {
                    var t = $(this).scrollTop();
                    S(t), A(t), j(t)
                }), 100)), $(".toc-link").on("click", (function(e) {
                    window.innerWidth <= 1024 ? x("toc") : (e.preventDefault(), scrollToDest($(this).attr("href")))
                }));
                var S = function(e) {
                        var t = $("#article-container").height(),
                            n = $(window).height(),
                            r = e / (t > n ? t - n : $(document).height() - n),
                            o = Math.round(100 * r),
                            i = o > 100 ? 100 : o <= 0 ? 0 : o;
                        $(".progress-num").text(i), $(".sidebar-toc__progress-bar").animate({
                            width: i + "%"
                        }, 100)
                    },
                    E = GLOBAL_CONFIG.isanchor,
                    A = function(e) {
                        if (0 === $(".toc-link").length) return !1;
                        var t = $("#article-container").find("h1,h2,h3,h4,h5,h6"),
                            n = "";
                        t.each((function() {
                            var t = $(this);
                            e > t.offset().top - 25 && (n = "#" + $(this).attr("id"))
                        })), "" === n && ($(".toc-link").removeClass("active"), $(".toc-child").hide());
                        var r, o, i = $(".toc-link.active");
                        if (n && i.attr("href") !== n) {
                            E && (o = n, window.history.replaceState && o !== window.location.hash && window.history.replaceState(void 0, void 0, o)), $(".toc-link").removeClass("active");
                            var a = $('.toc-link[href="' + n + '"]');
                            a.addClass("active");
                            var s = a.parents(".toc-child"),
                                c = s.length > 0 ? s.last() : a;
                            (r = c.closest(".toc-item").find(".toc-child")).is(":visible") || r.fadeIn(400), c.closest(".toc-item").siblings(".toc-item").find(".toc-child").hide()
                        }
                    },
                    j = function(e) {
                        if ($(".toc-link").hasClass("active")) {
                            var t = $(".active").offset().top,
                                n = $("#sidebar .sidebar-toc__content").scrollTop();
                            t > e + $(window).height() - 100 && $("#sidebar .sidebar-toc__content").scrollTop(n + 100), t < e + 100 && $("#sidebar .sidebar-toc__content").scrollTop(n - 100)
                        }
                    }
            }
            $("#readmode").click((function() {
                $("body").toggleClass("read-mode")
            })), $("#font_plus").click((function() {
                c.css("font-size", parseFloat(c.css("font-size")) + 1)
            })), $("#font_minus").click((function() {
                c.css("font-size", parseFloat(c.css("font-size")) - 1)
            })), $("#mobile-sidebar-menus .menus-expand").on("click", (function() {
                $(this).hasClass("menus-closed") ? ($(this).parents(".menus_item").find(".menus_item_child").slideDown(), $(this).removeClass("menus-closed")) : ($(this).parents(".menus_item").find(".menus_item_child").slideUp(), $(this).addClass("menus-closed"))
            })), $(window).on("touchmove", (function(e) {
                var t = $("#nav .menus_item_child");
                t.is(":visible") && t.css("display", "none")
            })), $("#rightside_config").on("click", (function() {
                var panel = document.getElementById('rightside-config-hide');
                var open = this.getAttribute('aria-expanded') !== 'true';
                if (!open && window.BoloTheme) BoloTheme.closeMenu();
                if (!open && panel.contains(document.activeElement)) this.focus();
                this.setAttribute('aria-expanded', String(open));
                this.setAttribute('aria-label', open ? '收起阅读设置' : '展开阅读设置');
                panel.classList.toggle('rightside-in', open);
                panel.classList.toggle('rightside-out', !open);
                panel.setAttribute('aria-hidden', String(!open));
                panel.inert = !open;
            })), $("#darkmode").click((function() {
                if (window.BoloTheme) { BoloTheme.set(BoloTheme.current() === "dark" ? "light" : "dark"); return; }
                "light" == ("dark" === document.documentElement.getAttribute("data-theme") ? "dark" : "light") ? (n(), Cookies.set("theme", "dark", 2), i && snackbarShow(GLOBAL_CONFIG.Snackbar.day_to_night)) : (r(), Cookies.set("theme", "light", 2), i && snackbarShow(GLOBAL_CONFIG.Snackbar.night_to_day)), "function" == typeof utterancesTheme && utterancesTheme()
            }))
        },
        initArticle: function() {
            page.share(), initCanvas("articleTop"), $(window).width() < 768 && $(".side__top--toc").click((function() {
                $(".post__toc").slideToggle()
            }));
            var e = $("#article-container [id^=b3_solo_h]"),
                t = $(".article__toc");
            t.find("a").click((function(e) {
                var t = $(this).attr("href");
                return window.location.hash = t, $(window).scrollTop($(t).offset().top), $(window).width() < 768 && $(".post__toc").slideToggle(), e.preventDefault(), e.stopPropagation(), !1
            })), $("#to_comment").click((function() {
                var e = $(".commentFont").offset().top;
                $("body,html").animate({
                    scrollTop: e
                }, 1e3)
            })), $(window).scroll((function(n) {
                if (0 === $(".article__toc li").length) return !1;
                var r = [];
                e.each((function(e) {
                    r.push({
                        id: this.id,
                        offsetTop: $(this).offset().top
                    })
                }));
                for (var o = $(window).scrollTop() + 10, i = 0, a = r.length; i < a; i++)
                    if (o < r[i].offsetTop) {
                        t.find("li").removeClass("current");
                        var s = i > 0 ? i - 1 : 0;
                        t.find('a[href="#' + r[s].id + '"]').parent().addClass("current");
                        break
                    } o >= r[r.length - 1].offsetTop && (t.find("li").removeClass("current"), t.find("li:last").addClass("current"))
            })), $(window).scroll()
        }
    }, $(document).ready((function() {
        Skin.init()
    })),
        function() {
            window.cnblogsConfig = {
                homeTopImg: ["https://ftp.stackoverflow.wiki/bolo/background/butterfly/1.jpg", "https://ftp.stackoverflow.wiki/bolo/background/butterfly/2.jpg", 
                "https://ftp.stackoverflow.wiki/bolo/background/butterfly/3.jpg", "https://ftp.stackoverflow.wiki/bolo/background/butterfly/4.jpg", 
                "https://ftp.stackoverflow.wiki/bolo/background/butterfly/5.jpg", "https://ftp.stackoverflow.wiki/bolo/background/butterfly/6.jpg", 
                "https://ftp.stackoverflow.wiki/bolo/background/butterfly/7.jpg", "https://ftp.stackoverflow.wiki/bolo/background/butterfly/8.jpg", 
                "https://ftp.stackoverflow.wiki/bolo/background/butterfly/9.jpg", "https://ftp.stackoverflow.wiki/bolo/background/butterfly/10.jpg",
                "https://ftp.stackoverflow.wiki/bolo/background/butterfly/11.jpg","https://ftp.stackoverflow.wiki/bolo/background/butterfly/13.jpg"]
            };
            let e, t = window.cnblogsConfig.homeTopImg;
            var n, r;
            e = t.length > 0 ? t.length > 1 ? t[(n = 0, r = t.length - 1, Math.floor(Math.random() * (r - n) + n))] : t[0] : "", $(".full_page").css({
                background: "#222 url(" + e + ")  center center no-repeat",
                "background-size": "cover"
            })
        }(), leftRight()

})();
