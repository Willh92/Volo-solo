/* Volo, derived from Bolo. Copyright (c) 2020-present, bolo-blog. AGPL-3.0-or-later. */
package org.b3log.solo.security;

import io.netty.handler.codec.http.*;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.solo.processor.OAuthProcessor;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.UpgradeService;
import org.b3log.solo.service.UserQueryService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class UpgradeSafetyTest {
    @Test
    public void restartingStudioCannotResetAnExistingAdministrator() throws Exception {
        LatkeTestSupport.configure();
        final boolean previous = UpgradeService.boloFastMigration;
        final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/oauth/bolo/login");
        final DefaultFullHttpResponse raw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            UpgradeService.boloFastMigration = true;
            service(SoloApplication.DATABASE_VERSION, "volo-studio").upgrade();
            Assert.assertFalse(UpgradeService.boloFastMigration);
            http.headers().set("Accept", "application/json");
            final Request request = new Request(null, http) {
                @Override public String getRemoteAddr() { return "192.0.2.203"; }
            };
            request.setParameter("username", "existing-owner");
            request.setParameter("password", "WrongPassword42");
            final OAuthProcessor processor = new OAuthProcessor();
            inject(processor, "initService", new InitService() { @Override public boolean isInited() { return true; } });
            inject(processor, "userQueryService", new UserQueryService() {
                @Override public JSONObject getUserByName(final String name) { return null; }
            });
            processor.adminLogin(new RequestContext(request, new Response(null, raw)));
            Assert.assertEquals(raw.status().code(), 401, "A wrong password after restart must stay a login failure");
        } finally {
            UpgradeService.boloFastMigration = previous;
            http.release();
            raw.release();
        }
    }

    @Test
    public void currentSchemaNeverUsesThemeNamesToEnableMigration() throws Exception {
        final boolean previous = UpgradeService.boloFastMigration;
        try {
            for (final String skin : new String[]{"volo-studio", "bolo-sakura", "my-custom-skin", ""}) {
                UpgradeService.boloFastMigration = true;
                service(SoloApplication.DATABASE_VERSION, skin).upgrade();
                Assert.assertFalse(UpgradeService.boloFastMigration, skin);
            }
        } finally { UpgradeService.boloFastMigration = previous; }
    }

    @Test
    public void olderSoloDatabaseStillOffersLegacyMigration() throws Exception {
        final boolean previous = UpgradeService.boloFastMigration;
        try {
            service("4.3.3", "solo-default").upgrade();
            Assert.assertTrue(UpgradeService.boloFastMigration);
        } finally { UpgradeService.boloFastMigration = previous; }
    }

    @Test(timeOut = 10000)
    public void simultaneousInitializationCannotLogInAnotherAccount() throws Exception {
        LatkeTestSupport.configure();
        final boolean previous = UpgradeService.boloFastMigration;
        final CountDownLatch initializing = new CountDownLatch(1), commit = new CountDownLatch(1);
        final AtomicReference<JSONObject> owner = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final OAuthProcessor processor = new OAuthProcessor();
        inject(processor, "initService", new InitService() {
            @Override public boolean isInited() { return owner.get() != null; }
            @Override public synchronized void init(final JSONObject input) {
                if (isInited()) return;
                initializing.countDown();
                try {
                    if (!commit.await(5, TimeUnit.SECONDS)) throw new AssertionError("Initialization did not finish");
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                owner.set(new JSONObject(input.toString()).put(Keys.OBJECT_ID, "owner")
                        .put(User.USER_ROLE, Role.ADMIN_ROLE));
            }
        });
        inject(processor, "userQueryService", new UserQueryService() {
            @Override public JSONObject getAdmin() { return owner.get(); }
            @Override public JSONObject getUserByName(final String name) {
                final JSONObject admin = owner.get();
                return admin != null && admin.getString(User.USER_NAME).equals(name) ? admin : null;
            }
        });
        final int[] status = new int[2];
        final Thread first = loginThread(processor, "new-owner", 204, status, 0, failure);
        final Thread second = loginThread(processor, "another-account", 205, status, 1, failure);
        try {
            UpgradeService.boloFastMigration = false;
            first.start();
            Assert.assertTrue(initializing.await(3, TimeUnit.SECONDS));
            second.start();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
            Assert.assertEquals(second.getState(), Thread.State.BLOCKED);
            commit.countDown();
            first.join(3000);
            second.join(3000);
            Assert.assertNull(failure.get());
            Assert.assertFalse(first.isAlive());
            Assert.assertFalse(second.isAlive());
            Assert.assertEquals(status[0], 200);
            Assert.assertEquals(status[1], 401, "The second request must validate the newly created account");
            Assert.assertEquals(owner.get().getString(User.USER_NAME), "new-owner");
        } finally {
            commit.countDown();
            first.join(1000);
            second.join(1000);
            UpgradeService.boloFastMigration = previous;
        }
    }

    private static Thread loginThread(final OAuthProcessor processor, final String username, final int address,
                                     final int[] status, final int index, final AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/oauth/bolo/login");
            final DefaultFullHttpResponse raw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            try {
                http.headers().set("Accept", "application/json");
                final Request request = new Request(null, http) {
                    @Override public String getRemoteAddr() { return "192.0.2." + address; }
                };
                request.setParameter("username", username);
                request.setParameter("password", "SetupPassword42");
                processor.adminLogin(new RequestContext(request, new Response(null, raw)));
                status[index] = raw.status().code();
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { http.release(); raw.release(); }
        }, "setup-login-" + index);
    }

    private static UpgradeService service(final String version, final String skin) throws Exception {
        final UpgradeService service = new UpgradeService() {
            @Override protected void upgradeDateSchema() { /* Database migration has separate JDBC tests. */ }
        };
        inject(service, "optionQueryService", new OptionQueryService() {
            @Override public JSONObject getPreference() { return new JSONObject().put(Option.ID_C_VERSION, version); }
            @Override public JSONObject getSkin() {
                return skin.isEmpty() ? null : new JSONObject().put(Option.ID_C_SKIN_DIR_NAME, skin)
                        .put(Option.ID_C_MOBILE_SKIN_DIR_NAME, skin);
            }
        });
        return service;
    }

    private static void inject(final Object target, final String name, final Object value) throws Exception {
        final Field field = (target instanceof UpgradeService ? UpgradeService.class : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
