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
 * main for admin
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 */

/* main 相关操作 */
admin.main = {
    trendChart: null,
    currentDays: 7,
    _escape: function (v) {
        return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    },
    loadTrends: function (days) {
        var that = this;
        var d = Number(days) === 30 ? 30 : 7;
        this.currentDays = d;
        $('#trendRange7Btn').toggleClass('is-active', d === 7);
        $('#trendRange30Btn').toggleClass('is-active', d === 30);
        $.ajax({
            url: Label.servePath + '/console/stat/trends?days=' + d,
            type: 'GET',
            cache: false,
            success: function (res) {
                if (!res || !res.sc || !res.data) return;
                that.renderTrends(res.data);
            }
        });
    },
    renderTrends: function (data) {
        var that = this;
        $('#voloTrendTotalPv').text(Number(data.totalPv || 0).toLocaleString());
        $('#voloTrendTotalUv').text(Number(data.totalUv || 0).toLocaleString());

        var articles = Array.isArray(data.topArticles) ? data.topArticles : [];
        if (!articles.length) {
            $('#voloTopArticlesList').html('<li class="is-empty">该时间段内暂无文章访问记录</li>');
        } else {
            var aHtml = '';
            for (var i = 0; i < articles.length; i++) {
                var a = articles[i];
                var rawTitle = (typeof Util !== 'undefined' && Util.htmlDecode) ? Util.htmlDecode(a.title || '') : String(a.title || '');
                var permalink = String(a.permalink || '');
                var href = (Label.servePath || '') + (permalink.charAt(0) === '/' ? permalink : ('/' + permalink));
                aHtml += '<li><a href="' + that._escape(href) + '" target="_blank" rel="noopener" title="' + that._escape(rawTitle) + '">' +
                    that._escape(rawTitle || a.articleId) + '</a><span class="console-trend-rank__count">' + Number(a.count || 0) + ' 次</span></li>';
            }
            $('#voloTopArticlesList').html(aHtml);
        }

        var referers = Array.isArray(data.topReferers) ? data.topReferers : [];
        if (!referers.length) {
            $('#voloTopReferersList').html('<li class="is-empty">该时间段内暂无外部引荐域名记录</li>');
        } else {
            var rHtml = '';
            for (var j = 0; j < referers.length; j++) {
                var r = referers[j];
                rHtml += '<li><span class="console-trend-rank__domain">' + that._escape(r.domain || '') + '</span>' +
                    '<span class="console-trend-rank__count">' + Number(r.count || 0) + ' 次</span></li>';
            }
            $('#voloTopReferersList').html(rHtml);
        }

        if (typeof document === 'undefined' || !document.getElementById || typeof Chart === 'undefined') return;
        var canvas = document.getElementById('voloDailyTrendChart');
        if (!canvas || !canvas.getContext) return;
        if (this.trendChart) {
            try { this.trendChart.destroy(); } catch (e) {}
            this.trendChart = null;
        }
        var labels = Array.isArray(data.labels) ? data.labels : [];
        var pvSeries = Array.isArray(data.pvSeries) ? data.pvSeries : [];
        var uvSeries = Array.isArray(data.uvSeries) ? data.uvSeries : [];
        this.trendChart = new Chart(canvas.getContext('2d'), {
            type: 'line',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: '浏览量 (PV)',
                        data: pvSeries,
                        borderColor: '#3f7665',
                        backgroundColor: 'rgba(63, 118, 101, 0.12)',
                        borderWidth: 2,
                        pointRadius: 3,
                        fill: true
                    },
                    {
                        label: '独立会话 (UV)',
                        data: uvSeries,
                        borderColor: '#b7791f',
                        backgroundColor: 'rgba(183, 121, 31, 0.08)',
                        borderDash: [5, 4],
                        borderWidth: 2,
                        pointRadius: 3,
                        fill: true
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                legend: {
                    labels: { boxWidth: 12, padding: 16 }
                },
                scales: {
                    yAxes: [{ ticks: { beginAtZero: true, precision: 0, maxTicksLimit: 6 } }]
                },
                tooltips: { mode: 'index', intersect: false },
                hover: { mode: 'nearest', intersect: true }
            }
        });
        if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function' && typeof Event === 'function') {
            window.dispatchEvent(new Event('bolo:console-charts-ready'));
        }
    }
};

/*
 * 注册到 admin 进行管理 
 */
admin.register.main =  {
    "obj": admin.main,
    "init": function () {
        admin.clearTip();
        admin.register.main.getLog();
        admin.main.loadTrends(admin.main.currentDays || 7);
        clearInterval(this.pollTimer);
        var pollTimer = setInterval(function () { if (document.hidden || (window.location && window.location.hash !== '#main')) { return; } admin.register.main.getLog(); }, 5000);
        this.pollTimer = pollTimer;
        if (window.BoloNavigation) window.BoloNavigation.onLeave(function () { clearInterval(pollTimer); if (admin.main.trendChart) { try { admin.main.trendChart.destroy(); } catch (e) {} admin.main.trendChart = null; } });
    },
    "refresh": function () {
        admin.main.loadTrends(admin.main.currentDays || 7);
        admin.clearTip();
    },
    getLog: () => { if (typeof document !== 'undefined' && document.hidden) { return; } if (typeof window !== 'undefined' && window.location && window.location.hash && window.location.hash !== '#main') { return; } admin.logs.load(true); },
};
