/* SPDX-License-Identifier: AGPL-3.0-or-later */
admin.githubRepositories = {
  loaded: false,
  dirty: false,
  page: 1,
  init: function () {
    if (!document.getElementById('githubRepositoryList')) return
    this.loaded = false
    this.dirty = false
    $('#githubIncludeForks').off('change.githubRepos').on('change.githubRepos', function () {
      admin.githubRepositories.dirty = true
      admin.githubRepositories.render()
    })
    $('#githubRepositoryQuery').off('input.githubRepos').on('input.githubRepos', function () {
      admin.githubRepositories.page = 1
      admin.githubRepositories.render()
    })
    this.load()
  },
  message: function (text) { $('#githubRepositoryMessage').text(text) },
  load: function () {
    var root = document.getElementById('githubRepositoryList')
    if (!root) return
    var revision = this.revision = (this.revision || 0) + 1
    this.loaded = false
    this.message('正在读取已同步的仓库…')
    $('#githubIncludeForks, #githubRepoRefresh').prop('disabled', true)
    $.ajax({url: Label.servePath + '/console/github-repositories', cache: false, timeout: 15000,
      success: function (data) {
        if (admin.githubRepositories.revision !== revision || document.getElementById('githubRepositoryList') !== root) return
        if (!data.sc) { admin.githubRepositories.message(data.msg || '读取仓库失败，请重试。'); return }
        admin.githubRepositories.state = data
        admin.githubRepositories.loaded = true
        admin.githubRepositories.dirty = false
        admin.githubRepositories.page = 1
        $('#githubIncludeForks').prop('checked', !!data.includeForks).prop('disabled', false)
        admin.githubRepositories.message(data.synced ? (data.updatedAt ? '上次同步：' + new Date(data.updatedAt).toLocaleString() : '这是旧版同步数据，立即同步可补全 Fork 仓库。') : '还没有此账号的仓库快照。保存 GitHub ID 后，点击“立即同步仓库”。')
        admin.githubRepositories.render()
      },
      error: function () { if (admin.githubRepositories.revision === revision) admin.githubRepositories.message('读取仓库失败，可点击“重新载入”重试；已有显示设置不会被清空。') },
      complete: function () { if (admin.githubRepositories.revision === revision && document.getElementById('githubRepositoryList') === root) $('#githubRepoRefresh').prop('disabled', false) }
    })
  },
  refresh: function (button) {
    var username = $('#myGitHubID').val().trim()
    if (!this.loaded || !this.state || username.toLowerCase() !== this.state.username.toLowerCase() || this.dirty) {
      this.message('请先点击下方“更新”保存 GitHub ID 和显示设置，再同步仓库。')
      return
    }
    if (!/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/.test(username)) { this.message('请填写有效的 GitHub ID。'); return }
    button.disabled = true
    this.message('正在从 GitHub 同步公开仓库，现有页面会保留到同步成功。')
    $.ajax({url: Label.servePath + '/sync/github/repo/do/get', type: 'POST', data: {githubId: username}, timeout: 90000,
      success: function (data) { if (data.sc) admin.githubRepositories.load(); else admin.githubRepositories.message(data.msg || '同步失败，原有数据已保留。') },
      error: function (xhr) { admin.githubRepositories.message(xhr.responseJSON?.msg || '同步失败，原有数据已保留，请检查网络后重试。') },
      complete: function () { button.disabled = false }
    })
  },
  payload: function () {
    if (!this.loaded || !this.state) return null
    var username = $('#myGitHubID').val().trim()
    if (!/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/.test(username)) return null
    return {username: username, includeForks: $('#githubIncludeForks').prop('checked'),
      hidden: username.toLowerCase() === this.state.username.toLowerCase() ? this.state.repositories.filter(function (repo) { return !repo.selected }).map(function (repo) { return repo.fullName }) : []}
  },
  updateSummary: function () {
    if (!this.state) return
    var includeForks = $('#githubIncludeForks').prop('checked')
    var displayed = this.state.repositories.filter(function (repo) { return repo.selected && (includeForks || !repo.fork) })
    $('#githubRepositorySummary').text(displayed.length + ' 个展示项目 · ' + displayed.reduce(function (sum, repo) { return sum + repo.stars }, 0) + ' Stars · ' + displayed.reduce(function (sum, repo) { return sum + repo.forks }, 0) + ' Forks')
  },
  render: function () {
    var state = this.state, list = document.getElementById('githubRepositoryList'), pagination = document.getElementById('githubRepositoryPagination')
    if (!state || !list || !pagination) return
    var includeForks = $('#githubIncludeForks').prop('checked')
    this.updateSummary()
    var keyword = $('#githubRepositoryQuery').val().trim().toLowerCase()
    var matches = state.repositories.filter(function (repo) { return (repo.fullName + ' ' + repo.description).toLowerCase().includes(keyword) })
    var pages = Math.max(1, Math.ceil(matches.length / 10))
    this.page = Math.min(this.page, pages)
    list.replaceChildren()
    function element (tag, text, className) { var node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node }
    if (!matches.length) {
      list.appendChild(element('p', state.repositories.length ? '没有匹配的仓库。' : '尚无仓库数据。', 'console-github-empty'))
    } else {
      var table = element('table', null, 'console-github-table'), head = element('thead'), header = element('tr'), body = element('tbody')
      ;['展示', '仓库', '类型', 'Stars', 'Forks'].forEach(function (text) { header.appendChild(element('th', text)) })
      head.appendChild(header); table.appendChild(head)
      matches.slice((this.page - 1) * 10, this.page * 10).forEach(function (repo) {
        var row = element('tr'), selection = element('td'), input = element('input'), info = element('td'), type = element('td', repo.fork ? 'Fork' : '原创')
        input.type = 'checkbox'; input.checked = !!repo.selected; input.disabled = repo.fork && !includeForks
        input.setAttribute('aria-label', '展示仓库 ' + repo.name)
        input.addEventListener('change', function () { repo.selected = input.checked; row.classList.toggle('console-github-excluded', !input.checked || input.disabled); admin.githubRepositories.dirty = true; admin.githubRepositories.updateSummary() })
        selection.appendChild(input)
        var link = element('a', repo.name); link.href = 'https://github.com/' + repo.fullName; link.target = '_blank'; link.rel = 'noopener noreferrer'
        info.appendChild(link); info.appendChild(element('p', repo.description || '暂无简介', 'console-github-description'))
        ;[selection, info, type, element('td', repo.stars), element('td', repo.forks)].forEach(function (cell, index) {
          cell.dataset.label = ['展示', '仓库', '类型', 'Stars', 'Forks'][index]; row.appendChild(cell)
        })
        if (input.disabled || !repo.selected) row.classList.add('console-github-excluded')
        body.appendChild(row)
      })
      table.appendChild(body); list.appendChild(table)
    }
    admin.pagination.render(pagination, this.page, pages, function (page) { admin.githubRepositories.page = page; admin.githubRepositories.render() })
  }
}
