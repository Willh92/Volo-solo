/* SPDX-License-Identifier: AGPL-3.0-or-later */
admin.security = {
  geoAccessRules: [],
  geoAccessLoaded: false,
  geoAccessRequest: 0,
  loadGeoAccess: function () {
    var list = document.getElementById('geoAccessRules'), that = this
    if (!list) return
    var revision = ++this.geoAccessRequest
    this.geoAccessLoaded = false
    $('#geoAccessSave,#geoAccessAdd').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/geo-access', type: 'GET', cache: false,
      complete: function () { $('#loadMsg').text('') },
      success: function (result) {
        if (!list.isConnected || revision !== that.geoAccessRequest) return
        if (!result.sc) { $('#geoAccessMessage').text(result.msg); return }
        that.geoAccessRules = result.rules.map(function (rule) { return Object.assign({}, rule) })
        that.geoAccessLoaded = true
        $('#geoAccessActive').val(result.active ? 'on' : 'off')
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
    return {version: 3, active: $('#geoAccessActive').val() === 'on', defaultAction: this.geoDefaultAction || 'allow', unknown: $('#geoAccessUnknown').val(), rules: this.geoAccessRules}
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
          var date = new Date(rule.created)
          var reason = (rule.source === 'automatic' ? '自动风控' : '手动封禁') + '：' + (rule.reason || '未填写原因')
          if (rule.source === 'automatic') reason += '\n累计触发 ' + (rule.occurrences == null ? 1 : rule.occurrences) + ' 次'
          if (rule.note) reason += '\n备注：' + rule.note
          var values = [rule.range, rule.region || '位置未知', reason, (rule.active === false ? '已到期 · ' : '') + (rule.expiresAt ? new Date(rule.expiresAt).toLocaleString() : '永久') + '\n添加于 ' + (Number.isFinite(date.getTime()) ? date.toLocaleString() : '—')]
          if (rule.updated) values[3] += '\n更新于 ' + new Date(rule.updated).toLocaleString()
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
        var input = root.querySelector('#' + (key === 'adminLoginPath' ? 'adminLoginPathSetting' : key))
        if (input) input.value = result.settings[key]
      })
      that.settingsLoaded[section] = true
      buttons.forEach(function (button) { button.disabled = false })
    },error:function (xhr) { $('#tipMsg').text(xhr.responseJSON?.msg || '安全配置读取失败，请刷新重试。') }})
  },
  saveSettings: function () {
    var section = window.location.hash.split('/')[1], data = {}
    if (!this.settingsLoaded[section]) return
    var keys = section === 'comments' ? ['commentFilterBuiltin','spam','commentFilterAllow','commentCaptchaEnabled'] : ['riskControlEnabled','wafPower','wafCurrentLimitTimes','wafCurrentLimitSecond','adminLoginPath']
    keys.forEach(function (key) { data[key] = $('#' + (key === 'adminLoginPath' ? 'adminLoginPathSetting' : key)).val() })
    $.ajax({url:Label.servePath + '/console/security/settings',type:'PUT',contentType:'application/json',data:JSON.stringify(data),
      success:function (result) { $('#tipMsg').text(result.msg); if (result.sc && data.adminLoginPath != null) $('#adminLoginPathCurrent').text(data.adminLoginPath.trim() || '/start') },error:function (xhr) { $('#tipMsg').text(xhr.responseJSON?.msg || '安全配置保存失败。') }})
  }
}
admin.register.access = {obj:admin.security,init:function () { this.loadSettings('access') },refresh:function () { this.loadSettings('access') }}
admin.register.ip = {obj:admin.security,init:admin.security.initIpBans,refresh:admin.security.loadIpBans}
admin.register.geo = {obj:admin.security,init:admin.security.loadGeoAccess,refresh:admin.security.loadGeoAccess}
admin.register.comments = {obj:admin.security,init:function () { this.loadSettings('comments') },refresh:function () { this.loadSettings('comments') }}
