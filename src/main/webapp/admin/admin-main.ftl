<#--

    Bolo - A stable and beautiful blogging system based in Solo.
    Copyright (c) 2020-present, https://github.com/bolo-blog

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

-->
<div id="notice">
    <aside class="console-onboarding" hidden role="status"><div><strong>欢迎使用 <span class="console-onboarding-version">Volo</span></strong><p>开始写作，或在站点设置中调整博客外观。</p></div><button type="button">不再提醒</button></aside>
    <script>
        $(function () {
            var notice = document.querySelector('.console-onboarding');
            if (!notice || $.cookie('neverShowVer') === boloVersion) return;
            notice.querySelector('.console-onboarding-version').textContent = 'Volo ' + boloVersion;
            notice.hidden = false;
            notice.querySelector('button').addEventListener('click', function () {
                $.cookie('neverShowVer', boloVersion, {expires: 222, path: '/'});
                notice.hidden = true;
            });
        });
    </script>
    <section class="console-welcome"><small>YOUR WRITING SPACE</small><h2>让每一次表达，都有归处。</h2><p>整理想法，记录生活，继续你的创作。</p><div class="console-shortcuts"><a href="#article/article" onclick="admin.article.prePost()">＋ 写一篇文章</a><a href="#article/article-list">管理文章</a><a href="#tools/preference">站点设置</a></div></section>
    <div class="module-panel">
        <div class="module-header">
            <h2>数据</h2>
        </div>
        <div class="module-body padding12 console-chart-grid">
            <div style="width: 32%; display: inline-table;">
                <canvas id="articleCountChart"></canvas>
                <script type="text/javascript">
                    Chart.defaults.global.animation.duration = 2000;

                    var options = {
                        responsive: true,
                        maintainAspectRatio: false,
                        scales: {yAxes: [{ticks: {beginAtZero: true, precision: 0, maxTicksLimit: 6}}]},
                        title: {
                            display: true,
                            text: '每月文章数量统计'
                        },
                        tooltips: {
                            mode: 'index',
                            intersect: false,
                        },
                        hover: {
                            mode: 'nearest',
                            intersect: true
                        }
                    };
                    var ctx = document.getElementById("articleCountChart").getContext("2d");
                    var categoryCountChart = new Chart(ctx, {
                        type: 'line',
                        data: data1,
                        options: options
                    });
                </script>
            </div>

            <div style="width: 32%; display: inline-table;">
                <canvas id="categoryCountChart"></canvas>
                <script type="text/javascript">
                    var data3 = {
                        datasets: [
                            {
                                label: [
                                    "文章数量"
                                ],
                                data: [
                                    <#list categories as category>
                                    <#if category.categoryPublishedArticleCount != 0>
                                    ${category.categoryPublishedArticleCount},
                                    </#if>
                                    </#list>
                                ],
                                backgroundColor: '#638e7c',
                            }
                        ],

                        labels: [
                            <#list categories as category>
                                <#if category.categoryPublishedArticleCount != 0>
                                    "${category.categoryTitle?js_string}",
                                </#if>
                            </#list>
                        ]
                    };
                    var options = {
                        responsive: true,
                        maintainAspectRatio: false,
                        scales: {yAxes: [{ticks: {beginAtZero: true, precision: 0, maxTicksLimit: 6}}]},
                        title: {
                            display: true,
                            text: '分类使用率'
                        },
                        legend: {
                            display: false
                        }
                    };
                    var ctx = document.getElementById("categoryCountChart").getContext("2d");
                    if (data3.labels.length) new Chart(ctx, {
                        type: 'bar',
                        data: data3,
                        options: options
                    });
                    else {
                        var empty = document.createElement('p');
                        empty.className = 'console-chart-empty';
                        empty.innerHTML = '分类使用率<span>暂无分类数据</span>';
                        ctx.canvas.replaceWith(empty);
                    }
                </script>
            </div>

            <div style="width: 32%; display: inline-table;">
                <canvas id="tagsTop5Chart"></canvas>
                <script type="text/javascript">
                    var options = {
                        responsive: true,
                        maintainAspectRatio: false,
                        title: {
                            display: true,
                            text: '标签 Top5'
                        }
                    };
                    var ctx = document.getElementById("tagsTop5Chart").getContext("2d");
                    var tagsTop5Chart = new Chart(ctx, {
                        type: 'doughnut',
                        data: data2,
                        options: options
                    });
                </script>
            </div>
        </div>
    </div>
    <div class="module-panel">
        <div class="module-header">
            <h2>公告</h2>
        </div>
        <div class="module-body padding12">
            <div id="noticeList" style="background: none;">
                <a href="https://github.com/Willh92/Volo-solo/releases" target="_blank" rel="noopener noreferrer">查看 Volo 更新</a>
            </div>
        </div>
    </div>
    <div class="module-panel">
        <div class="module-header">
            <h2>近期日志</h2>
        </div>
        <div class="module-body padding12">
            <div id="recentLogs"></div>
        </div>
    </div>
</div>
<script>window.dispatchEvent(new Event('bolo:console-charts-ready'));</script>
