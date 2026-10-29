/*
 * Volo - A blogging system forked from Bolo.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.b3log.latke.Keys;
import org.b3log.solo.model.Comment;
import org.json.JSONObject;

/** Reply traversal stays within one article, including when legacy data contains cycles. */
public final class CommentThreads {
    private CommentThreads() { }

    /** Resolve reply labels from real parents, never from a client-supplied cached name. */
    public static List<JSONObject> forDisplay(final List<JSONObject> comments) {
        final List<JSONObject> copies = new ArrayList<>();
        final Map<String, JSONObject> byId = new HashMap<>();
        for (final JSONObject stored : comments) {
            final JSONObject copy = new JSONObject();
            for (final String key : stored.keySet()) copy.put(key, stored.get(key));
            CommentNames.apply(copy);
            copies.add(copy);
            byId.put(copy.optString(Keys.OBJECT_ID), copy);
        }
        final List<JSONObject> ordered = twoLevels(copies);
        for (final JSONObject comment : ordered) {
            final String parentId = comment.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID);
            final JSONObject parent = byId.get(parentId);
            final boolean reply = comment.optInt("commentDepth") == 1 && parent != null
                    && !parentId.equals(comment.optString(Keys.OBJECT_ID))
                    && comment.optString(Comment.COMMENT_ON_ID).equals(parent.optString(Comment.COMMENT_ON_ID));
            comment.put("isReply", reply);
            comment.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, reply ? parent.optString(Comment.COMMENT_NAME) : "");
            if (!reply) comment.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, "");
        }
        return ordered;
    }

    /** Flat DOM order with exactly two visual levels; actual parent IDs stay intact. */
    public static List<JSONObject> twoLevels(final List<JSONObject> comments) {
        final Map<String, JSONObject> byId = new HashMap<>();
        final Map<String, String> roots = new HashMap<>();
        for (final JSONObject comment : comments) byId.put(comment.optString(Keys.OBJECT_ID), comment);
        for (final JSONObject comment : comments) {
            String current = comment.optString(Keys.OBJECT_ID);
            final List<String> path = new ArrayList<>();
            final Map<String, Integer> visited = new HashMap<>();
            String root;
            while (true) {
                if (roots.containsKey(current)) { root = roots.get(current); break; }
                final int cycle = visited.getOrDefault(current, -1);
                if (cycle >= 0) { root = path.subList(cycle, path.size()).stream().min(String::compareTo).orElse(current); break; }
                visited.put(current, path.size());
                path.add(current);
                final JSONObject node = byId.get(current);
                final String parentId = node.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID);
                final JSONObject parent = byId.get(parentId);
                if (parent == null || !node.optString(Comment.COMMENT_ON_ID).equals(parent.optString(Comment.COMMENT_ON_ID))) {
                    root = current; break;
                }
                current = parentId;
            }
            for (final String id : path) roots.put(id, root);
        }
        final Comparator<JSONObject> chronological = Comparator.comparingLong((JSONObject c) -> c.optLong(Comment.COMMENT_CREATED))
                .thenComparing(c -> c.optString(Keys.OBJECT_ID));
        final List<JSONObject> result = new ArrayList<>();
        final List<JSONObject> heads = new ArrayList<>();
        final Map<String, List<JSONObject>> replies = new HashMap<>();
        for (final JSONObject comment : comments) {
            final String id = comment.optString(Keys.OBJECT_ID), root = roots.get(id);
            comment.put("commentThreadId", root).put("commentDepth", root.equals(id) ? 0 : 1);
            if (root.equals(id)) heads.add(comment);
            else replies.computeIfAbsent(root, ignored -> new ArrayList<>()).add(comment);
        }
        heads.sort(chronological.reversed());
        for (final JSONObject head : heads) {
            result.add(head);
            final List<JSONObject> children = replies.getOrDefault(head.optString(Keys.OBJECT_ID), new ArrayList<>());
            children.sort(chronological);
            result.addAll(children);
        }
        return result;
    }
    public static Set<String> descendants(final List<JSONObject> comments, final String articleId, final String rootId) {
        final Map<String, List<String>> children = new HashMap<>();
        final Set<String> present = new LinkedHashSet<>();
        for (final JSONObject comment : comments) {
            if (!articleId.equals(comment.optString(Comment.COMMENT_ON_ID))) continue;
            final String id = comment.optString(Keys.OBJECT_ID);
            if (id.isBlank()) continue;
            present.add(id);
            children.computeIfAbsent(comment.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID), ignored -> new ArrayList<>()).add(id);
        }
        final Set<String> removed = new LinkedHashSet<>();
        if (!present.contains(rootId)) return removed;
        final ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(rootId);
        while (!pending.isEmpty()) {
            final String id = pending.removeFirst();
            if (removed.add(id)) pending.addAll(children.getOrDefault(id, List.of()));
        }
        return removed;
    }
    public static String removeMailBindings(final String context, final Set<String> removed) {
        final List<String> retained = new ArrayList<>();
        for (final String entry : context.split(";")) {
            if (!entry.isEmpty() && !removed.contains(entry.split(":", 2)[0])) retained.add(entry);
        }
        return String.join(";", retained);
    }
}
