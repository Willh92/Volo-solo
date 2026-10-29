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
package org.b3log.solo.bolo.tool;

import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

/**
 * <h3>bolo-solo</h3>
 * <p>将链滴图床的图床上传至当前设定的图床并替换链接</p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-12-19 22:14
 **/
@Singleton
public class PBConvert {

    final private PBThread pbThread = new PBThread();

    @RequestProcessing(value = "/PBC/status", method = {HttpMethod.GET})
    public void getPBStatus(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) {
            context.sendError(401);
            return;
        }

        org.b3log.solo.web.JsonResponses.json(context).renderMsg(pbThread.getStatus());
    }

    @RequestProcessing(value = "/PBC/run", method = HttpMethod.POST)
    public void runPB(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) return;
        if (!pbThread.start()) {
            AdminRequests.reject(context, 409, "转换任务正在运行，请勿重复提交。");
            return;
        }
        context.renderJSON(new JSONObject().put("sc", true).put("msg", "转换任务已启动，请稍后查看状态。"));
    }
}
