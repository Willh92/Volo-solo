/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.processor;

import java.io.IOException;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.KanbanModels;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.JsonResponses;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

/**
 * Local Live2D model selection using the bundled manifest.
 * @author Liang Ding (Solo Author)
 * @author adlered (Bolo Author)
 */
@Singleton
public class KanBanNiangProcessor {
    @Inject
    private OptionRepository optionRepository;

    @RequestProcessing(value = "/plugins/kanbanniang/assets/model", method = HttpMethod.GET)
    public void randomModel(final RequestContext context) {
        String selected = "";
        try {
            final JSONObject option = optionRepository.get(Option.ID_C_KANBANNIANG_SELECTOR);
            if (option != null) selected = option.optString(Option.OPTION_VALUE);
        } catch (RepositoryException ignored) {
            // Missing preferences must not prevent the bundled default from loading.
        }
        renderModel(context, selected);
    }

    @RequestProcessing(value = "/plugins/kanbanniang/assets/absoluteRandomModel", method = HttpMethod.GET)
    public void absolutelyRandomModel(final RequestContext context) {
        renderModel(context, "");
    }

    private void renderModel(final RequestContext context, final String selected) {
        context.setHeader("Cache-Control", "no-store");
        try {
            context.renderJSON(new KanbanModels(SoloApplication.getWebResources()).select(selected));
        } catch (IOException | org.json.JSONException exception) {
            context.setStatus(503);
            context.renderJSON(new JSONObject().put("sc", false).put("msg", "Bundled model resources are unavailable"));
        }
    }

    @RequestProcessing(value = "/plugins/kanbanniang/assets/list", method = HttpMethod.GET)
    public void kanbanniangList(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) {
            context.setStatus(401);
            JsonResponses.json(context).renderMsg("Unauthorized");
            return;
        }
        try {
            context.renderJSON(new JSONObject().put("sc", true)
                    .put("msg", String.join(";", new KanbanModels(SoloApplication.getWebResources()).names())));
        } catch (IOException | org.json.JSONException exception) {
            context.setStatus(503);
            JsonResponses.json(context).renderMsg("Bundled model resources are unavailable");
        }
    }
}
