/* SPDX-License-Identifier: AGPL-3.0-or-later */
admin.security = {
  geoAccessRules: [],
  geoAccessLoaded: false,
  geoAccessRequest: 0,
  loadGeoAccess: function () {
    var list = document.getElementById('geoAccessRules'), that = this
    if (!list) return
    var revision = ++this.geoAccessRequest
    this.geoEdit = -1
    this.geoAccessLoaded = false
    $('#geoAccessSave,#geoAccessAdd').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/geo-access', type: 'GET', cache: false,
      complete: function () { $('#loadMsg').text('') },
      success: function (result) {
        if (!list.isConnected || revision !== that.geoAccessRequest) return
        if (!result.sc) { $('#geoAccessMessage').text(result.msg); return }
        that.geoAccessRules = result.rules.map(function (rule) { return Object.assign({}, rule) })
        that.geoAccessLoaded = true
        $('#geoAccessActive').val(result.active ? 'true' : 'false')
        that.geoDefaultAction = result.defaultAction || 'allow'
        $('#geoAccessLegacyFallback').text(that.geoDefaultAction === 'deny' ? '兼容旧配置：所有规则均未命中或被禁用时，仍拒绝访问。' : '')
        $('#geoAccessUnknown').val(result.unknown)
        $('#geoAccessCurrent').text('当前连接位置：' + (result.currentRegion || result.currentCountryName) + (result.currentCountry ? '（' + result.currentCountry + '）' : '') + (result.enabled ? '' : '；服务器配置已暂停地区限制'))
        $('#geoAccessSave,#geoAccessAdd').prop('disabled', false)
        that.geoSavedPolicy = JSON.stringify(that.geoPolicy())
        that.renderGeoAccess()
      }, error: function (xhr) { if (list.isConnected && revision === that.geoAccessRequest) $('#geoAccessMessage').text(xhr.responseJSON?.msg || '无法读取地区访问规则。') }
    })
  },
  geoPage: 1,
  geoEdit: -1,
  element: function (tag, text, className) {
    var node = document.createElement(tag)
    if (text != null) node.textContent = text
    if (className) node.className = className
    return node
  },
  pager: function (id, page, count, action) {
    admin.pagination.render(id, page, count, action)
  },
  renderGeoAccess: function () {
    var list = document.getElementById('geoAccessRules'), that = this
    if (!list) return
    list.replaceChildren()
    $('#geoAccessRuleCount').text(this.geoAccessRules.length + ' / 128 条规则')
    $('#geoAccessAdd').prop('disabled', !this.geoAccessLoaded || this.geoAccessRules.length >= 128)
    this.geoPage = Math.min(Math.max(1, this.geoPage), Math.max(1, Math.ceil(this.geoAccessRules.length / 10)))
    if (!this.geoAccessRules.length) {
      list.append(this.element('p', this.geoDefaultAction === 'deny' ? '尚未添加规则；保留旧配置的拒绝访问策略。' : '尚未添加规则，已知位置的访问不受地区限制。', 'console-ip-ban-empty'))
    } else {
      var table = this.element('table', null, 'console-security-table'), head = this.element('thead'), row = this.element('tr'), body = this.element('tbody')
      var labels = ['优先级', '命中操作', '范围', '未命中时', '状态', '操作']
      labels.forEach(function (label) { var th = that.element('th', label); th.scope = 'col'; row.append(th) }); head.append(row)
      this.geoAccessRules.slice((this.geoPage - 1) * 10, this.geoPage * 10).forEach(function (rule, offset) {
        var index = (that.geoPage - 1) * 10 + offset, tr = that.element('tr')
        var values = [String(index + 1), (rule.action === 'allow' ? '允许' : '拒绝') + (rule.name ? ' · ' + rule.name : ''), [rule.countries, rule.regions].filter(Boolean).join('；') || '待填写', {next:'继续下一条',allow:'允许',deny:'拒绝'}[rule.onMiss || 'next'], rule.enabled ? '启用' : '禁用']
        values.forEach(function (value, i) {
          var td = that.element('td', i === 1 ? null : value); td.dataset.label = labels[i]
          if (i === 1) {
            td.append(that.element('span', rule.action === 'allow' ? '允许' : '拒绝'))
            if (rule.name) { var name = that.element('span', rule.name, 'console-rule-name'); name.title = rule.name; td.append(name) }
          }
          tr.append(td)
        })
        var actions = that.element('td'), group = that.element('div', null, 'console-row-actions'); actions.dataset.label = '操作'
        function button(text, disabled, action) { var b = that.element('button', text); b.type = 'button'; b.disabled = disabled || !!that.geoSaving; b.addEventListener('click', function () { if (!that.geoSaving) action() }); group.append(b) }
        function move(direction) {
          var target = index + direction
          that.geoAccessRules.splice(index, 1); that.geoAccessRules.splice(target, 0, rule)
          if (that.geoEdit === index) that.geoEdit = target
          else if (that.geoEdit === target) that.geoEdit = index
          that.geoPage = Math.floor(target / 10) + 1; that.renderGeoAccess()
        }
        var order = that.element('div', null, 'console-order-controls')
        function arrow(label, direction, unavailable) {
          if (unavailable) return
          var control = that.element('button', null, 'console-order-arrow')
          control.type = 'button'; control.title = label; control.setAttribute('aria-label', label); control.disabled = !!that.geoSaving
          var icon = that.element('span', null, direction < 0 ? 'icon-move-up' : 'icon-move-down'); icon.setAttribute('aria-hidden', 'true')
          control.append(icon); control.addEventListener('click', function () { if (!that.geoSaving) move(direction) }); order.append(control)
        }
        arrow('上移', -1, index === 0)
        arrow('下移', 1, index === that.geoAccessRules.length - 1)
        tr.children[0].className = 'console-order-cell'
        tr.children[0].replaceChildren(order, that.element('span', String(index + 1), 'console-order-index'))
        button('编辑', false, function () { that.geoEdit = index; that.renderGeoAccess(); document.getElementById('geoRuleEditor').scrollIntoView({behavior:'smooth',block:'nearest'}) })
        button(rule.enabled ? '禁用' : '启用', !!that.geoSaving, function () { that.toggleGeoRule(index) })
        button('移除', false, function () { that.geoAccessRules.splice(index, 1); that.geoEdit = -1; that.renderGeoAccess() })
        actions.append(group); tr.append(actions); body.append(tr)
      })
      table.append(head, body); list.append(table)
    }
    this.pager('geoAccessPagination', this.geoPage, Math.ceil(this.geoAccessRules.length / 10), function (page) { that.geoPage = page; that.renderGeoAccess() })
    var rule = this.geoAccessRules[this.geoEdit]
    if (!rule) return
    var card = this.element('fieldset', null, 'console-geo-rule'), legend = this.element('legend', '编辑规则 ' + (this.geoEdit + 1))
    card.id = 'geoRuleEditor'; card.append(legend)
    var fields = this.element('div', null, 'console-geo-rule-fields')
    function field(label, key, type, placeholder, limit) {
      var wrapper = that.element('label', null, 'console-geo-rule-field console-geo-rule-' + key), caption = that.element('span', label), input = that.element(type === 'select' || type === 'textarea' ? type : 'input')
      if (type === 'select') (key === 'onMiss' ? [['next','继续下一条'],['allow','允许访问'],['deny','拒绝访问']] : [['allow','允许访问'],['deny','拒绝访问']]).forEach(function (item) { var option = that.element('option', item[1]); option.value = item[0]; input.append(option) })
      else { if (type !== 'textarea') input.type = 'text'; input.placeholder = placeholder; input.maxLength = limit; if (type === 'textarea') input.rows = 2 }
      input.value = rule[key] || ''
      input.addEventListener(type === 'select' ? 'change' : 'input', function () { rule[key] = input.value })
      wrapper.append(caption, input); fields.append(wrapper)
    }
    field('命中范围时', 'action', 'select')
    field('未命中本规则时', 'onMiss', 'select')
    field('备注（选填）', 'name', 'text', '例如：允许公司所在城市', 80)
    field('国家代码（选填）', 'countries', 'text', 'CN 或 US,CA', 1000)
    field('国家或省市范围（每行一项）', 'regions', 'textarea', 'CN 或 CN/广东省/深圳市', 10000)
    var enabledLabel = this.element('label'), enabled = this.element('input'); enabled.type = 'checkbox'; enabled.checked = rule.enabled
    enabled.addEventListener('change', function () { rule.enabled = enabled.checked }); enabledLabel.append(enabled, document.createTextNode('启用本条规则'))
    var done = this.element('button', '完成编辑'); done.type = 'button'; done.addEventListener('click', function () { that.geoEdit = -1; that.renderGeoAccess() })
    card.append(fields, enabledLabel, done); list.append(card)
  },
  addGeoAccessRule: function () {
    if (this.geoSaving || !this.geoAccessLoaded || this.geoAccessRules.length >= 128) return
    this.geoAccessRules.push({action:'deny',onMiss:'next',enabled:true,name:'',countries:'',regions:''})
    this.geoEdit = this.geoAccessRules.length - 1; this.geoPage = Math.floor(this.geoEdit / 10) + 1
    this.renderGeoAccess()
    document.getElementById('geoRuleEditor').querySelector('.console-geo-rule-countries input').focus()
  },
  geoPolicy: function () {
    return {version: 3, active: $('#geoAccessActive').val() === 'true', defaultAction: this.geoDefaultAction || 'allow', unknown: $('#geoAccessUnknown').val(), rules: this.geoAccessRules}
  },
  toggleGeoRule: function (index) {
    if (this.geoSaving || !this.geoAccessLoaded || !this.geoAccessRules[index]) return
    if (JSON.stringify(this.geoPolicy()) !== this.geoSavedPolicy) { $('#geoAccessMessage').text('有尚未保存的修改，请先保存地区规则，再使用快捷启用／禁用。'); return }
    var rule = this.geoAccessRules[index], enabled = rule.enabled, that = this
    rule.enabled = !enabled
    this.geoSaving = true
    this.renderGeoAccess()
    this.saveGeoAccess(function () { rule.enabled = enabled; that.renderGeoAccess() })
  },
  saveGeoAccess: function (onFailure) {
    var button = document.getElementById('geoAccessSave')
    if (!button || button.disabled || !this.geoAccessLoaded) return
    button.disabled = true
    this.geoSaving = true
    function failure(message) { admin.security.geoSaving = false; $('#geoAccessMessage').text(message); if (typeof onFailure === 'function') onFailure() }
    $.ajax({url: Label.servePath + '/console/geo-access', type: 'PUT', contentType: 'application/json', global: false,
      data: JSON.stringify(this.geoPolicy()),
      complete: function () { admin.security.geoSaving = false; button.disabled = !admin.security.geoAccessLoaded },
      success: function (result) { if (result.sc) { $('#geoAccessMessage').text(result.msg || '地区规则已保存。'); admin.security.loadGeoAccess() } else failure(result.msg || '地区规则保存失败，请重试。') },
      error: function (xhr) { failure(xhr.responseJSON?.msg || '地区规则保存失败，请重试。') }
    })
  },
  ipBanPage: 1,
  ipBanRequest: 0,
  ipBanEditing: null,
  ipBanSaving: false,
  loadIpBans: function (page) {
    var list = document.getElementById('ipBanList')
    if (!list) return
    var that = this, revision = ++this.ipBanRequest
    var pager = document.getElementById('ipBanPagination'), total = document.getElementById('ipBanTotal')
    var query = document.getElementById('ipBanQuery')
    page = page || this.ipBanPage
    list.setAttribute('aria-busy', 'true')
    $.ajax({url: Label.servePath + '/console/ip-bans', type: 'GET', cache: false,
      data: {page: page, pageSize: 10, keyword: query ? query.value.trim() : '', source: $('#ipBanSource').val() || 'all', status: $('#ipBanStatus').val() || 'active'},
      complete: function () { $('#loadMsg').text(''); if (revision === that.ipBanRequest) list.removeAttribute('aria-busy') },
      success: function (result) {
        if (revision !== that.ipBanRequest || !list.isConnected) return
        list.replaceChildren()
        if (pager) pager.replaceChildren()
        if (!result.sc) { list.textContent = result.msg || '无法读取封禁列表。'; return }
        that.ipBanPage = result.page
        if (total) total.textContent = '共 ' + result.total + ' 条'
        if (!result.rules.length) {
          var empty = document.createElement('p'); empty.className = 'console-ip-ban-empty'
          empty.textContent = query?.value.trim() ? '没有匹配的封禁规则。' : '暂无封禁规则。'
          list.append(empty); return
        }
        var table = document.createElement('table'), head = document.createElement('thead'), heading = document.createElement('tr'), body = document.createElement('tbody')
        var labels = ['IP / 网段', '位置', '来源与原因', '有效期', '操作']
        table.className = 'console-ip-ban-table'
        labels.forEach(function (label) { var cell = document.createElement('th'); cell.scope = 'col'; cell.textContent = label; heading.append(cell) })
        head.append(heading)
        result.rules.forEach(function (rule) {
          var row = document.createElement('tr'), button = document.createElement('button'), edit = document.createElement('button')
          var createdStr = rule.created ? that.formatDate(rule.created, 1) : '—'
          var reason = (rule.source === 'automatic' ? '自动风控' : '手动封禁') + '：' + (rule.reason || '未填写原因')
          if (rule.source === 'automatic') reason += '\n累计触发 ' + (rule.occurrences == null ? 1 : rule.occurrences) + ' 次'
          if (rule.note) reason += '\n备注：' + rule.note
          var values = [rule.range, rule.region || '位置未知', reason, (rule.active === false ? '已到期 · ' : '') + (rule.expiresAt ? that.formatDate(rule.expiresAt, 1) : '永久') + '\n添加于 ' + createdStr]
          if (rule.updated) values[3] += '\n更新于 ' + that.formatDate(rule.updated, 1)
          values.forEach(function (value, index) { var cell = document.createElement('td'); cell.dataset.label = labels[index]; cell.textContent = value; row.append(cell) })
          var actions = document.createElement('td'); actions.dataset.label = labels[4]
          var group = document.createElement('div'); group.className = 'console-row-actions'
          edit.type = 'button'; edit.textContent = rule.active === false ? '重新生效' : '编辑封禁'
          edit.addEventListener('click', function () { admin.security.editIpBan(rule) })
          button.type = 'button'; button.textContent = rule.active === false ? '移除记录' : '解除封禁'
          button.addEventListener('click', function () { admin.security.removeIpBan(rule.range) })
          group.append(edit, button); actions.append(group); row.append(actions); body.append(row)
        })
        table.append(head, body); list.append(table)
        that.pager('ipBanPagination', result.page, result.pageCount, function (target) { that.loadIpBans(target) })
      }, error: function (xhr) {
        if (revision !== that.ipBanRequest || !list.isConnected) return
        list.textContent = xhr.responseJSON?.msg || '无法读取封禁列表。'
        if (pager) pager.replaceChildren()
        if (total) total.textContent = ''
      }
    })
  },
  formatDate: function (value, mode) {
    if (typeof Util !== 'undefined' && Util && typeof Util.formatDate === 'function') {
      return Util.formatDate(value, mode === undefined ? 1 : mode)
    }
    if (value === null || value === undefined || typeof value === 'boolean' || value === '') return '—'
    var d = value instanceof Date ? value : new Date(typeof value === 'string' && /^\d+$/.test(value.trim()) ? Number(value.trim()) : value)
    if (!Number.isFinite(d.getTime()) || d.getTime() < 0) return '—'
    var pad = function (n) { return (n < 10 ? '0' : '') + n }
    var ymd = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    var m = mode === undefined ? 1 : mode
    if (m === 1) return ymd + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
    if (m === 2) return ymd + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
    return ymd
  },
  addIpBan: function () {
    if (this.ipBanSaving) return
    var editing = this.ipBanEditing, keep = editing && $('#ipBanDuration').val() === 'keep'
    var duration = keep ? null : this.durationSeconds()
    if (!keep && duration == null) return
    var data = {range: editing ? editing.range : $('#ipBanRange').val()}
    data[editing ? 'note' : 'reason'] = $('#ipBanReason').val()
    if (!keep) data.durationSeconds = duration
    if (editing && editing.source === 'automatic') {
      var count = String($('#ipBanOccurrences').val()), occurrences = Number(count)
      if (!/^[0-9]{1,7}$/.test(count) || !Number.isInteger(occurrences) || occurrences > 1000000) { $('#ipBanMessage').text('自动风控次数应为 0 至 1000000 的整数。'); return }
      data.occurrences = occurrences
    }
    this.ipBanSaving = true
    $('#ipBanSubmit,#ipBanCancel').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/ip-bans', type: editing ? 'PUT' : 'POST', contentType: 'application/json',
      data: JSON.stringify(data),
      complete: function () { admin.security.ipBanSaving = false; $('#ipBanSubmit,#ipBanCancel').prop('disabled', false) },
      success: function (result) { $('#ipBanMessage').text(result.msg); if (result.sc) {
        admin.security.cancelIpBanEdit(); $('#ipBanRange').val(''); $('#ipBanReason').val(''); $('#ipBanQuery').val('')
        if (editing && editing.active === false) $('#ipBanStatus').val('active')
        admin.security.loadIpBans(editing ? admin.security.ipBanPage : 1)
      } },
      error: function (xhr) { $('#ipBanMessage').text(xhr.responseJSON?.msg || '封禁失败，请重试。') }
    })
  },
  editIpBan: function (rule) {
    if (this.ipBanSaving) return
    this.ipBanEditing = rule
    $('#ipBanRange').val(rule.range).prop('readOnly', true)
    $('#ipBanReason').val(Object.prototype.hasOwnProperty.call(rule, 'note') ? rule.note : rule.source === 'automatic' ? '' : rule.reason || '')
    document.getElementById('ipBanOccurrenceField').hidden = rule.source !== 'automatic'
    $('#ipBanOccurrences').val(rule.occurrences == null ? 1 : rule.occurrences)
    var keep = document.querySelector('#ipBanDuration option[value="keep"]')
    keep.hidden = rule.active === false; keep.disabled = rule.active === false
    $('#ipBanDuration').val(rule.active === false ? '86400' : 'keep').trigger('change')
    var message = document.getElementById('ipBanEditing')
    message.hidden = false
    message.textContent = (rule.active === false ? '重新生效：' : '编辑封禁：') + rule.range + '。原始来源和触发原因会保留。' + (rule.active === false ? '新期限从保存时开始计算。' : '修改期限时从保存时重新计算；保留原到期时间则不延长封禁。')
    document.getElementById('ipBanCancel').hidden = false
    $('#ipBanSubmit').text(rule.active === false ? '保存并重新生效' : '保存修改')
    document.getElementById('ipBanForm').scrollIntoView({behavior: 'smooth', block: 'center'})
    document.getElementById('ipBanReason').focus({preventScroll: true})
  },
  cancelIpBanEdit: function () {
    this.ipBanEditing = null
    $('#ipBanRange').val('').prop('readOnly', false); $('#ipBanReason').val('')
    var keep = document.querySelector('#ipBanDuration option[value="keep"]')
    keep.hidden = true; keep.disabled = true
    $('#ipBanDuration').val('0').trigger('change')
    document.getElementById('ipBanEditing').hidden = true
    document.getElementById('ipBanOccurrenceField').hidden = true
    document.getElementById('ipBanCancel').hidden = true
    $('#ipBanSubmit').text('添加封禁')
  },
  removeIpBan: function (range) {
    if (!confirm('解除 ' + range + ' 的封禁？')) return
    $.ajax({url: Label.servePath + '/console/ip-bans/remove', type: 'POST', contentType: 'application/json', data: JSON.stringify({range: range}),
      success: function (result) { $('#ipBanMessage').text(result.msg); if (result.sc) admin.security.loadIpBans() },
      error: function (xhr) { $('#ipBanMessage').text(xhr.responseJSON?.msg || '解除封禁失败，请重试。') }
    })
  },
  durationSeconds: function () {
    var duration = $('#ipBanDuration').val()
    if (duration !== 'custom') return Number(duration)
    var days = Number($('#ipBanCustomDays').val())
    if (!Number.isInteger(days) || days < 1 || days > 3650) { $('#ipBanMessage').text('自定义时长应为 1 至 3650 天。'); return null }
    return days * 86400
  },
  initIpBans: function () {
    var that = this
    this.ipBanEditing = null; this.ipBanSaving = false
    $('#ipBanDuration').on('change', function () { document.querySelector('.console-duration-custom').hidden = this.value !== 'custom' })
    $('#ipBanSource,#ipBanStatus').on('change', function () { that.loadIpBans(1) })
    this.loadIpBans()
  },
  settingsLoaded: {},
  settingsRequests: {},
  loadSettings: function (section) {
    var that = this
    var root = document.querySelector('[data-security-page="' + section + '"]')
    if (!root) return
    var revision = this.settingsRequests[section] = (this.settingsRequests[section] || 0) + 1
    this.settingsLoaded[section] = false
    var buttons = root.querySelectorAll('.console-form-actions button')
    buttons.forEach(function (button) { button.disabled = true })
    $.ajax({url:Label.servePath + '/console/security/settings',cache:false,complete:function () { $('#loadMsg').text('') },success:function (result) {
      if (!root.isConnected || revision !== that.settingsRequests[section]) return
      if (!result.sc) { $('#tipMsg').text(result.msg); return }
      Object.keys(result.settings).forEach(function (key) {
        var domId = key === 'adminLoginPath' ? 'adminLoginPathSetting' : key
        var input = root.querySelector('#' + domId)
        if (input) input.value = result.settings[key]
      })
      that.settingsLoaded[section] = true
      buttons.forEach(function (button) { button.disabled = false })
    },error:function (xhr) { $('#tipMsg').text(xhr.responseJSON?.msg || '安全配置读取失败，请刷新重试。') }})
  },
  saveSettings: function () {
    var section = window.location.hash.split('/')[1], data = {}
    if (!this.settingsLoaded[section]) return
    var keys = section === 'comments' ? ['commentAccessMode','commentModerationMode','commentFilterBuiltin','spam','commentFilterAllow','commentCaptchaEnabled'] : ['riskControlEnabled','wafPower','wafCurrentLimitTimes','wafCurrentLimitSecond','adminLoginPath']
    var root = document.querySelector && document.querySelector('[data-security-page="' + section + '"]')
    keys.forEach(function (key) {
      var domId = key === 'adminLoginPath' ? 'adminLoginPathSetting' : key
      var scoped = root && root.querySelector ? root.querySelector('#' + domId) : null
      data[key] = scoped ? scoped.value : $('#' + domId).val()
    })
    $.ajax({url:Label.servePath + '/console/security/settings',type:'PUT',contentType:'application/json',data:JSON.stringify(data),
      success:function (result) { $('#tipMsg').text(result.msg); if (result.sc && data.adminLoginPath != null) $('#adminLoginPathCurrent').text(data.adminLoginPath.trim() || '/start') },error:function (xhr) { $('#tipMsg').text(xhr.responseJSON?.msg || '安全配置保存失败。') }})
  }
}
Object.assign(admin.security, {
  totpState: null,
  totpSetup: null,
  totpRecoveryPlain: [],
  totpActionType: '',
  totpRequest: 0,
  totpBusy: false,
  loadTotp: function () {
    var root = document.querySelector('[data-security-page="totp"]'), that = this
    if (!root) return
    var revision = ++this.totpRequest
    this.cancelTotpSetup()
    this.closeTotpAction()
    this.totpRecoveryPlain = []
    var recPanel = document.getElementById('totpRecoveryPanel'), recList = document.getElementById('totpRecoveryList'), setMsg = document.getElementById('totpSettingsMessage')
    if (recPanel) recPanel.hidden = true
    if (recList) recList.replaceChildren()
    if (setMsg) setMsg.textContent = ''
    $('#totpSaveSettings,#totpStartSetup').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/security/totp', type: 'GET', cache: false,
      complete: function () { $('#loadMsg').text('') },
      success: function (result) {
        if (!root.isConnected || revision !== that.totpRequest) return
        if (!result.sc || !result.totp) { $('#totpSummary').text(result.msg || '无法读取 2FA 状态。'); return }
        that.applyTotpState(result.totp)
      },
      error: function (xhr) {
        if (root.isConnected && revision === that.totpRequest) {
          $('#totpSummary').text(xhr.responseJSON?.msg || '无法读取 2FA 状态，请刷新重试。')
        }
      }
    })
  },
  applyTotpState: function (totp) {
    this.totpState = totp
    var badge = document.getElementById('totpStatusBadge')
    if (badge) {
      var state = totp.enabled ? 'enabled' : totp.bound ? 'paused' : 'unbound'
      badge.dataset.state = state
      badge.textContent = totp.enabled ? '已启用' : totp.bound ? '已绑定（停用）' : '未配置'
    }
    var summary = document.getElementById('totpSummary')
    if (summary) {
      if (totp.bound) {
        var boundFmt = totp.boundAt ? this.formatDate(totp.boundAt, 1) : '—'
        var timeText = boundFmt !== '—' ? ' · 绑定于 ' + boundFmt : ''
        summary.textContent = '绑定账号：' + (totp.boundUser || '管理员') + timeText + ' · 剩余应急恢复码：' + (totp.recoveryRemaining || 0) + ' / 8 组'
      } else {
        summary.textContent = '当前尚未绑定 2FA 密钥。请点击下方“生成绑定密钥”使用身份验证器应用扫码绑定。'
      }
    }
    var setupBtn = document.getElementById('totpStartSetup')
    if (setupBtn) {
      setupBtn.disabled = false
      setupBtn.textContent = totp.bound ? '更换绑定密钥' : '生成绑定密钥'
    }
    var regenBtn = document.getElementById('totpRegenRecoveryBtn')
    if (regenBtn) regenBtn.hidden = !totp.bound
    var unbindBtn = document.getElementById('totpUnbindBtn')
    if (unbindBtn) unbindBtn.hidden = !totp.bound
    var revokeBtn = document.getElementById('totpRevokeDevicesBtn')
    if (revokeBtn) revokeBtn.hidden = !(totp.bound && Number(totp.rememberDays) > 0)
    $('#totpEnabled').val(totp.enabled ? 'true' : 'false')
    $('#totpScope').val(totp.scope || 'admin')
    var issuerInput = document.getElementById('totpIssuer')
    if (issuerInput) {
      issuerInput.value = totp.issuer || ''
      issuerInput.placeholder = '留空默认使用 ' + (totp.defaultIssuer || 'Volo')
    }
    $('#totpWindowSteps').val(String(totp.windowSteps == null ? 1 : totp.windowSteps))
    $('#totpRememberDays').val(String(totp.rememberDays || 0))
    $('#totpSaveSettings').prop('disabled', false)
    this.syncTotpToggleVerify()
  },
  syncTotpToggleVerify: function () {
    var wrap = document.getElementById('totpToggleVerifyWrap')
    var input = document.getElementById('totpSettingsVerifyCode')
    if (!wrap || !this.totpState) return
    var targetOn = $('#totpEnabled').val() === 'true'
    var changed = this.totpState.bound && (targetOn !== !!this.totpState.enabled)
    wrap.hidden = !changed
    if (!changed && input) input.value = ''
  },
  renderTotpQr: function (uri) {
    var host = document.getElementById('totpQrCode')
    if (!host) return
    host.replaceChildren()
    function draw () {
      if (!host.isConnected || typeof $.fn.qrcode !== 'function') return
      host.replaceChildren()
      $(host).qrcode({render: 'canvas', width: 176, height: 176, correctLevel: 0, background: '#ffffff', foreground: '#14201b', text: uri})
    }
    if (typeof $.fn.qrcode === 'function') {
      draw()
    } else {
      $.ajax({method: 'GET', url: (window.Label && Label.staticServePath || '') + '/js/lib/jquery.qrcode.min.js', dataType: 'script', cache: true, success: draw})
    }
  },
  startTotpSetup: function () {
    if (this.totpBusy) return
    var that = this
    this.totpBusy = true
    this.closeTotpAction()
    $('#totpStartSetup').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/security/totp/setup', type: 'POST', contentType: 'application/json', data: '{}',
      complete: function () { that.totpBusy = false; $('#totpStartSetup').prop('disabled', false) },
      success: function (result) {
        if (!result.sc || !result.setup) { $('#totpSettingsMessage').text(result.msg || '生成绑定密钥失败。'); return }
        that.totpSetup = result.setup
        document.getElementById('totpRecoveryPanel').hidden = true
        var panel = document.getElementById('totpSetupPanel')
        panel.hidden = false
        document.getElementById('totpSecretText').textContent = result.setup.formattedSecret || result.setup.secret
        document.getElementById('totpSetupMeta').textContent = '验证器标识：' + result.setup.issuer + ' · 账号：' + result.setup.account + ' · 周期：30 秒（6 位）'
        var currentWrap = document.getElementById('totpCurrentCodeWrap')
        currentWrap.hidden = !result.setup.requireCurrentCode
        document.getElementById('totpCurrentCode').value = ''
        document.getElementById('totpSetupCode').value = ''
        document.getElementById('totpSetupMessage').textContent = ''
        that.renderTotpQr(result.setup.otpauthUri)
        panel.scrollIntoView({behavior: 'smooth', block: 'nearest'})
        ;(result.setup.requireCurrentCode ? document.getElementById('totpCurrentCode') : document.getElementById('totpSetupCode')).focus()
      },
      error: function (xhr) { $('#totpSettingsMessage').text(xhr.responseJSON?.msg || '生成绑定密钥失败，请重试。') }
    })
  },
  cancelTotpSetup: function () {
    this.totpSetup = null
    var panel = document.getElementById('totpSetupPanel')
    if (panel) panel.hidden = true
    var qr = document.getElementById('totpQrCode')
    if (qr) qr.replaceChildren()
    var msg = document.getElementById('totpSetupMessage'), secText = document.getElementById('totpSecretText'), secMeta = document.getElementById('totpSetupMeta'), curCode = document.getElementById('totpCurrentCode'), setCode = document.getElementById('totpSetupCode')
    if (msg) msg.textContent = ''
    if (secText) secText.textContent = ''
    if (secMeta) secMeta.textContent = ''
    if (curCode) curCode.value = ''
    if (setCode) setCode.value = ''
  },
  confirmTotpSetup: function () {
    if (this.totpBusy || !this.totpSetup) return
    var code = ($('#totpSetupCode').val() || '').replace(/\s+/g, '')
    if (!/^[0-9]{6}$/.test(code)) {
      $('#totpSetupMessage').text('请输入验证器显示的 6 位数字口令。')
      document.getElementById('totpSetupCode').focus()
      return
    }
    var currentCode = ($('#totpCurrentCode').val() || '').trim()
    if (this.totpSetup.requireCurrentCode && !currentCode) {
      $('#totpSetupMessage').text('更换密钥需输入原验证器口令或应急恢复码。')
      document.getElementById('totpCurrentCode').focus()
      return
    }
    var that = this
    this.totpBusy = true
    $('#totpConfirmBtn').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/security/totp/confirm', type: 'POST', contentType: 'application/json',
      data: JSON.stringify({setupId: this.totpSetup.setupId, code: code, currentCode: currentCode}),
      complete: function () { that.totpBusy = false; $('#totpConfirmBtn').prop('disabled', false) },
      success: function (result) {
        if (!result.sc) { $('#totpSetupMessage').text(result.msg || '验证失败，请重试。'); return }
        that.cancelTotpSetup()
        that.applyTotpState(result.totp)
        that.showRecoveryCodes(result.recoveryCodes || [])
        $('#totpSettingsMessage').text('')
        if (admin.showNotice) admin.showNotice(result.msg || '2FA 已绑定并启用。')
        else $('#tipMsg').text(result.msg || '2FA 已绑定并启用。')
      },
      error: function (xhr) { $('#totpSetupMessage').text(xhr.responseJSON?.msg || '验证失败，请确认口令与系统时间后重试。') }
    })
  },
  showRecoveryCodes: function (codes) {
    this.totpRecoveryPlain = Array.isArray(codes) ? codes.slice() : []
    var panel = document.getElementById('totpRecoveryPanel'), list = document.getElementById('totpRecoveryList')
    if (!panel || !list || !this.totpRecoveryPlain.length) return
    list.replaceChildren()
    var that = this
    this.totpRecoveryPlain.forEach(function (item) {
      list.append(that.element('code', item, 'console-totp-recovery-item'))
    })
    panel.hidden = false
    panel.scrollIntoView({behavior: 'smooth', block: 'nearest'})
  },
  saveTotpSettings: function () {
    if (this.totpBusy || !this.totpState) return
    var enabledVal = $('#totpEnabled').val(), msgNode = document.getElementById('totpSettingsMessage')
    if (msgNode) delete msgNode.dataset.tone
    if (enabledVal === 'true' && !this.totpState.bound) {
      $('#totpSettingsMessage').text('请先点击上方“生成绑定密钥”完成扫码绑定后再启用 2FA。')
      return
    }
    var verifyCode = ($('#totpSettingsVerifyCode').val() || '').trim()
    if (this.totpState.bound && ((enabledVal === 'true') !== !!this.totpState.enabled) && !verifyCode) {
      $('#totpSettingsMessage').text(enabledVal === 'true' ? '重新启用 2FA 需输入当前 6 位动态口令。' : '停用 2FA 需输入当前 6 位动态口令或应急恢复码。')
      document.getElementById('totpSettingsVerifyCode').focus()
      return
    }
    var payload = {
      enabled: enabledVal,
      scope: $('#totpScope').val(),
      issuer: $('#totpIssuer').val(),
      windowSteps: Number($('#totpWindowSteps').val()),
      rememberDays: Number($('#totpRememberDays').val()),
      verifyCode: verifyCode
    }
    var that = this
    this.totpBusy = true
    $('#totpSaveSettings').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/security/totp/settings', type: 'PUT', contentType: 'application/json',
      data: JSON.stringify(payload),
      complete: function () { that.totpBusy = false; $('#totpSaveSettings').prop('disabled', false) },
      success: function (result) {
        var feedback = document.getElementById('totpSettingsMessage')
        if (!result.sc) { if (feedback) { feedback.dataset.tone = 'error'; feedback.textContent = result.msg || '配置保存失败。' } return }
        document.getElementById('totpSettingsVerifyCode').value = ''
        if (feedback) { feedback.dataset.tone = 'ok'; feedback.textContent = result.msg || '配置已保存。' }
        that.applyTotpState(result.totp)
        if (admin.showNotice) admin.showNotice(result.msg || '2FA 配置已保存。')
      },
      error: function (xhr) { var feedback = document.getElementById('totpSettingsMessage'); if (feedback) { feedback.dataset.tone = 'error'; feedback.textContent = xhr.responseJSON?.msg || '2FA 配置保存失败，请重试。' } }
    })
  },
  openTotpAction: function (type) {
    if (this.totpBusy || !this.totpState || !this.totpState.bound) return
    if (type === 'unbind' && !this.totpState.enabled) {
      if (!confirm('确定解绑并清除已保存的 2FA 密钥与恢复码？')) return
      this.executeTotpUnbind('')
      return
    }
    this.cancelTotpSetup()
    this.totpActionType = type
    var panel = document.getElementById('totpActionPanel')
    document.getElementById('totpActionTitle').textContent = type === 'recovery' ? '重新生成应急恢复码' : '解绑并关闭 2FA'
    document.getElementById('totpActionDesc').textContent = type === 'recovery'
      ? '生成新的 8 组应急恢复码后，原有未使用的恢复码将立即作废。请输入验证器当前显示的 6 位动态口令确认：'
      : '解绑后后台登录将不再要求动态口令，原有密钥与应急恢复码会被清除。请输入 6 位动态口令或应急恢复码确认：'
    document.getElementById('totpActionSubmit').textContent = type === 'recovery' ? '确认重新生成' : '确认解绑'
    document.getElementById('totpActionCode').value = ''
    document.getElementById('totpActionMessage').textContent = ''
    panel.hidden = false
    panel.scrollIntoView({behavior: 'smooth', block: 'nearest'})
    document.getElementById('totpActionCode').focus()
  },
  closeTotpAction: function () {
    this.totpActionType = ''
    var panel = document.getElementById('totpActionPanel')
    if (panel) panel.hidden = true
    var code = document.getElementById('totpActionCode')
    if (code) code.value = ''
    var msg = document.getElementById('totpActionMessage')
    if (msg) msg.textContent = ''
  },
  submitTotpAction: function () {
    if (this.totpBusy || !this.totpActionType) return
    var code = ($('#totpActionCode').val() || '').trim()
    if (!code) {
      $('#totpActionMessage').text('请输入动态口令确认操作。')
      document.getElementById('totpActionCode').focus()
      return
    }
    if (this.totpActionType === 'unbind') {
      this.executeTotpUnbind(code)
      return
    }
    var that = this
    this.totpBusy = true
    $('#totpActionSubmit').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/security/totp/recovery', type: 'POST', contentType: 'application/json',
      data: JSON.stringify({verifyCode: code}),
      complete: function () { that.totpBusy = false; $('#totpActionSubmit').prop('disabled', false) },
      success: function (result) {
        if (!result.sc) { $('#totpActionMessage').text(result.msg || '生成恢复码失败。'); return }
        that.closeTotpAction()
        that.applyTotpState(result.totp)
        that.showRecoveryCodes(result.recoveryCodes || [])
        if (admin.showNotice) admin.showNotice(result.msg || '应急恢复码已更新。')
      },
      error: function (xhr) { $('#totpActionMessage').text(xhr.responseJSON?.msg || '动态口令校验失败，请重试。') }
    })
  },
  executeTotpUnbind: function (verifyCode) {
    var that = this
    this.totpBusy = true
    $('#totpActionSubmit,#totpUnbindBtn').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/security/totp/unbind', type: 'POST', contentType: 'application/json',
      data: JSON.stringify({verifyCode: verifyCode}),
      complete: function () { that.totpBusy = false; $('#totpActionSubmit,#totpUnbindBtn').prop('disabled', false) },
      success: function (result) {
        if (!result.sc) { $('#totpActionMessage').text(result.msg || '解绑失败。'); return }
        that.closeTotpAction()
        document.getElementById('totpRecoveryPanel').hidden = true
        that.applyTotpState(result.totp)
        if (admin.showNotice) admin.showNotice(result.msg || '已解绑 2FA。')
      },
      error: function (xhr) {
        if (!document.getElementById('totpActionPanel').hidden) {
          $('#totpActionMessage').text(xhr.responseJSON?.msg || '解绑失败，请重试。')
        } else {
          $('#totpSettingsMessage').text(xhr.responseJSON?.msg || '解绑失败，请重试。')
        }
      }
    })
  },
  revokeTotpDevices: function () {
    if (this.totpBusy) return
    var that = this
    this.totpBusy = true
    $('#totpRevokeDevicesBtn').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/security/totp/revoke-devices', type: 'POST', contentType: 'application/json', data: '{}',
      complete: function () { that.totpBusy = false; $('#totpRevokeDevicesBtn').prop('disabled', false) },
      success: function (result) {
        if (result.sc) {
          that.applyTotpState(result.totp)
          if (admin.showNotice) admin.showNotice(result.msg || '已撤销全部记住的浏览器。')
        } else {
          $('#totpSettingsMessage').text(result.msg || '操作未完成。')
        }
      },
      error: function (xhr) { $('#totpSettingsMessage').text(xhr.responseJSON?.msg || '撤销失败，请重试。') }
    })
  },
  copyText: function (value, label) {
    if (!value) return
    function done () {
      if (admin.showNotice) admin.showNotice(label + '已复制到剪贴板。')
      else $('#tipMsg').text(label + '已复制到剪贴板。')
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(value).then(done).catch(function () {})
      return
    }
    var area = document.createElement('textarea')
    area.value = value
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.append(area)
    area.select()
    try { document.execCommand('copy'); done() } catch (ignored) {}
    area.remove()
  },
  copyTotpSecret: function () {
    if (this.totpSetup && this.totpSetup.secret) this.copyText(this.totpSetup.secret, '绑定密钥')
  },
  _b64UrlToBytes: function (b64url) {
    var str = String(b64url || '').replace(/-/g, '+').replace(/_/g, '/')
    while (str.length % 4 !== 0) str += '='
    var bin = typeof atob === 'function' ? atob(str) : Buffer.from(str, 'base64').toString('binary')
    var out = new Uint8Array(bin.length)
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
    return out
  },
  _bytesToB64Url: function (buf) {
    var bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf || 0)
    var bin = ''
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
    var b64 = typeof btoa === 'function' ? btoa(bin) : Buffer.from(bin, 'binary').toString('base64')
    return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  },
  _formatPasskeyTime: function (ms) {
    var ts = Number(ms || 0)
    if (!ts || ts <= 0) return '尚未使用'
    return this.formatDate(ts, 1)
  },
  loadPasskeys: function () {
    if (!document || typeof document.getElementById !== 'function') return
    var tbody = document.getElementById('passkeyListBody')
    if (!tbody) return
    var that = this
    $.ajax({
      url: Label.servePath + '/console/security/passkeys',
      type: 'GET',
      cache: false,
      success: function (res) {
        if (!tbody.isConnected) return
        if (!res || !res.sc || !res.data) {
          $('#passkeyMessage').text((res && res.msg) || '无法读取通行密钥列表。')
          return
        }
        that.renderPasskeys(res.data.credentials || [])
      },
      error: function (xhr) {
        if (tbody.isConnected) $('#passkeyMessage').text((xhr && xhr.responseJSON && xhr.responseJSON.msg) || '无法读取通行密钥列表。')
      }
    })
  },
  renderPasskeys: function (list) {
    if (!document || typeof document.getElementById !== 'function') return
    var tbody = document.getElementById('passkeyListBody')
    var badge = document.getElementById('passkeyStatusBadge')
    if (!tbody) return
    var creds = Array.isArray(list) ? list : []
    if (badge) {
      badge.dataset.state = creds.length > 0 ? 'enabled' : 'unbound'
      badge.textContent = creds.length > 0 ? ('已绑定 ' + creds.length + ' 个') : '未绑定'
    }
    tbody.replaceChildren()
    if (!creds.length) {
      var emptyRow = this.element('tr'), emptyCell = this.element('td', '尚未绑定任何通行密钥，点击上方“注册新通行密钥”即可添加。', 'console-simple-table__empty')
      emptyCell.colSpan = 5
      emptyRow.append(emptyCell)
      tbody.append(emptyRow)
      return
    }
    var that = this
    creds.forEach(function (item) {
      var tr = that.element('tr')
      var nameTd = that.element('td')
      nameTd.append(that.element('strong', item.name || '通行密钥'))
      var algName = Number(item.alg) === -7 ? 'ES256 (P-256)' : (Number(item.alg) === -257 ? 'RS256 (RSA)' : String(item.alg || 'FIDO2'))
      var algTd = that.element('td', algName)
      var createdTd = that.element('td', Number(item.createdAt || 0) > 0 ? that.formatDate(item.createdAt, 1) : '—')
      var usedTd = that.element('td', Number(item.lastUsedAt || 0) > 0 ? that._formatPasskeyTime(item.lastUsedAt) : '尚未使用')
      var actTd = that.element('td', null, 'is-right')
      var actGroup = that.element('div', null, 'console-row-actions is-right')
      var delBtn = that.element('button', '移除', 'is-danger')
      delBtn.type = 'button'
      delBtn.addEventListener('click', function () {
        that.removePasskey(item.id, item.name || '通行密钥')
      })
      actGroup.append(delBtn)
      actTd.append(actGroup)
      tr.append(nameTd, algTd, createdTd, usedTd, actTd)
      tbody.append(tr)
    })
  },
  registerPasskey: function (btn) {
    if (this.passkeyBusy) return
    if (typeof window === 'undefined' || !window.PublicKeyCredential || typeof navigator === 'undefined' || !navigator.credentials || typeof navigator.credentials.create !== 'function') {
      $('#passkeyMessage').text('当前浏览器或非安全上下文（需 HTTPS 或 localhost）不支持 WebAuthn 通行密钥。')
      return
    }
    var that = this
    var deviceName = String($('#passkeyDeviceName').val() || '').trim()
    this.passkeyBusy = true
    if (btn) btn.disabled = true
    $('#passkeyMessage').text('正在请求系统安全认证器…')
    $.ajax({
      url: Label.servePath + '/console/security/passkeys/register/options',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ name: deviceName }),
      success: function (res) {
        if (!res || !res.sc || !res.data) {
          that.passkeyBusy = false
          if (btn) btn.disabled = false
          $('#passkeyMessage').text((res && res.msg) || '无法初始化通行密钥绑定。')
          return
        }
        var opts = res.data
        var publicKey = {
          challenge: that._b64UrlToBytes(opts.challenge),
          rp: opts.rp,
          user: {
            id: that._b64UrlToBytes(opts.user.id),
            name: opts.user.name,
            displayName: opts.user.displayName || opts.user.name
          },
          pubKeyCredParams: opts.pubKeyCredParams || [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
          timeout: opts.timeout || 60000,
          attestation: opts.attestation || 'none',
          authenticatorSelection: opts.authenticatorSelection || { residentKey: 'preferred', userVerification: 'preferred' },
          excludeCredentials: (opts.excludeCredentials || []).map(function (c) {
            return { type: c.type || 'public-key', id: that._b64UrlToBytes(c.id) }
          })
        }
        navigator.credentials.create({ publicKey: publicKey }).then(function (cred) {
          if (!cred || !cred.response) throw new Error('未获取到有效的通行密钥凭证。')
          var verifyPayload = {
            challengeId: opts.challengeId,
            name: deviceName,
            credential: {
              id: cred.id,
              rawId: that._bytesToB64Url(cred.rawId),
              type: cred.type || 'public-key',
              response: {
                clientDataJSON: that._bytesToB64Url(cred.response.clientDataJSON),
                attestationObject: that._bytesToB64Url(cred.response.attestationObject)
              }
            }
          }
          $.ajax({
            url: Label.servePath + '/console/security/passkeys/register/verify',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(verifyPayload),
            complete: function () {
              that.passkeyBusy = false
              if (btn) btn.disabled = false
            },
            success: function (vRes) {
              if (!vRes || !vRes.sc) {
                $('#passkeyMessage').text((vRes && vRes.msg) || '通行密钥校验未通过。')
                return
              }
              $('#passkeyDeviceName').val('')
              $('#passkeyMessage').text('')
              if (vRes.data && vRes.data.credentials) that.renderPasskeys(vRes.data.credentials)
              else that.loadPasskeys()
              if (admin.showNotice) admin.showNotice(vRes.msg || '通行密钥已成功绑定。')
            },
            error: function (xhr) {
              $('#passkeyMessage').text((xhr && xhr.responseJSON && xhr.responseJSON.msg) || '通行密钥绑定失败，请重试。')
            }
          })
        }).catch(function (err) {
          that.passkeyBusy = false
          if (btn) btn.disabled = false
          var msg = (err && err.name === 'NotAllowedError') ? '已取消或超时未完成系统身份确认。' : ((err && err.message) || '通行密钥创建未完成。')
          $('#passkeyMessage').text(msg)
        })
      },
      error: function (xhr) {
        that.passkeyBusy = false
        if (btn) btn.disabled = false
        $('#passkeyMessage').text((xhr && xhr.responseJSON && xhr.responseJSON.msg) || '暂时无法生成通行密钥注册参数。')
      }
    })
  },
  removePasskey: function (id, name) {
    if (!id || this.passkeyBusy) return
    if (typeof confirm === 'function' && !confirm('确定移除通行密钥 "' + name + '" 吗？移除后仍可使用账号密码登录。')) return
    var that = this
    this.passkeyBusy = true
    $.ajax({
      url: Label.servePath + '/console/security/passkeys/' + encodeURIComponent(id),
      type: 'DELETE',
      complete: function () { that.passkeyBusy = false },
      success: function (res) {
        if (!res || !res.sc) {
          $('#passkeyMessage').text((res && res.msg) || '移除通行密钥失败。')
          return
        }
        $('#passkeyMessage').text('')
        if (res.data && res.data.credentials) that.renderPasskeys(res.data.credentials)
        else that.loadPasskeys()
        if (admin.showNotice) admin.showNotice(res.msg || '通行密钥已移除。')
      },
      error: function (xhr) {
        $('#passkeyMessage').text((xhr && xhr.responseJSON && xhr.responseJSON.msg) || '移除通行密钥失败，请重试。')
      }
    })
  },
  copyRecoveryCodes: function () {
    if (this.totpRecoveryPlain && this.totpRecoveryPlain.length) this.copyText(this.totpRecoveryPlain.join('\n'), '应急恢复码')
  }
})
admin.register.access = {obj:admin.security,init:function () { this.loadSettings('access') },refresh:function () { this.loadSettings('access') }}
admin.register.totp = {obj:admin.security,init:function () { this.loadTotp(); this.loadPasskeys() },refresh:function () { this.loadTotp(); this.loadPasskeys() }}
admin.register.ip = {obj:admin.security,init:admin.security.initIpBans,refresh:function () { if (this.ipBanEditing) this.cancelIpBanEdit(); this.loadIpBans() }}
admin.register.geo = {obj:admin.security,init:admin.security.loadGeoAccess,refresh:admin.security.loadGeoAccess}
admin.register.comments = {obj:admin.security,init:function () { this.loadSettings('comments') },refresh:function () { this.loadSettings('comments') }}
