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
package org.b3log.solo.improve;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.bolo.tool.PassSSL;
import org.json.JSONObject;

import org.b3log.latke.http.Request;
import java.io.IOException;

/**
 * 感谢您参与「Bolo 用户体验改进计划」
 * 这里是用户体验改进计划的信息收集类
 */
public class ImproveHelper implements Runnable {

    // 提交反馈服务器地址
    private static final String helperHost = "http://report.stackoverflow.wiki:4399/Log";
    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(2000).setConnectionRequestTimeout(1000).setSocketTimeout(2000).build();

    private final String requestURI;
    private final String maskedClientIP;
    private final String userAgent;
    private final String referer;

    public ImproveHelper(final RequestContext context) {
        final Request request = context == null ? null : context.getRequest();
        if (request == null) {
            this.requestURI = null;
            this.maskedClientIP = null;
            this.userAgent = null;
            this.referer = null;
            return;
        }
        this.requestURI = request.getRequestURI();
        String clientIP = request.getRemoteAddr();
        try {
            if (clientIP != null) {
                final String[] ipSplit = clientIP.split("\\.");
                if (ipSplit.length >= 3) {
                    clientIP = ipSplit[0] + "." + ipSplit[1] + "." + ipSplit[2] + ".*";
                }
            }
        } catch (Exception ignored) {
        }
        this.maskedClientIP = clientIP;
        this.userAgent = request.getHeader("User-Agent");
        this.referer = request.getHeader("Referer");
    }

    @Override
    public void run() {
        if (requestURI != null && "true".equals(ImproveOptions.doJoinHelpImprovePlan())) {
            final JSONObject statisticsObject = new JSONObject();
            statisticsObject.put("category", "statistics");

            final JSONObject statisticsDataObject = new JSONObject();
            /*
              隐私信息说明
              serverTime: 服务器当前时间戳
              serverHost：博客的服务端地址
              requestURL：访问者访问的URL地址
              clientIP：访问者的IP地址，去敏处理
              userAgent: 浏览器UA
              referer: 浏览器Referer
             */
            try {
                statisticsDataObject.put("serverTime", System.currentTimeMillis());
                statisticsDataObject.put("serverHost", Latkes.getStaticServePath());
                statisticsDataObject.put("requestURL", requestURI);
                statisticsDataObject.put("clientIP", maskedClientIP);
                statisticsDataObject.put("userAgent", userAgent);
                statisticsDataObject.put("referer", referer);
            } catch (Exception ignored) {
                return;
            }

            statisticsObject.put("data", statisticsDataObject);

            final HttpPost httpPost = new HttpPost(helperHost);
            httpPost.setConfig(REQUEST_CONFIG);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setEntity(new StringEntity(statisticsObject.toString(), "utf-8"));

            try (CloseableHttpClient client = new PassSSL().createSSLClientDefault();
                 CloseableHttpResponse ignored = client.execute(httpPost)) {
            } catch (IOException ignored) {
            }
        }
    }
}
