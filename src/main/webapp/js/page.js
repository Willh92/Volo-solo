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
 * @fileoverview Page util, load highlight and process comment.
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */
var Page = function (tips) {
    this.currentCommentId = '';
    this.tips = tips
};

// The admin and comment editors share one asynchronous local runtime loader.
Page.loadCommentLibrary = function () { return Util.loadVditorRuntime(); };

$.extend(Page.prototype, {
    share: function () {
        var $this = $('.article__share');
        if ($this.length === 0) {
            return
        }
        var $qrCode = $this.find('.item__qr');
        var shareURL = $this.data('url');
        var avatarURL = $this.data('avatar');
        var title = encodeURIComponent($this.data('title') + ' - ' +
            $this.data('blogtitle'));
        var url = encodeURIComponent(shareURL);

        var urls = {};
        urls.tencent = 'http://share.v.t.qq.com/index.php?c=share&a=index&title=' +
            title +
            '&url=' + url + '&pic=' + avatarURL;
        urls.weibo = 'http://v.t.sina.com.cn/share/share.php?title=' +
            title + '&url=' + url + '&pic=' + avatarURL;
        urls.qqz = 'https://sns.qzone.qq.com/cgi-bin/qzshare/cgi_qzshare_onekey?url='
            + url + '&sharesource=qzone&title=' + title + '&pics=' + avatarURL;
        urls.twitter = 'https://twitter.com/intent/tweet?status=' + title + ' ' +
            url;

        $this.find('span').click(function () {
            var key = $(this).data('type');

            if (!key) {
                return
            }

            if (key === 'wechat') {
                if ($qrCode.find('canvas').length === 0) {
                    Util.addScript(Label.staticServePath +
                        '/js/lib/jquery.qrcode.min.js', 'qrcodeScript');
                    $qrCode.qrcode({
                        width: 128,
                        height: 128,
                        text: shareURL,
                    })
                } else {
                    $qrCode.slideToggle()
                }
                return false
            }

            window.open(urls[key], '_blank', 'top=100,left=200,width=648,height=618')
        })
    },
    /*
     * @description 文章加载
     */
    load: function () {
        var that = this;
        this.prepareCommentIdentity(typeof loggedIn !== 'undefined' ? loggedIn : false);
        Util.bindCommentAvatars(document);
        this.refreshCommentPermissions();
        $('#soloEditorEditIdentity').click(function () {
            that.editingGuestIdentity = true;
            that.prepareCommentIdentity(false);
            document.getElementById('boloUser')?.focus();
        });
        // comment
        $('#comment').click(function () {
            that.toggleEditor()
        }).attr('readonly', 'readonly');

        $('#soloEditorCancel').click(function () {
            that.cancelCommentEdit();
            that.setEditorOpen(false)
        });
        $('#soloEditorRetry').click(function () {
            that.prepareCommentEditor().catch(function () {});
        });
        this.updateCommentSubmitState();
        var updateViewport = function () { that.updateEditorViewport() };
        window.addEventListener('resize', updateViewport);
        window.visualViewport?.addEventListener('resize', updateViewport);
        window.visualViewport?.addEventListener('scroll', updateViewport);
        if (document.getElementById('soloEditorComment') && !window.navigator?.connection?.saveData) {
            var warmup = function () { that.prepareCommentEditor().catch(function () {}); };
            if (window.requestIdleCallback) this.commentWarmup = window.requestIdleCallback(warmup, {timeout: 3000});
            else this.commentWarmup = setTimeout(warmup, 700);
        }
        if (window.BoloNavigation) BoloNavigation.onLeave(function () {
            that.commentDisposed = true;
            clearTimeout(that.commentEditorTimeout);
            if (window.requestIdleCallback) window.cancelIdleCallback(that.commentWarmup);
            else clearTimeout(that.commentWarmup);
            window.removeEventListener('resize', updateViewport);
            window.visualViewport?.removeEventListener('resize', updateViewport);
            window.visualViewport?.removeEventListener('scroll', updateViewport);
        });
        $('#soloEditorAdd').click(function () {
            that.requestCommentSubmission(typeof loggedIn !== 'undefined' ? loggedIn : false);
        })
    },
    isCommentEditorReady: function () {
        return !this.commentDisposed && this.commentEditorReady === true
            && this.commentEditorHost === document.getElementById('soloEditorComment')
            && !!window.vditor?.vditor?.wysiwyg;
    },
    updateCommentSubmitState: function () {
        if (!this.isCommentEditorReady() || this.commentPending) $('#soloEditorAdd').attr('disabled', 'disabled');
        else $('#soloEditorAdd').removeAttr('disabled');
    },
    prepareCommentIdentity: function (loggedIn) {
        this.commentLoggedIn = loggedIn === true || loggedIn === 'true';
        var host = document.getElementById('soloEditorIdentity');
        if (!host) return;
        var profile = this.guestProfile;
        if (!profile) {
            try { profile = JSON.parse(window.localStorage.getItem('volo.comment.profile.v1') || 'null'); } catch (ignored) { }
            this.guestProfile = profile && typeof profile.name === 'string' ? {name: profile.name.slice(0, 20), site: typeof profile.site === 'string' ? profile.site.slice(0, 255) : '', email: typeof profile.email === 'string' ? profile.email.slice(0, 254) : '', token: /^[a-f0-9]{64}$/.test(profile.token || '') ? profile.token : ''} : {};
            profile = this.guestProfile;
        }
        if (!this.commentLoggedIn && !profile.token) profile.token = this.createVisitorKey();
        var fields = [['boloUser', 'soloEditorNameSlot', 'volo_comment_name'], ['boloSite', 'soloEditorSiteSlot', 'volo_comment_site']];
        fields.forEach(function (field) {
            var input = document.getElementById(field[0]), slot = document.getElementById(field[1]);
            if (!input || !slot) return;
            if (input.parentElement !== slot) {
                input.removeAttribute('style');
                slot.appendChild(input);
                var key = field[0] === 'boloUser' ? 'name' : 'site';
                if (!input.value) input.value = typeof profile[key] === 'string' ? profile[key] : (typeof $.cookie === 'function' ? ($.cookie(field[2]) || '') : '');
                input.maxLength = field[0] === 'boloUser' ? 20 : 255;
            }
        });
        var email = document.getElementById('soloEditorEmail');
        if (email && !email.dataset.restored) {
            email.value = typeof profile.email === 'string' ? profile.email : (typeof $.cookie === 'function' ? ($.cookie('my_email') || '') : '');
            email.dataset.restored = 'true';
        }
        var card = document.getElementById('soloEditorVisitorCard');
        var saved = typeof profile.name === 'string' && profile.name.trim().length >= 1;
        host.hidden = this.commentLoggedIn || (saved && !this.editingGuestIdentity);
        if (card) {
            card.hidden = this.commentLoggedIn || !saved || this.editingGuestIdentity;
            document.getElementById('soloEditorVisitorName').textContent = saved ? profile.name : '';
            document.getElementById('soloEditorVisitorAvatar').src = Util.generatedAvatar(profile.name);
        }
    },
    rememberCommentIdentity: function () {
        if (this.commentLoggedIn) return;
        var name = document.getElementById('boloUser'), site = document.getElementById('boloSite'), email = document.getElementById('soloEditorEmail');
        if (!name) return;
        this.guestProfile = {name: name.value.trim(), site: site ? site.value.trim() : '', email: email ? email.value.trim() : '', token: this.guestProfile?.token || this.createVisitorKey()};
        try { window.localStorage.setItem('volo.comment.profile.v1', JSON.stringify(this.guestProfile)); } catch (ignored) { }
        this.editingGuestIdentity = false;
    },
    requestCommentSubmission: function (loggedIn) {
        if (this.commentPending || this.commentDisposed) return;
        this.prepareCommentIdentity(loggedIn);
        if (!this.isCommentEditorReady()) {
            $('#soloEditorError').text('评论编辑器正在加载，请稍后重试。');
            this.updateCommentSubmitState();
            return;
        }
        var content = window.vditor.getValue();
        if (content.length < 2 || content.length > 500) {
            $('#soloEditorError').text(this.tips.commentContentCannotEmptyLabel);
            return;
        }
        if (this.editingCommentId) { this.saveCommentEdit(); return; }
        if (!this.commentLoggedIn) {
            var name = document.getElementById('boloUser');
            if (!String(name ? name.value : $('#boloUser').val() || '').trim()) {
                $('#soloEditorError').text('请填写昵称后再发送。');
                if (name) name.focus();
                return;
            }
            if (String(name ? name.value : $('#boloUser').val() || '').trim().length > 20) {
                $('#soloEditorError').text('昵称不能超过 20 个字符。');
                if (name) name.focus();
                return;
            }
            var site = document.getElementById('boloSite'), siteValue = site ? site.value.trim() : '';
            if (siteValue) {
                try {
                    var parsedSite = new URL(siteValue);
                    if (siteValue.length > 255 || !/^https?:$/.test(parsedSite.protocol)) throw new Error('Invalid site');
                } catch (invalidSite) {
                    $('#soloEditorError').text('主页请填写不超过 255 个字符的 HTTP 或 HTTPS 网址，或留空。');
                    site.focus();
                    return;
                }
            }
            var email = document.getElementById('soloEditorEmail');
            if (email && (email.value.trim().length > 254 || (email.value.trim() && !/^[^\s@;:]+@[^\s@;:]+\.[^\s@;:]+$/.test(email.value.trim())))) {
                $('#soloEditorError').text('请填写有效的通知邮箱，或留空。');
                email.focus();
                return;
            }
        }
        this.rememberCommentIdentity();
        this.submitComment();
    },
    createVisitorKey: function () {
        if (!window.crypto?.getRandomValues) return '';
        var bytes = new Uint8Array(32);
        window.crypto.getRandomValues(bytes);
        return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    },
    findCommentElement: function (id) {
        return (document.getElementById(id) || document.getElementById('li-comment-' + id)
            || document.getElementById('comment-' + id))?.closest('[data-comment-depth]');
    },
    refreshCommentPermissions: function () {
        if (!this.tips.oId || (!this.commentLoggedIn && !this.guestProfile?.token)) return;
        var that = this;
        $.ajax({url: Label.servePath + '/article/comment-permissions', type: 'GET', cache: false,
            data: {articleId: this.tips.oId}, headers: {'X-Volo-Visitor-Key': this.guestProfile?.token || ''},
            success: function (result) {
                if (that.commentDisposed || !result.sc) return;
                that.applyCommentModeration(result.moderation || []);
                (document.getElementById('soloEditorComment') ? (result.editable || []) : []).forEach(function (id) {
                    var item = that.findCommentElement(id);
                    if (!item || item.querySelector('[data-comment-edit]')) return;
                    var edit = document.createElement('button'), remove = document.createElement('button');
                    edit.type = remove.type = 'button'; edit.className = remove.className = 'comment-edit-action';
                    edit.textContent = '编辑'; remove.textContent = '删除';
                    edit.dataset.commentEdit = id; remove.dataset.commentDelete = id;
                    edit.addEventListener('click', function () { that.editComment(id); });
                    remove.addEventListener('click', function () { that.deleteOwnComment(id); });
                    var actions = document.createElement('span');
                    actions.className = 'comment-management-actions';
                    actions.append(edit, remove);
                    var reply = item.querySelector('[data-comment-name][onclick*="page.toggleEditor"]');
                    if (reply) {
                        actions.classList.add('comment-management-actions--inline');
                        var replyShell = reply.parentElement?.matches('.comment-reply') ? reply.parentElement : reply;
                        replyShell.before(actions);
                        actions.append(replyShell);
                    } else {
                        var content = item.querySelector('[data-comment-content]');
                        if (content) content.after(actions); else item.append(actions);
                    }
                });
            }
        });
    },
    applyCommentModeration: function (entries) {
        var that = this;
        entries.forEach(function (entry) {
            var item = that.findCommentElement(entry.id), location = item?.querySelector('.comment-location');
            if (!location) return;
            location.textContent = entry.address + (entry.region ? ' · ' + entry.region : '');
            var button = document.createElement('button');
            button.type = 'button'; button.className = 'comment-ban-action'; button.textContent = '封禁';
            button.dataset.commentBan = entry.id;
            button.setAttribute('aria-label', '封禁这条评论的来源 IP');
            button.addEventListener('click', function () { that.banCommentSource(entry.id); });
            location.append(button);
        });
    },
    banCommentSource: function (id) {
        if (this.commentBanPending || typeof swal !== 'function') return;
        var that = this;
        swal({title: '封禁评论来源', text: '封禁这条评论的来源 IP？该地址将无法访问本站，可在后台博客安全中解除。', icon: 'warning',
            buttons: {cancel: '取消', confirm: '封禁'}}).then(function (confirmed) {
            if (!confirmed || that.commentDisposed || that.commentBanPending) return;
            that.commentBanPending = true;
            $.ajax({url: Label.servePath + '/console/ip-bans', type: 'POST', contentType: 'application/json',
                data: JSON.stringify({commentId: id, reason: '从评论封禁'}),
                complete: function () { that.commentBanPending = false; },
                success: function (result) { if (!that.commentDisposed) swal({text: result.msg || (result.sc ? '已封禁。' : '封禁失败。'), icon: result.sc ? 'success' : 'warning'}); },
                error: function (xhr) { if (!that.commentDisposed) swal({text: xhr.responseJSON?.msg || '封禁失败，请重试。', icon: 'warning'}); }
            });
        });
    },
    editComment: function (id) {
        if (this.commentPending || this.commentEditLoading) return;
        var that = this;
        this.commentEditLoading = true;
        $.ajax({url: Label.servePath + '/article/comments/' + encodeURIComponent(id), type: 'GET', cache: false,
            headers: {'X-Volo-Visitor-Key': this.guestProfile?.token || ''},
            complete: function () { that.commentEditLoading = false; },
            error: function (xhr) { if (!that.commentDisposed && window.swal) swal({text: xhr.responseJSON?.msg || '无法编辑这条评论。', icon: 'warning'}); },
            success: function (result) {
                if (that.commentDisposed || !result.sc) return;
                that.cancelCommentEdit();
                that.commentBeforeEdit = {value: that.isCommentEditorReady() ? window.vditor.getValue() : '', parent: that.currentCommentId};
                that.editingCommentId = id; that.pendingCommentEdit = result.commentContent;
                that.setEditorOpen(true);
                $('#soloEditorReplyTarget').text('编辑评论'); $('#soloEditorAdd').text('保存修改');
                that.prepareCommentEditor().then(function () { that.applyPendingCommentEdit(); }).catch(function () {});
            }
        });
    },
    applyPendingCommentEdit: function () {
        if (!this.isCommentEditorReady() || this.pendingCommentEdit === undefined) return;
        window.vditor.disabledCache?.();
        window.vditor.setValue(this.pendingCommentEdit); delete this.pendingCommentEdit;
    },
    cancelCommentEdit: function () {
        if (!this.editingCommentId) return;
        if (this.isCommentEditorReady()) {
            window.vditor.setValue(this.commentBeforeEdit?.value || '');
            window.vditor.enableCache?.();
        }
        this.currentCommentId = this.commentBeforeEdit?.parent;
        delete this.editingCommentId; delete this.pendingCommentEdit; delete this.commentBeforeEdit;
        $('#soloEditorAdd').text('发送');
    },
    saveCommentEdit: function () {
        if (!this.isCommentEditorReady() || !this.editingCommentId || this.commentPending) return;
        var that = this, id = this.editingCommentId;
        this.commentPending = true; this.updateCommentSubmitState();
        $.ajax({url: Label.servePath + '/article/comments/' + encodeURIComponent(id), type: 'PUT', contentType: 'application/json',
            headers: {'X-Volo-Visitor-Key': this.guestProfile?.token || ''}, data: JSON.stringify({commentContent: window.vditor.getValue()}),
            complete: function () { that.commentPending = false; if (!that.commentDisposed) that.updateCommentSubmitState(); },
            error: function (xhr) { if (!that.commentDisposed) $('#soloEditorError').text(xhr.responseJSON?.msg || '保存失败，请重试。'); },
            success: function (result) {
                if (that.commentDisposed || !result.sc) return;
                var content = that.findCommentElement(id)?.querySelector('[data-comment-content]');
                if (content) content.innerHTML = result.commentHTML;
                that.cancelCommentEdit(); that.setEditorOpen(false); Util.parseMarkdown();
            }
        });
    },
    deleteOwnComment: function (id) {
        if (!window.confirm('只删除这条评论的内容？其他人的回复会保留，删除后无法恢复。')) return;
        var that = this;
        $.ajax({url: Label.servePath + '/article/comments/' + encodeURIComponent(id), type: 'DELETE',
            headers: {'X-Volo-Visitor-Key': this.guestProfile?.token || ''},
            error: function (xhr) { if (!that.commentDisposed && window.swal) swal({text: xhr.responseJSON?.msg || '删除失败，请重试。', icon: 'warning'}); },
            success: function (result) {
                if (that.commentDisposed || !result.sc) return;
                var item = that.findCommentElement(id), content = item?.querySelector('[data-comment-content]');
                if (content) { content.textContent = '这条评论已删除'; content.classList.add('comment-tombstone'); }
                if (item) item.dataset.commentDeleted = 'true';
                item?.querySelectorAll('.comment-location').forEach(node => node.remove());
                item?.querySelectorAll('[data-comment-edit],[data-comment-delete]').forEach(button => button.remove());
            }
        });
    },
    updateEditorViewport: function () {
        var editor = document.getElementById('soloEditor');
        if (!editor) return;
        var viewport = window.visualViewport;
        var available = viewport?.height || window.innerHeight;
        editor.style.bottom = Math.max(0, viewport ? window.innerHeight - available - viewport.offsetTop : 0) + 'px';
        editor.style.maxHeight = Math.max(0, available - 16) + 'px';
        editor.style.setProperty('--composer-viewport-height', available + 'px');
    },
    setEditorOpen: function (open) {
        var editor = document.getElementById('soloEditor');
        if (!editor) return;
        if (!open) editor.querySelector('.vditor--fullscreen [data-type="fullscreen"]')?.click();
        // A transformed skin card must not become the fixed editor's containing block.
        if (editor.parentElement !== document.body) document.body.appendChild(editor);
        editor.classList.toggle('is-open', open);
        editor.setAttribute('aria-hidden', String(!open));
        if (open) editor.removeAttribute('inert');
        else editor.setAttribute('inert', '');
        var trigger = document.getElementById('comment');
        if (trigger) {
            trigger.setAttribute('aria-expanded', String(open));
            if (!open) trigger.focus({preventScroll: true});
        }
        this.updateEditorViewport();
    },
    toggleEditor: function (commentId, name) {
        if (this.editingCommentId) this.cancelCommentEdit();
        this.prepareCommentIdentity(typeof loggedIn !== 'undefined' ? loggedIn : false);
        var $editor = $('#soloEditor');
        if ($editor.length === 0) {
            if (window.swal) swal({text: '评论编辑器暂不可用，请刷新页面后重试。', icon: 'warning'});
            return
        }
        if ($editor.hasClass('is-open') && !commentId) { this.setEditorOpen(false); return }
        this.setEditorOpen(true);
        $('#soloEditorError').text('');
        this.currentCommentId = commentId;
        $('#soloEditorReplyTarget').text(name ? '@' + name : '');
        var that = this;
        this.prepareCommentEditor().then(function (editor) {
            if (!that.commentDisposed && editor?.vditor?.wysiwyg && document.getElementById('soloEditor')?.classList.contains('is-open')) editor.focus();
        }).catch(function () {});
    },
    prepareCommentEditor: function () {
        var editorHost = document.getElementById('soloEditorComment');
        if (!editorHost || this.commentDisposed) return Promise.resolve(null);
        if (this.isCommentEditorReady()) return Promise.resolve(window.vditor);
        if (this.commentEditorHost === editorHost && this.commentEditorPromise) return this.commentEditorPromise;
        var that = this;
        if (this.commentEditorHost === editorHost && window.vditor?.destroy) {
            try { window.vditor.destroy(); } catch (error) {
                // A constructor interrupted before its after callback may be incomplete.
                editorHost.replaceChildren();
                editorHost.classList.remove('vditor');
            }
        }
        this.commentEditorHost = editorHost;
        this.commentEditorReady = false;
        this.commentEditorGeneration = (this.commentEditorGeneration || 0) + 1;
        var generation = this.commentEditorGeneration;
        this.updateCommentSubmitState();
        var retry = document.getElementById('soloEditorRetry');
        if (retry) retry.hidden = true;
        $('#soloEditorError').text('');
        editorHost.setAttribute('aria-busy', 'true');
        this.commentEditorPromise = Page.loadCommentLibrary().then(function (runtime) {
            that.commentEditorI18n = runtime?.i18n;
            // Let the opening dialog paint before a cold editor is constructed.
            return new Promise(function (resolve) { requestAnimationFrame(function () { setTimeout(resolve, 0); }); });
        }).then(function () {
            if (that.commentDisposed || document.getElementById('soloEditorComment') !== editorHost) return null;
            that.commentEditorTimeout = setTimeout(function () {
                if (that.commentDisposed || that.commentEditorReady || that.commentEditorGeneration !== generation) return;
                that.commentEditorPromise = null;
                editorHost.removeAttribute('aria-busy');
                if (retry) retry.hidden = false;
                $('#soloEditorError').text('评论编辑器加载失败，请重试。');
            }, 20000);
            window.vditor = that.buildCommentEditor(editorHost, generation);
            return window.vditor;
        }).catch(function (error) {
            if (!that.commentDisposed && document.getElementById('soloEditorComment') === editorHost) {
                editorHost.removeAttribute('aria-busy');
                that.commentEditorPromise = null;
                clearTimeout(that.commentEditorTimeout);
                if (retry) retry.hidden = false;
                if (document.getElementById('soloEditor')?.classList.contains('is-open')) $('#soloEditorError').text('评论编辑器加载失败，请关闭后重试。');
            }
            throw error;
        });
        return this.commentEditorPromise;
    },
    buildCommentEditor: function (editorHost, generation) {
            var that = this;
            var toolbar = [
                'emoji',
                'headings',
                'bold',
                'italic',
                'strike',
                'link',
                '|',
                'list',
                'ordered-list',
                'check',
                'outdent',
                'indent',
                'quote',
                'line',
                'code',
                'inline-code',
                'table',
                'insert-before',
                'insert-after',
                '|',
                'undo',
                'redo',
                'edit-mode',
                'both',
                'preview',
                '|',
                'fullscreen',
                'devtools',
                'help',
            ], resizeEnable = true;
            if ($(window).width() < 768) {
                toolbar = [
                    'emoji',
                    'link',
                    'upload',
                    'insert-after',
                    'edit-mode',
                    'preview',
                    'fullscreen',
                ];
                resizeEnable = false
            }

            return new Vditor('soloEditorComment', {
                cdn: Label.servePath + '/js/lib/vditor',
                hint: {emojiPath: Label.servePath + '/js/lib/vditor/dist/images/emoji'},
                theme: window.BoloTheme && BoloTheme.current() === 'dark' ? 'dark' : 'classic',
                placeholder: that.tips.commentContentCannotEmptyLabel,
                height: 180,
                tab: '\t',
                esc: function () {
                    $('#soloEditorCancel').click()
                },
                ctrlEnter: function () {
                    $('#soloEditorAdd').click()
                },
                preview: {
                    delay: 500,
                    mode: 'editor',
                    url: Label.servePath + '/console/markdown/2html',
                    hljs: {
                        enable: !Label.luteAvailable,
                        style: Label.hljsStyle,
                    },
                    parse: function (element) {
                        if (element.style.display === 'none') {
                            return
                        }
                        Util.parseLanguage()
                    },
                },
                counter: {enable: true, max: 500},
                cache: {enable: true, id: 'volo-comment-' + that.tips.oId},
                resize: {
                    enable: resizeEnable,
                    position: 'top',
                },
                lang: Label.langLabel,
                i18n: that.commentEditorI18n,
                toolbar: toolbar,
                after: () => {
                    if (that.commentDisposed || that.commentEditorGeneration !== generation
                        || document.getElementById('soloEditorComment') !== editorHost) return;
                    clearTimeout(that.commentEditorTimeout);
                    that.commentEditorReady = true;
                    that.applyPendingCommentEdit();
                    editorHost.removeAttribute('aria-busy');
                    var retry = document.getElementById('soloEditorRetry');
                    if (retry) retry.hidden = true;
                    $('#soloEditorError').text('');
                    that.updateCommentSubmitState();
                    if (document.getElementById('soloEditorComment') === editorHost
                        && !that.commentDisposed
                        && document.getElementById('soloEditor')?.classList.contains('is-open')) window.vditor?.focus()
                }
            });
    },
    /*
     * @description 加载随机文章
     * @param {String} headTitle 随机文章标题
     */
    loadRandomArticles: function (headTitle) {
        return this.loadArticleLinks('#randomArticles', '/articles/random', 'POST', 'randomArticles',
            headTitle || $('<h4>').text(this.tips.randomArticles1Label))
    },
    /*
     * @description 加载相关文章
     * @param {String} id 文章 id
     * @param {String} headTitle 相关文章标题
     */
    loadRelevantArticles: function (id, headTitle) {
        return this.loadArticleLinks('#relevantArticles', '/article/id/' + encodeURIComponent(id) + '/relevant/articles',
            'GET', 'relevantArticles', headTitle)
    },
    loadArticleLinks: function (selector, path, method, key, heading) {
        var target = $(selector);
        if (!target.length) return;
        var cancelled = false;
        var request = $.ajax({
            url: Label.servePath + path,
            type: method,
            dataType: 'json',
            success: function (data) {
                if (cancelled || !target[0].isConnected) return;
                var articles = data && data[key];
                if (!Array.isArray(articles) || articles.length === 0) {
                    target.remove();
                    return
                }
                var list = $('<ul>');
                for (var i = 0; i < articles.length; i++) {
                    var article = articles[i];
                    list.append($('<li>').append($('<a>', {
                        rel: 'nofollow', title: article.articleTitle,
                        href: Label.servePath + article.articlePermalink,
                    }).text(article.articleTitle)))
                }

                target.empty().append(heading, list)
            },
            error: function (xhr, status) {
                if (!cancelled && status !== 'abort' && target[0].isConnected) target.remove()
            },
        });
        if (window.BoloNavigation) window.BoloNavigation.onLeave(function () {
            cancelled = true;
            if (request && request.readyState !== 4) request.abort()
        });
        return request
    },
    /*
     * @description 提交评论
     * @param {String} commentId 回复评论时的评论 id
     */
    submitComment: function () {
        var that = this,
            tips = this.tips;
        if (this.commentPending) return;
        var commentEditor = window.vditor;
        if (!this.isCommentEditorReady()) {
            $('#soloEditorError').text('评论编辑器正在加载，请稍后重试。');
            return;
        }

        if (commentEditor.getValue().length > 1 && commentEditor.getValue().length <= 500) {
            this.commentPending = true;
            $('#soloEditorAdd').attr('disabled', 'disabled');
            var requestJSONObject = {
                'oId': tips.oId,
                'commentContent': commentEditor.getValue(),
                'boloUser': this.commentLoggedIn ? '' : $("#boloUser").val(),
                'boloSite': this.commentLoggedIn ? '' : $("#boloSite").val(),
                'email': this.commentLoggedIn ? '' : (document.getElementById('soloEditorEmail')?.value.trim() || ''),
                'visitorKey': this.commentLoggedIn ? '' : (this.guestProfile?.token || ''),
                'URI': window.location.href
            };

            if (this.currentCommentId) {
                requestJSONObject.commentOriginalCommentId = this.currentCommentId
            }

            $.ajax({
                type: 'POST',
                url: Label.servePath + '/article/comments' + (document.documentElement?.dataset.boloSkin
                    ? '?skin=' + encodeURIComponent(document.documentElement.dataset.boloSkin) : ''),
                cache: false,
                contentType: 'application/json',
                data: JSON.stringify(requestJSONObject),
                complete: function () {
                    that.commentPending = false;
                    if (!that.commentDisposed) that.updateCommentSubmitState();
                },
                error: function (xhr) {
                    if (that.commentDisposed) return;
                    var message = xhr?.responseJSON?.msg || '发送失败，请检查网络后重试。';
                    if (xhr?.status === 429) {
                        var seconds = parseInt(xhr.getResponseHeader('Retry-After'), 10);
                        if (seconds > 0) message += ' 请等待 ' + seconds + ' 秒后再试。';
                    }
                    $('#soloEditorError').text(message);
                },
                success: function (result) {
                    if (that.commentDisposed) return;
                    if (!result.sc) {
                        $('#soloEditorError').text(result.msg);
                        swal({
                            title: "",
                            text: "评论推送失败: " + result.msg,
                            icon: "error",
                            button: "好",
                        });

                        return
                    }
                    that.setEditorOpen(false);
                    commentEditor.setValue('');
                    swal({
                        title: "",
                        text: "评论已推送!",
                        icon: "success",
                        button: "好",
                    });
                    if (that.addCommentAjax(result.cmtTpl) === false) window.location.reload()
                },
            })
        } else {
            $('#soloEditorError').text(that.tips.commentContentCannotEmptyLabel)
            swal({
                title: "",
                text: "评论推送失败: " + that.tips.commentContentCannotEmptyLabel,
                icon: "error",
                button: "好",
            });
        }
    },
    /*
     * @description 添加回复评论表单
     * @param {String} id 被回复的评论 id
     */
    addReplyForm: function (id, name) {
        var that = this;
        that.currentCommentId = id;
        this.toggleEditor(id, name)
    },
    /*
     * @description 隐藏回复评论的浮出层
     * @parma {String} id 被回复的评论 id
     */
    hideComment: function (id) {
        $('#commentRef' + id).hide()
    },
    /*
     * @description 显示回复评论的浮出层
     * @parma {Dom} it 触发事件的 dom
     * @param {String} id 被回复的评论 id
     * @param {Int} top 位置相对浮出层的高度
     * @param {String} [parentTag] it 如果嵌入在 position 为 relative 的元素 A 中时，需取到 A 元素
     */
    showComment: function (it, id, top, parentTag) {
        var positionTop = parseInt($(it).position().top);
        if (parentTag) {
            positionTop = parseInt($(it).parents(parentTag).position().top)
        }
        if ($('#commentRef' + id).length > 0) {
            // 此处重复设置 top 是由于评论为异步，原有回复评论的显示位置应往下移动
            $('#commentRef' + id).show().css('top', (positionTop + top) + 'px')
        } else {
            var $refComment = $('#' + id).clone();
            $refComment.addClass('comment-body-ref').attr('id', 'commentRef' + id);
            $refComment.find('#replyForm').remove();
            $('#comments').append($refComment);
            $('#commentRef' + id).css('top', (positionTop + top) + 'px')
        }
    },
    /*
     * @description 回复不刷新，将回复内容异步添加到评论列表中
     * @parma {String} commentHTML 回复内容 HTML
     */
    addCommentAjax: function (commentHTML) {
        if (typeof commentHTML !== 'string' || !commentHTML.trim()) return false;
        var template = document.createElement('template');
        template.innerHTML = commentHTML;
        var item = template.content.querySelector('[data-comment-depth]');
        if (!item) return false;
        var target = this.currentCommentId && (document.getElementById(this.currentCommentId)
            || document.getElementById('li-comment-' + this.currentCommentId)
            || document.getElementById('comment-' + this.currentCommentId));
        target = target?.closest('[data-comment-depth]');
        var anchor = document.getElementById('comments');
        if (item && target) {
            var thread = target.dataset.commentThread;
            item.dataset.commentThread = thread;
            item.dataset.commentDepth = '1';
            var last = target;
            Array.from(target.parentElement.children).forEach(function (sibling) {
                if (sibling.dataset.commentThread === thread) last = sibling;
            });
            last.after(template.content);
        } else if (anchor) {
            if (anchor.tagName === 'SPAN') anchor.after(template.content);
            else anchor.prepend(template.content);
        } else return false;
        Util.bindCommentAvatars(document);
        this.refreshCommentPermissions();
        Util.parseMarkdown();
        Util.parseLanguage();
        window.location.hash = '#comments'
        return true
    },
});
