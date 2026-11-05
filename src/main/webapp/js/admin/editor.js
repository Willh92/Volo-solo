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
 * @fileoverview editor
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 */

admin.editors = {}

/*
 * @description Create SoloEditor can use all editor.
 * @constructor
 * @param conf 编辑器初始化参数
 * @param conf.id 编辑器渲染元素 id
 * @param conf.height 编辑器种类
 */
var SoloEditor = function (conf) {
  this.conf = conf
  this.init()
}

SoloEditor.convertStaticImageToWebp = function (file) {
  if (!file || typeof Label !== 'object' || String(Label.imageWebpAdaptive) !== 'true') {
    return Promise.resolve(file)
  }
  var mime = String(file.type || '').toLowerCase()
  if (!/^image\/(jpeg|jpg|png|bmp)$/.test(mime)) {
    return Promise.resolve(file)
  }
  if (typeof document === 'undefined' || !document.createElement || typeof Image === 'undefined' || typeof File === 'undefined' || typeof URL === 'undefined' || !URL.createObjectURL) {
    return Promise.resolve(file)
  }
  var rawQuality = parseFloat(Label.imageUploadCompress)
  var quality = (!isNaN(rawQuality) && rawQuality > 0 && rawQuality < 1) ? Math.min(0.95, Math.max(0.5, rawQuality)) : ((!isNaN(rawQuality) && rawQuality >= 10 && rawQuality < 100) ? Math.min(0.95, Math.max(0.5, rawQuality / 100)) : 0.86)
  return new Promise(function (resolve) {
    var objUrl = ''
    try {
      objUrl = URL.createObjectURL(file)
    } catch (e) {
      resolve(file)
      return
    }
    var cleanup = function () {
      try { if (objUrl && URL.revokeObjectURL) URL.revokeObjectURL(objUrl) } catch (e) {}
    }
    var img = new Image()
    img.onload = function () {
      try {
        var w = img.naturalWidth || img.width
        var h = img.naturalHeight || img.height
        if (!w || !h || w * h > 36000000) {
          cleanup()
          resolve(file)
          return
        }
        var canvas = document.createElement('canvas')
        canvas.width = w
        canvas.height = h
        var ctx = canvas.getContext && canvas.getContext('2d')
        if (!ctx || typeof canvas.toBlob !== 'function') {
          cleanup()
          resolve(file)
          return
        }
        ctx.drawImage(img, 0, 0, w, h)
        canvas.toBlob(function (blob) {
          cleanup()
          if (!blob || blob.type !== 'image/webp' || blob.size <= 0 || blob.size >= file.size) {
            resolve(file)
            return
          }
          var baseName = String(file.name || 'image').replace(/\.[^.]+$/, '')
          resolve(new File([blob], baseName + '.webp', { type: 'image/webp', lastModified: file.lastModified || Date.now() }))
        }, 'image/webp', quality)
      } catch (err) {
        cleanup()
        resolve(file)
      }
    }
    img.onerror = function () {
      cleanup()
      resolve(file)
    }
    img.src = objUrl
  })
}

SoloEditor.prepareUploadFiles = function (files) {
  var list = Array.prototype.slice.call(files || [])
  if (!list.length || typeof Label !== 'object' || String(Label.imageWebpAdaptive) !== 'true') {
    return list
  }
  return Promise.all(list.map(function (f) { return SoloEditor.convertStaticImageToWebp(f) }))
}

