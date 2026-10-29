package org.b3log.solo.security;

import io.netty.handler.codec.http.*;
import java.lang.reflect.InvocationTargetException;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.solo.bolo.tool.PBConvert;
import org.b3log.solo.bolo.tool.PBThread;
import org.b3log.solo.processor.CommentProcessor;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ManagementMutationTest {
    @BeforeClass public void configure() throws Exception { LatkeTestSupport.configure(); }

    @Test public void unauthenticatedWritesDoNotReachRepositoriesOrWorkers() {
        for (String path : new String[]{"/article/commentSync", "/article/fishpi/commentSync", "/PBC/run"}) {
            final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path);
            final DefaultFullHttpResponse raw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            try {
                final RequestContext context = new RequestContext(new Request(null, http), new Response(null, raw));
                if (path.equals("/PBC/run")) new PBConvert().runPB(context);
                else if (path.contains("fishpi")) new CommentProcessor().commentSyncFromFishPI(context);
                else new CommentProcessor().commentSync(context);
                Assert.assertEquals(raw.status().code(), 401, path);
                Assert.assertEquals(raw.headers().get("Cache-Control"), "no-store");
            } finally { http.release(); raw.release(); }
        }
    }

    @Test public void imageMigrationRejectsUntrustedDownloadTargetsBeforeNetwork() throws Exception {
        final var download = PBThread.class.getDeclaredMethod("download", String.class);
        download.setAccessible(true);
        for (String url : new String[]{"http://img.hacpai.com/x.png", "https://img.hacpai.com.evil.test/x.png",
                "https://img.hacpai.com@127.0.0.1/x.png", "https://img.hacpai.com:8443/x.png", "file:///tmp/x.png"}) {
            try {
                download.invoke(null, url);
                Assert.fail("Untrusted download accepted: " + url);
            } catch (InvocationTargetException exception) {
                Assert.assertTrue(exception.getCause() instanceof java.io.IOException, url);
            }
        }
    }
}
