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
        Skin._initToc()
        Skin._initBackToTop()
        Util.initPjax(function () {
            if ($('.post__fix').length === 0) {
                $('body').addClass('body--gray')
            } else {
                $('body').removeClass('body--gray')
            }
            $('.header__nav a').each(function () {
                console.log(this.href)
                console.log(location.href)
                if (this.href === location.href) {
                    this.className = 'active nexmoe-list-item mdui-list-item mdui-ripple'
                }else{
                    this.className ='nexmoe-list-item mdui-list-item mdui-ripple'
                }
            })

            Skin._initToc()
        })

        $('.header__nav a').each(function () {
            if (this.href === location.href) {
                this.className = 'active nexmoe-list-item mdui-list-item mdui-ripple'
            }
        }).click(function () {
            $('.header__nav a').removeClass('active')
            if (this.href === location.href) {
                this.className = 'active nexmoe-list-item mdui-list-item mdui-ripple'
            }
        })


    },
    _initBackToTop: function () {
        var control = document.querySelector('#nexmoe-content .back-to-top')
        if (!control) return
        control.setAttribute('role', 'button')
        control.setAttribute('aria-label', '返回顶部')
        function update () {
            var visible = window.scrollY > 240
            control.classList.toggle('is-visible', visible)
            control.setAttribute('aria-hidden', String(!visible))
            control.tabIndex = visible ? 0 : -1
            control.inert = !visible
        }
        function onKey (event) {
            if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); Util.goTop() }
        }
        window.addEventListener('scroll', update, {passive: true})
        control.addEventListener('keydown', onKey)
        update()
        window.BoloNavigation?.onLeave(function () {
            window.removeEventListener('scroll', update)
            control.removeEventListener('keydown', onKey)
        })
    },
    _initToc: function () {
        /* $(window).keydown(function (event) {
            if (event.which == 84) {
                Util.goTop()
            }
        }); */
        // 删除重复头图问题
        let headerImg = $(".nexmoe-post-cover img:eq(0)").attr("data-src") || $(".nexmoe-post-cover img:eq(0)").attr("src")
        let firstP = $("article p:eq(0) img:eq(0)").attr("data-src")

        if (firstP == undefined) {
            firstP = $("article p:eq(0) img:eq(0)").attr("src")
        }
        if (headerImg == firstP && headerImg != undefined) {
            $("article img:eq(0)").remove()
        }

    },
    _initShare: function () {
        var $this = $('.post__share')
        var $qrCode = $this.find('.post__code')
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
    },
    initArticle: function () {
        Skin._initShare()
        Skin._initToc()
    },
}
Skin.init()