$.extend(SoloEditor.prototype, {
  /*
   * @description 初始化编辑器
   */
  init: function () {
    this._userTyped = false

    Label.emoji = {}
    let defaultEmojiData = JSON.parse('[{"doge":"' + Label.servePath + '/js/lib/vditor/dist/images/emoji/doge.png"},{"huaji":"' + Label.servePath + '/js/lib/vditor/dist/images/emoji/huaji.gif"},{"smile":"😄"},{"laughing":"😆"},{"smirk":"😏"},{"heart_eyes":"😍"},{"kissing_heart":"😘"},{"flushed":"😳"},{"grin":"😁"},{"stuck_out_tongue_closed_eyes":"😝"},{"sleeping":"😴"},{"anguished":"😧"},{"open_mouth":"😮"},{"expressionless":"😑"},{"unamused":"😒"},{"sweat_smile":"😅"},{"weary":"😩"},{"sob":"😭"},{"joy":"😂"},{"astonished":"😲"},{"scream":"😱"},{"tired_face":"😫"},{"rage":"😡"},{"triumph":"😤"},{"yum":"😋"},{"mask":"😷"},{"sunglasses":"😎"},{"dizzy_face":"😵"},{"imp":"👿"},{"smiling_imp":"😈"},{"innocent":"😇"},{"alien":"👽"},{"yellow_heart":"💛"},{"blue_heart":"💙"},{"purple_heart":"💜"},{"green_heart":"💚"},{"broken_heart":"💔"},{"anger":"💢"},{"exclamation":"❗"},{"question":"❓"},{"zzz":"💤"},{"notes":"🎶"},{"poop":"💩"},{"+1":"👍"},{"-1":"👎"},{"ok_hand":"👌"},{"punch":"👊"},{"v":"✌"},{"point_up":"☝"},{"point_down":"👇"},{"pray":"🙏"},{"clap":"👏"},{"muscle":"💪"},{"ok_woman":"🙆"},{"no_good":"🙅"},{"raising_hand":"🙋"},{"massage":"💆"},{"haircut":"💇"},{"nail_care":"💅"},{"see_no_evil":"🙈"},{"feet":"🐾"},{"trollface":"' + Label.servePath + '/js/lib/vditor/dist/images/emoji/trollface.png"},{"snowman":"⛄"},{"zap":"⚡"},{"cat":"🐱"},{"dog":"🐶"},{"mouse":"🐭"},{"hamster":"🐹"},{"rabbit":"🐰"},{"frog":"🐸"},{"koala":"🐨"},{"pig":"🐷"},{"monkey":"🐒"},{"racehorse":"🐎"},{"camel":"🐫"},{"sheep":"🐑"},{"elephant":"🐘"},{"panda_face":"🐼"},{"snake":"🐍"},{"hatched_chick":"🐥"},{"hatching_chick":"🐣"},{"turtle":"🐢"},{"bug":"🐛"},{"honeybee":"🐝"},{"beetle":"🐞"},{"snail":"🐌"},{"octopus":"🐙"},{"whale":"🐳"},{"dolphin":"🐬"},{"dragon":"🐉"},{"goat":"🐐"},{"paw_prints":"🐾"},{"tulip":"🌷"},{"four_leaf_clover":"🍀"},{"rose":"🌹"},{"mushroom":"🍄"},{"seedling":"🌱"},{"shell":"🐚"},{"crescent_moon":"🌙"},{"partly_sunny":"⛅"},{"octocat":"' + Label.servePath + '/js/lib/vditor/dist/images/emoji/octocat.png"},{"jack_o_lantern":"🎃"},{"ghost":"👻"},{"santa":"🎅"},{"tada":"🎉"},{"camera":"📷"},{"loudspeaker":"📢"},{"hourglass":"⌛"},{"lock":"🔒"},{"bulb":"💡"},{"hammer":"🔨"},{"moneybag":"💰"},{"smoking":"🚬"},{"bomb":"💣"},{"gun":"🔫"},{"hocho":"🔪"},{"pill":"💊"},{"syringe":"💉"},{"scissors":"✂"},{"swimmer":"🏊"},{"black_joker":"🃏"},{"coffee":"☕"},{"tea":"🍵"},{"sake":"🍶"},{"beer":"🍺"},{"wine_glass":"🍷"},{"pizza":"🍕"},{"hamburger":"🍔"},{"poultry_leg":"🍗"},{"meat_on_bone":"🍖"},{"dango":"🍡"},{"doughnut":"🍩"},{"icecream":"🍦"},{"shaved_ice":"🍧"},{"cake":"🍰"},{"cookie":"🍪"},{"lollipop":"🍭"},{"apple":"🍎"},{"green_apple":"🍏"},{"tangerine":"🍊"},{"lemon":"🍋"},{"cherries":"🍒"},{"grapes":"🍇"},{"watermelon":"🍉"},{"strawberry":"🍓"},{"peach":"🍑"},{"banana":"🍌"},{"pear":"🍐"},{"pineapple":"🍍"},{"sweet_potato":"🍠"},{"eggplant":"🍆"},{"tomato":"🍅"}]')
    if (Array.isArray(defaultEmojiData)) {
      defaultEmojiData.forEach(item => {
        const key = Object.keys(item)[0]
        Label.emoji[key] = item[key]
      })
    }

    const options = {
      cdn: Label.servePath + '/js/lib/vditor',
      theme: window.BoloTheme && BoloTheme.current() === 'dark' ? 'dark' : 'classic',
      outline: {enable: Boolean(this.conf.outline)},
      mode: Label.editorMode,
      typewriterMode: this.conf.typewriterMode,
      cache: {
        enable: true,
      },
      tab: '\t',
      input: value => {
        this._userTyped = true
        if (typeof this.conf.onInput === 'function') {
          this.conf.onInput(value)
        }
      },
      preview: {
        delay: 500,
        mode: this.conf.previewMode,
        url: Label.servePath + '/console/markdown/2html',
        hljs: {
          enable: !Label.luteAvailable,
          style: Label.hljsStyle,
          renderMenu: Util.prepareCodeCopyMenu,
        },
        parse: function (element) {
          if (element.style.display === 'none') {
            return
          }
          Util.parseMarkdown()
        },
      },
      upload: {
        max: 50 * 1024 * 1024,
        url: Label.uploadURL,
        token: Label.uploadToken,
        headers: {'X-Requested-With': 'XMLHttpRequest'},
        file: function (files) { return SoloEditor.prepareUploadFiles(files) },
        filename: function (name) {
          return name.replace(/[^(a-zA-Z0-9\u4e00-\u9fa5\.)]/g, '').
          replace(/[\?\\/:|<>\*\[\]\(\)\$%\{\}@~]/g, '').
          replace(/\s+/g, '_')
        },
      },
      height: this.conf.height,
      counter: {
        enable: true,
        max: 102400,
      },
      resize: {
        enable: this.conf.resize,
      },
      lang: Label.localeString,
      hint: {
        emojiPath: Label.servePath + '/js/lib/vditor/dist/images/emoji',
        emojiTail: `<a href="https://` + Label.hacpaiDomain + `/emoji/index.html" target="_blank">全部表情</a>`,
        emoji: Label.emoji,
      },
      toolbarConfig: {
        pin: true,
      },
      toolbar:[
        "emoji",
        "headings",
        "bold",
        "link",
        "|",
        "list",
        "ordered-list",
        "check",
        "outdent",
        "indent",
        "|",
        "quote",
        "code",
        "insert-before",
        "insert-after",
        "|",
        "upload",
        "record",
        "table",
        "|",
        "undo",
        "redo",
        "|",
        "fullscreen",
        "edit-mode",
        {
          name: "more",
          toolbar: [
            "italic",
            "strike",
            "line",
            "inline-code",
            "both",
            "code-theme",
            "content-theme",
            "export",
            "outline",
            "preview",
            "devtools",
            "info",
            "help",
          ],
        }],
      after: () => {
        if (document.getElementById(this.conf.id) !== host) return
        this.editorReady = true
        host.classList.remove('editor-loading')
        host.removeAttribute('aria-busy')
        if (this.editor && window.BoloTheme) {
          this.editor.setTheme(BoloTheme.current() === 'dark' ? 'dark' : 'classic')
        }
        if (typeof this.conf.fun === 'function') {
          this.conf.fun()
        }
      },
    }

    if (this.conf.id === 'articleContent' && window.innerWidth > 768) {
      const insertSnippet = snippet => {
        this.insertContent(snippet)
      }
      options.toolbar.splice(options.toolbar.indexOf('fullscreen'), 0,
        {
          name: 'volo-quick-insert',
          tipPosition: 'n',
          tip: '快捷插入面板（图库 / 书架 / 锁块 / 引用）',
          icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M17.5 14v7M14 17.5h7"/></svg>',
          click: () => {
            if (admin.article && typeof admin.article.openQuickInsert === 'function') {
              admin.article.openQuickInsert('media')
            }
          },
        },
        {
          name: 'volo-lock-user',
          tipPosition: 'n',
          tip: '插入「注册用户可见」隐藏内容块',
          icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><rect x="16" y="11" width="6" height="5" rx="1"/><path d="M17.5 11V9.5a1.5 1.5 0 0 1 3 0V11"/></svg>',
          click: () => insertSnippet('\n:::lock type="user" hint="登录或注册后即可阅读隐藏内容"\n在此输入仅登录用户可见的正文...\n:::\n'),
        },
        {
          name: 'volo-lock-pwd',
          tipPosition: 'n',
          tip: '插入「局部密码保护」隐藏内容块',
          icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><circle cx="12" cy="16" r="1"/></svg>',
          click: () => insertSnippet('\n:::lock type="pwd" pwd="123456" hint="请输入解锁密码查看本段内容"\n在此输入受密码保护的局部内容...\n:::\n'),
        },
        {
          name: 'volo-bookshelf',
          tipPosition: 'n',
          tip: '插入「在线书架」内嵌阅读器短代码',
          icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>',
          click: () => insertSnippet('\n:::bookshelf:::\n'),
        },
        '|'
      )
    }

    if (window.innerWidth <= 768) {
      options.preview.mode = 'editor'
      options.preview.actions = []
      options.outline.enable = false
      options.typewriterMode = false
      options.height = Math.min(this.conf.height, Math.max(260, Math.round(window.innerHeight * .48)))
      options.toolbar = [
        'emoji',
        'link',
        'upload',
        'edit-mode',
        {
          name: 'more',
          toolbar: [
            'insert-after',
            'fullscreen',
            'preview',
            'info',
            'help',
          ],
        },
      ]
      options.resize.enable = false
      options.toolbarConfig.pin = true
    }

    const host = document.getElementById(this.conf.id)
    if (host) { host.classList.add('editor-loading'); host.setAttribute('aria-busy', 'true') }
    this.ready = Util.loadVditorRuntime().then(runtime => {
      if (document.getElementById(this.conf.id) !== host) return null
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Editor initialization timed out')), 20000)
        const after = options.after
        options.i18n = runtime.i18n
        options.after = () => {
          clearTimeout(timer)
          if (document.getElementById(this.conf.id) !== host) { resolve(null); return }
          try { after(); resolve(this.editor) } catch (error) { reject(error) }
        }
        try { this.editor = new Vditor(this.conf.id, options) } catch (error) { clearTimeout(timer); reject(error) }
      })
    }).catch(() => {
      if (document.getElementById(this.conf.id) !== host) return
      host.classList.remove('editor-loading')
      host.removeAttribute('aria-busy')
      $('#tipMsg').text('编辑器加载失败，请刷新页面重试。')
      return null
    })
  },
  /*
   * @description 获取编辑器值
   * @returns {string} 编辑器值
   */
  getContent: function () {
    return this.editorReady ? this.editor.getValue() : ''
  },
  /*
   * @description 设置编辑器值
   * @param {string} content 编辑器回填内容
   */
  setContent: function (content) {
    this._userTyped = false
    if (this.editorReady) this.editor.setValue(content)
    else this.ready = Promise.resolve(this.ready).then(editor => { editor?.setValue(content); return editor })
  },
  /*
   * @description 插入内容到编辑器光标处（兼容冷启动队列）
   * @param {string} value 待插入内容
   */
  insertContent: function (value) {
    this._userTyped = true
    if (this.editorReady && this.editor && typeof this.editor.insertValue === 'function') {
      this.editor.insertValue(value)
    } else {
      this.ready = Promise.resolve(this.ready).then(editor => {
        if (editor && typeof editor.insertValue === 'function') {
          editor.insertValue(value)
        }
        return editor
      })
    }
  },
  /*
   * @description 移除编辑器值
   */
  remove: function () {
    document.getElementById(this.editor.vditor.id).outerHTML = ''
  },
})

admin.editors.articleEditor = {}
admin.editors.abstractEditor = {}
