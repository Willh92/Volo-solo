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
 * @description index for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

Util.htmlDecode = function (code) {
    var div = document.createElement('div')
    div.innerHTML = decodeURIComponent(code)
    return div.innerText
}

var Admin = function () {
    this.register = {}
    this.panelRequests = {}
    // 工具栏下的工具
    this.tools = [
        '#page-list', '#theme-list', '#link-list', '#preference',
        '#user-list', '#plugin-list', '#others', '#category-list', '#follow-list', '#usite']
    // 多用户时，一般用户不能使用的功能
    this.adTools = [
        'link-list', 'preference', 'theme-list', 'page-list',
        'access', 'ip', 'geo', 'comments', 'user-list', 'plugin-list', 'others', 'category-list', 'follow-list', 'usite']
}

$.extend(Admin.prototype, {
    /**
     * @description  登出
     */
    logout: function () {
        if (window.BoloNavigation) {
            window.BoloNavigation.navigate(Label.servePath + '/logout')
        } else {
            window.location.href = Label.servePath + '/logout'
        }
    },
    toggleMenu: function (open) {
        var next = typeof open === 'boolean' ? open : !document.body.classList.contains('console-nav-open')
        document.body.classList.toggle('console-nav-open', next)
        $('.console-menu').attr('aria-expanded', String(next)).attr('aria-label', next ? '收起侧栏' : '展开侧栏')
    },
    showNotice: function (message, tone) {
        var text = String(message == null ? '' : message).trim()
        this.noticePending = {message: text, tone: tone === 'error' ? 'error' : 'info'}
        var output = document.getElementById('tipMsg')
        if (output) output.textContent = text
    },
    initNotices: function () {
        if (this.noticeCleanup) this.noticeCleanup()
        var notice = document.getElementById('consoleNotice'), output = document.getElementById('tipMsg')
        var title = document.getElementById('consoleNoticeTitle'), close = document.getElementById('consoleNoticeClose')
        if (!notice || !output || !title || !close) return
        var that = this, timer, deadline = 0, remaining = 0, hovering = false, focused = false
        function stopTimer() { clearTimeout(timer); timer = undefined }
        function dismiss() { stopTimer(); notice.hidden = true; output.textContent = '' }
        function schedule() {
            stopTimer()
            if (notice.hidden || hovering || focused) return
            deadline = Date.now() + remaining
            var message = output.textContent
            timer = setTimeout(function () { if (output.textContent === message) dismiss() }, remaining)
        }
        function pause() { if (timer !== undefined) remaining = Math.max(0, deadline - Date.now()); stopTimer() }
        function enter() { hovering = true; pause() }
        function leave() { hovering = false; schedule() }
        function focus() { focused = true; pause() }
        function blur(event) { if (!notice.contains(event.relatedTarget)) { focused = false; schedule() } }
        function render() {
            stopTimer()
            var message = output.textContent.trim(), pending = that.noticePending
            that.noticePending = null
            notice.hidden = !message
            if (!message) return
            var tone = pending && pending.message === message ? pending.tone : 'info'
            notice.dataset.tone = tone
            title.textContent = tone === 'error' ? '操作未完成' : '提示'
            remaining = tone === 'error' ? 8000 : 5000
            schedule()
        }
        var observer = new MutationObserver(render)
        observer.observe(output, {childList: true, characterData: true, subtree: true})
        close.addEventListener('click', dismiss)
        notice.addEventListener('mouseenter', enter)
        notice.addEventListener('mouseleave', leave)
        notice.addEventListener('focusin', focus)
        notice.addEventListener('focusout', blur)
        this.noticeCleanup = function () {
            stopTimer(); observer.disconnect(); that.noticePending = null; notice.hidden = true
            close.removeEventListener('click', dismiss)
            notice.removeEventListener('mouseenter', enter)
            notice.removeEventListener('mouseleave', leave)
            notice.removeEventListener('focusin', focus)
            notice.removeEventListener('focusout', blur)
        }
        if (window.BoloNavigation) window.BoloNavigation.onLeave(this.noticeCleanup)
        render()
    },
    handleRequestError: function (xhr, reason) {
        if (xhr.status === 0 && reason === 'abort') return
        var messages = {400:'提交内容有误，请检查后重试。', 401:'登录状态已失效，请重新登录后再试。',
            403:'访问被拒绝，请确认操作权限。', 404:'请求的资源不存在或已失效。',
            413:'提交的数据过大，请减少内容后重试。', 429:'操作过于频繁，请稍后重试。'}
        var detail = xhr.responseJSON && xhr.responseJSON.msg
        var message = typeof detail === 'string' && detail.trim() ? detail.trim().slice(0, 2000)
            : reason === 'timeout' ? '请求超时，请稍后重试。'
                : xhr.status === 0 ? '连接失败，请检查网络后重试。'
                    : messages[xhr.status] || '操作未完成，请稍后重试。'
        this.showNotice((xhr.status ? xhr.status + ' · ' : '') + message, 'error')
    },
    /**
     * @description 清除提示
     */
    clearTip: function () {
        $('#tipMsg').text('')
        $('#loadMsg').text('')
    },
    /**
     * @description 根据当前页数设置 hash
     * @param {Int} currentPage 当前页
     */
    setHashByPage: function (currentPage) {
        var hash = window.location.hash,
            hashList = hash.split('/')
        if (/^\d*$/.test(hashList[hashList.length - 1])) {
            hashList[hashList.length - 1] = currentPage
        } else {
            hashList.push(currentPage)
        }
        window.location.hash = hashList.join('/')
    },
    /**
     * @description 设置某个 tab 被选择
     * @param {Stirng} id id tab id
     */
    selectTab: function (id) {
        window.location.hash = '#' + id
    },
    /**
     * @description 根据当前 hash 解析出当前页数及 hash 数组。
     */
    analyseHash: function () {
        var hash = window.location.hash
        var tag = hash.substr(1, hash.length - 1)
        var tagList = tag.split('/')
        var tags = {}
        tags.page = 1,
            tags.hashList = []
        for (var i = 0; i < tagList.length; i++) {
            if (i === tagList.length - 1 && (/^\d+$/.test(tagList[i]))) {
                tags.page = tagList[i]
            } else {
                tags.hashList.push(tagList[i])
            }
        }
        return tags
    },
    /**
     * @description 根据当前 hash 设置当前 tab
     */
    setCurByHash: function () {
        $(window).scrollTop(0)
        $('#tipMsg').text('')
        if (window.location.hash === '#tools/preference/security') { window.location.replace('#security/access'); return }
        if (window.location.hash === '#tools/tool-box') { window.location.replace('#tools/others/maintenance'); return }
        var tags = admin.analyseHash()
        var tab = tags.hashList[1],
            subTab = tags.hashList[2]

        if (tags.hashList.length === 1) {
            tab = tags.hashList[0]
        }

        if (tab === '') {
            tab = 'main'
            tags.hashList = ['main']
            history.replaceState(history.state, '', '#main')
        }
        var title = document.querySelector('#tabs_' + tab + ' a')
        if (title) document.getElementById('consolePageTitle').textContent = title.textContent.trim()

        // 离开编辑器时进行提示
        try {
            if (admin.editors.articleEditor.getContent) {
                // 除更新、发布、取消发布文章，编辑器中无内容外，离开编辑器需进行提示。
                if (tab !== 'article' && admin.article.isConfirm &&
                    admin.editors.articleEditor.getContent().replace(/\s/g, '') !== ''
                    && admin.article.content !==
                    admin.editors.articleEditor.getContent()) {
                    if (!confirm(Label.editorLeaveLabel)) {
                        window.location.hash = '#article/article'
                        return
                    }
                }
                // 不离开编辑器，hash 需变为 "#article/article"，此时不需要做任何处理。
                if (tab === 'article' && admin.article.isConfirm &&
                    admin.editors.articleEditor.getContent().replace(/\s/g, '') !== ''
                    && admin.article.content !==
                    admin.editors.articleEditor.getContent()) {
                    return
                }
            }
        } catch (e) {
            console.log(e)
        }

        // clear article
        if (tab !== 'article' && admin.editors.articleEditor.setContent) {
            admin.article.clear()
        }
        admin.article.isConfirm = true

        $('#tabs').tabs('setCurrent', tab)
        $('#loadMsg').text(Label.loadingLabel)

        if ($('#tabsPanel_' + tab).length === 1) {
            if ($('#tabsPanel_' + tab).html().replace(/\s/g, '') === '') {
                var panel = $('#tabsPanel_' + tab)
                if (this.panelRequests[tab]) {
                    this.panelRequests[tab].tags = tags
                    return
                }
                var pending = {tags: tags}
                this.panelRequests[tab] = pending
                panel.addClass('console-page-loading').attr('aria-busy', 'true')
                $.ajax({url: document.documentElement.dataset.boloAdminPages + 'admin-' + tab + '.do',
                    dataType: 'html', timeout: 30000,
                    success: function (html) {
                        panel.html(html)
                        panel.removeClass('console-page-loading').removeAttr('aria-busy')
                        var currentTags = pending.tags
                        // 页面加载完后，回调初始函数
                        try {
                            if (tab === 'article' && admin.article.status.id) {
                                // 当文章页面编辑器未初始化时，调用更新文章需先初始化编辑器
                                admin.register[tab].init.call(admin.register[tab].obj,
                                    admin.article.getAndSet)
                            } else {
                                admin.register[tab].init.call(admin.register[tab].obj, currentTags.page)
                            }

                            // 页面包含子 tab，需根据 hash 定位到相应的 tab
                            var childTabs = $('#tab' + tab.substring(0, 1).toUpperCase() + tab.substring(1))
                            if (currentTags.hashList[2] && childTabs.length) {
                                childTabs.tabs('setCurrent', currentTags.hashList[2])
                            }

                            // 根据 hash 调用现有的插件函数
                            admin.plugin.setCurByHash(currentTags)
                        } catch (error) {
                            panel.empty()
                            $('#tipMsg').text('页面初始化失败，请重新选择菜单重试。')
                            $('#loadMsg').text('')
                            console.error(error)
                        }
                    },
                    error: function (xhr, status) {
                        panel.empty()
                        $('#loadMsg').text('')
                        $('#tipMsg').text(status === 'timeout' ? '页面载入超时，请重新选择菜单重试。' : '页面载入失败，请重新选择菜单重试。')
                    },
                    complete: function () {
                        panel.removeClass('console-page-loading').removeAttr('aria-busy')
                        delete admin.panelRequests[tab]
                    }
                })
            } else {
                if (tab === 'article' && admin.article.status.id) {
                    admin.article.getAndSet()
                }

                // 已加载过 HTML，只需调用刷新函数
                if (admin.register[tab] && admin.register[tab].refresh) {
                    admin.register[tab].refresh.call(admin.register[tab].obj, tags.page)
                }

                // 页面包含子 tab，需根据 hash 定位到相应的 tab
                var childTabs = $('#tab' + tab.substring(0, 1).toUpperCase() + tab.substring(1))
                if (subTab && childTabs.length) {
                    childTabs.tabs('setCurrent', subTab)
                }

                // 根据 hash 调用现有的插件函数
                admin.plugin.setCurByHash(tags)
            }
        } else {
            $('#tipMsg').text('Error: No tab! ' + Label.reportIssueLabel)
            $('#loadMsg').text('')
        }
    },
    /**
     * @description 初始化整个后台
     */
    init: function () {
        Util.killIE()
        Util.loadVditorRuntime().catch(function () {})
        $('#loadMsg').text(Label.loadingLabel)

        // 构建 tabs
        $('#tabs').tabs()

        this.initNotices()
        $('#loadMsg').text('')

        window.onbeforeunload = function (event) {
            var editor = admin.editors.articleEditor
            var hasArticle = admin.article.status.id || $('#title').val() ||
                editor && editor.getContent && editor.getContent().trim()
            if (window.location.hash === '#article/article' && admin.article.isConfirm && hasArticle) {
                if (event) {
                    event.returnValue = Label.editorLeaveLabel
                }
                return Label.editorLeaveLabel
            }
        }

        $(document).off('ajaxError.voloConsole').on('ajaxError.voloConsole', function (event, xhr, options, exc) {
            $('#loadMsg').text('')
            admin.handleRequestError(xhr, exc)
        })
        if (window.BoloNavigation) window.BoloNavigation.onLeave(function () { $(document).off('ajaxError.voloConsole') })
    },
    /**
     * @description tools and article collapse
     * @param {Bom} it 触发事件对象
     */
    collapseNav: function (it) {
        var subNav = $(it).next()
        subNav.slideToggle('normal', function () {
            if (this.style.display !== 'none') {
                $(it).
                    find('.icon-chevron-down')[0].className = 'icon-chevron-up fn__right'
                $(it).addClass('tab-current')
            } else {
                $(it).
                    find('.icon-chevron-up')[0].className = 'icon-chevron-down fn__right'
                $(it).removeClass('tab-current')
            }

            $('#tabs > ul').height('auto')
        })
    },
    /**
     * @description 后台及当前页面所需插件初始化完后，对权限进行控制及当前页面属于 tools 时，tools 选项需展开。
     */
    inited: function () {
        // Removes functions with the current user role
        if (Label.userRole !== 'adminRole') {
            for (var i = 0; i < this.adTools.length; i++) {
                $('#tabs').tabs('remove', this.adTools[i])
            }
            $('#tabSecurityTitle').parent().remove()
        } else {
            // 当前 tab 属于 Tools 时，设其展开
            for (var j = 0; j < this.tools.length; j++) {
                if ('#' + window.location.hash.split('/')[1] === this.tools[j]) {
                    $('#tabToolsTitle').click()
                    break
                }
            }
        }
        if (window.location.hash.startsWith('#security/')) $('#tabSecurityTitle').click()
        this.setCurByHash()
    },
})

var admin = new Admin()
