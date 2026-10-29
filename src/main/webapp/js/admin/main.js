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
};

/*
 * 注册到 admin 进行管理 
 */
var lastMain = -1;
admin.register.main =  {
    "obj": admin.main,
    "init": function () {
        admin.clearTip();
        admin.register.main.getLog();
        clearInterval(this.pollTimer);
        var pollTimer = setInterval(admin.register.main.getLog, 5000);
        this.pollTimer = pollTimer;
        if (window.BoloNavigation) window.BoloNavigation.onLeave(function () { clearInterval(pollTimer); });
    },
    "refresh": function () {
        admin.clearTip();
    },
    getLog: () => {
        if (window.location.hash !== '#main') return;
        $.ajax({
            url: Label.servePath + '/admin/logs',
            cache: false,
            timeout: 2000,
            dataType: 'json',
            success: function (result) {
                if (!result || !Array.isArray(result.result)) return;
                const escape = value => $('<span>').text(String(value == null ? '' : value)).html();
                let json = result.result;
                for (let i = 0; i < json.length; i++) {
                    let r = json[i];
                    let rId = r.id;
                    if (rId > lastMain) {
                        let rDate = r.date;
                        let rLevel = r.level;
                        let rSrc = escape(r.name + ':' + r.lineNumber);
                        let msg = escape(r.message);
                        if (r.throwable !== undefined) {
                            msg += '<br>' + escape(r.throwable.class + ': ' + r.throwable.message);
                            for (const line of r.throwable.stackTrace || []) msg += '<br>' + escape(line);
                        }
                        let buildStart = '<tbody class="table-oddRow"><tr class="table-hasExpend">';
                        rDate = rDate.substring(0, 19);
                        const levelClass = ['WARN', 'INFO', 'ERROR'].includes(rLevel) ? rLevel.toLowerCase() : 'other';
                        let build1 = '<td style="width: 40px; vertical-align: top"><span class="console-log-level console-log-level--' + levelClass + '">' + escape(rLevel) + '</span></td>';
                        let build2 = '<td style="width: 135px; vertical-align: top"><span class="console-log-time">' + escape(rDate) + '</span></td>';
                        let build3 = '<td style="vertical-align: top"><span style="word-wrap: break-word; white-space: normal; word-break: break-all"><span class="console-log-source">' + rSrc + '</span><br>' + msg + '</span></td>';
                        let buildEnd = '</tr></tbody>';
                        let res = buildStart + build1 + build2 + build3 + buildEnd;
                        $('#recentLogs').prepend(res);
                        $('#recentLogs').children('tbody').slice(300).remove();
                        lastMain = rId;
                    }
                }
            }
        });
    },
};
