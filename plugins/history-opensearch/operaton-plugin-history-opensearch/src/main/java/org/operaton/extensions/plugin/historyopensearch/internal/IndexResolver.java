/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides in which time-based index a history document lives, so that every event of one entity
 * updates the same document even when the entity spans several index periods.
 *
 * <p>History events are deltas: an {@code end} event does not necessarily say when the entity started.
 * The index of a document is therefore determined once, when the document is first seen, and found again
 * for later events by (1) a cache of recent assignments, (2) the start or creation time the event carries
 * (it equals the one used when the document was created), (3) a search for the document id over all
 * indices of the category, and (4) a real-time {@code _mget} in the newest indices, which closes the window
 * before a freshly written document becomes searchable. Only an entity that cannot be found anywhere is
 * placed by the current time. Thread-safe.
 */
final class IndexResolver {

    /** Documents written this recently may not be searchable yet; they are looked up with a real-time get. */
    private static final long REFRESH_WINDOW_SECONDS = 10;

    private static final int SEARCH_CHUNK = 500;

    private final OpenSearchClient client;
    private final ObjectMapper json;
    private final IndexNames names;
    private final Retrier retrier;
    private final Map<String, String> cache;

    IndexResolver(OpenSearchClient client, ObjectMapper json, IndexNames names, Retrier retrier, int cacheSize) {
        this.client = client;
        this.json = json;
        this.names = names;
        this.retrier = retrier;
        this.cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cacheSize;
            }
        });
    }

    /** The target index of every document, in order. Lookups that fail after the retries throw a {@link RestException}. */
    List<String> resolve(List<HistoryDocument> documents, Instant now) {
        if (!names.timeBased()) {
            return documents.stream().map(d -> names.base(d.category())).toList();
        }
        // Pass 1: what is known from the cache, from earlier documents of the batch or from the start time?
        Map<String, String> assigned = new LinkedHashMap<>();
        Map<String, Set<String>> unknown = new LinkedHashMap<>();
        for (HistoryDocument document : documents) {
            String key = key(document);
            if (assigned.containsKey(key) || isUnknown(unknown, document)) {
                continue;
            }
            String cached = cache.get(key);
            if (cached != null) {
                assigned.put(key, cached);
            } else if (document.anchor() != null) {
                assigned.put(key, names.at(document.category(), document.anchor()));
            } else {
                unknown.computeIfAbsent(document.category(), c -> new LinkedHashSet<>())
                        .add(document.id());
            }
        }
        // Pass 2: look the rest up in the cluster; what is not there yet begins now.
        Map<String, String> found = lookup(unknown, now);
        for (Map.Entry<String, Set<String>> entry : unknown.entrySet()) {
            for (String id : entry.getValue()) {
                String key = entry.getKey() + "|" + id;
                assigned.put(key, found.getOrDefault(key, names.at(entry.getKey(), now)));
            }
        }
        // Pass 3: remember and answer.
        List<String> result = new ArrayList<>(documents.size());
        for (HistoryDocument document : documents) {
            String key = key(document);
            String index = assigned.get(key);
            cache.put(key, index);
            result.add(index);
        }
        return result;
    }

    private static boolean isUnknown(Map<String, Set<String>> unknown, HistoryDocument document) {
        Set<String> ids = unknown.get(document.category());
        return ids != null && ids.contains(document.id());
    }

    private static String key(HistoryDocument document) {
        return document.category() + "|" + document.id();
    }

    private Map<String, String> lookup(Map<String, Set<String>> unknown, Instant now) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : unknown.entrySet()) {
            List<String> ids = new ArrayList<>(entry.getValue());
            for (int from = 0; from < ids.size(); from += SEARCH_CHUNK) {
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + SEARCH_CHUNK));
                search(entry.getKey(), chunk, found);
            }
            List<String> missing = ids.stream()
                    .filter(id -> !found.containsKey(entry.getKey() + "|" + id))
                    .toList();
            if (!missing.isEmpty()) {
                realtimeGet(entry.getKey(), missing, now, found);
            }
        }
        return found;
    }

    private void search(String category, List<String> ids, Map<String, String> found) {
        ObjectNode request = json.createObjectNode();
        request.put("size", ids.size() * 2);
        request.put("_source", false);
        ArrayNode values = request.putObject("query").putObject("ids").putArray("values");
        ids.forEach(values::add);
        // newest index first: should an id exist twice (never written by this plugin), the newest copy wins
        request.putArray("sort").addObject().put("_index", "desc");
        JsonNode response = retrier.call(
                "looking up existing " + category + " documents",
                () -> client.execute(
                        "POST",
                        "/" + names.searchPattern(category)
                                + "/_search?ignore_unavailable=true&allow_no_indices=true"
                                + "&filter_path=hits.hits._id,hits.hits._index",
                        write(request),
                        "application/json"));
        for (JsonNode hit : response.path("hits").path("hits")) {
            found.putIfAbsent(
                    category + "|" + hit.path("_id").asText(),
                    hit.path("_index").asText());
        }
    }

    private void realtimeGet(String category, List<String> ids, Instant now, Map<String, String> found) {
        Set<String> candidates = new TreeSet<>();
        candidates.add(names.at(category, now));
        candidates.add(names.at(category, now.minusSeconds(REFRESH_WINDOW_SECONDS)));
        ObjectNode request = json.createObjectNode();
        ArrayNode docs = request.putArray("docs");
        for (String index : candidates) {
            for (String id : ids) {
                docs.addObject().put("_index", index).put("_id", id);
            }
        }
        JsonNode response = retrier.call(
                "looking up recent " + category + " documents",
                () -> client.execute(
                        "POST",
                        "/_mget?_source=false&filter_path=docs._index,docs._id,docs.found",
                        write(request),
                        "application/json"));
        for (JsonNode doc : response.path("docs")) {
            if (doc.path("found").asBoolean(false)) {
                found.putIfAbsent(
                        category + "|" + doc.path("_id").asText(),
                        doc.path("_index").asText());
            }
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchException(
                    "Cannot serialize a request to OpenSearch", e);
        }
    }
}
