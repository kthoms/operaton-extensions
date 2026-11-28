/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A SCIM 2.0 server in the test JVM: {@code /Users} and {@code /Groups} with filters, paging and
 * sorting, {@code /oauth/token} with the client credentials and password grants, request recording,
 * and switches for the behaviours the plugin has to cope with (page size caps, expired tokens, slow
 * answers, failures). It evaluates real SCIM filters, so a malformed filter fails the request.
 */
public final class FakeScimServer implements AutoCloseable {

    /** One request the server received. */
    public record Recorded(String method, String path, String rawQuery, Map<String, String> headers) {

        /** The decoded value of a query parameter, or {@code null}. */
        public String param(String name) {
            if (rawQuery == null) {
                return null;
            }
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    private static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, ObjectNode> users = new LinkedHashMap<>();
    private final Map<String, ObjectNode> groups = new LinkedHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> passwords = new ConcurrentHashMap<>();
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokensIssued = new AtomicInteger();

    private volatile Map<String, String> lastTokenForm = Map.of();
    private volatile int tokenFailStatus;
    private volatile String tokenFailBody = "";
    private volatile int maxPageSize = Integer.MAX_VALUE;
    private volatile String requiredAuthorization;
    private volatile boolean oauthRequired;
    private volatile String clientId = "operaton";
    private volatile String clientSecret = "secret";
    private volatile long expiresIn = 3600;
    private volatile boolean memberFilterSupported = true;
    private volatile CountDownLatch gate;
    private volatile int failStatus;
    private volatile String failBody = "";

    private FakeScimServer(HttpServer server) {
        this.server = server;
    }

    /** Starts a server on a free port of the loopback interface. */
    public static FakeScimServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeScimServer fake = new FakeScimServer(http);
            http.createContext("/", fake::handle);
            http.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "fake-scim");
                thread.setDaemon(true);
                return thread;
            }));
            http.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the fake SCIM server", e);
        }
    }

    /** The base URL, without a trailing slash. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The URL of the token endpoint. */
    public String tokenUrl() {
        return baseUrl() + "/oauth/token";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- data

    /** Adds a user; the resource has the usual SCIM shape. */
    public synchronized FakeScimServer user(
            String scimId, String userName, String givenName, String familyName, String email) {
        ObjectNode user = MAPPER.createObjectNode();
        user.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:User");
        user.put("id", scimId);
        user.put("userName", userName);
        user.put("active", true);
        ObjectNode name = user.putObject("name");
        name.put("givenName", givenName);
        name.put("familyName", familyName);
        ArrayNode emails = user.putArray("emails");
        emails.addObject().put("type", "home").put("value", "private-" + email);
        ObjectNode work = emails.addObject();
        work.put("type", "work");
        work.put("value", email);
        work.put("primary", true);
        users.put(scimId, user);
        return this;
    }

    /** Sets the {@code active} flag of a user. */
    public synchronized FakeScimServer active(String scimId, boolean active) {
        users.get(scimId).put("active", active);
        return this;
    }

    /** Adds a group with the given members (SCIM ids of users or groups). */
    public synchronized FakeScimServer group(String scimId, String displayName, String... memberScimIds) {
        ObjectNode group = MAPPER.createObjectNode();
        group.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:Group");
        group.put("id", scimId);
        group.put("displayName", displayName);
        ArrayNode members = group.putArray("members");
        for (String member : memberScimIds) {
            ObjectNode entry = members.addObject();
            entry.put("value", member);
            entry.put("type", users.containsKey(member) ? "User" : "Group");
        }
        groups.put(scimId, group);
        return this;
    }

    /** Adds the read-only {@code groups} claim to every user, computed from the group members. */
    public synchronized FakeScimServer withUserGroupsClaim() {
        for (ObjectNode user : users.values()) {
            ArrayNode claim = user.putArray("groups");
            for (ObjectNode group : groups.values()) {
                for (JsonNode member : group.get("members")) {
                    if (member.get("value").asText().equals(user.get("id").asText())) {
                        ObjectNode ref = claim.addObject();
                        ref.put("value", group.get("id").asText());
                        ref.put("display", group.get("displayName").asText());
                        ref.put("type", "direct");
                    }
                }
            }
        }
        return this;
    }

    /** Registers a password for the password grant of the token endpoint. */
    public FakeScimServer password(String userName, String password) {
        passwords.put(userName, password);
        return this;
    }

    // ---- behaviour

    /** Caps {@code count}, like servers that return fewer resources than asked for. */
    public FakeScimServer maxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
        return this;
    }

    /** Requires exactly this {@code Authorization} header value on {@code /Users} and {@code /Groups}. */
    public FakeScimServer requireAuthorization(String value) {
        this.requiredAuthorization = value;
        return this;
    }

    /** Requires a bearer token issued by the token endpoint. */
    public FakeScimServer requireOAuthToken(String clientId, String clientSecret, long expiresInSeconds) {
        this.oauthRequired = true;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.expiresIn = expiresInSeconds;
        return this;
    }

    /** Declares whether the server can filter groups by {@code members[value eq ..]}; else it answers 400. */
    public FakeScimServer memberFilterSupported(boolean supported) {
        this.memberFilterSupported = supported;
        return this;
    }

    /** Invalidates every issued token, like a server that expired them early. */
    public void revokeTokens() {
        validTokens.clear();
    }

    /** Answers every request to the token endpoint with this status and body. */
    public void failTokenEndpoint(int status, String body) {
        this.tokenFailBody = body;
        this.tokenFailStatus = status;
    }

    /** The form fields of the latest request to the token endpoint. */
    public Map<String, String> lastTokenForm() {
        return lastTokenForm;
    }

    /** The number of client credentials access tokens issued so far (password grants are not counted). */
    public int tokensIssued() {
        return tokensIssued.get();
    }

    /** Holds every SCIM answer until the latch is released. */
    public void holdAnswers(CountDownLatch latch) {
        this.gate = latch;
    }

    /** Answers every SCIM request with this status and body until {@link #stopFailing()}. */
    public void failWith(int status, String body) {
        this.failBody = body;
        this.failStatus = status;
    }

    /** Ends {@link #failWith}. */
    public void stopFailing() {
        this.failStatus = 0;
    }

    /** All requests so far. */
    public List<Recorded> requests() {
        return Collections.unmodifiableList(requests);
    }

    /** The SCIM requests (not the token endpoint) whose path starts with the prefix. */
    public List<Recorded> requestsTo(String pathPrefix) {
        return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    /** Forgets the recorded requests. */
    public void clearRequests() {
        requests.clear();
    }

    // ---- HTTP

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        String path = exchange.getRequestURI().getPath();
        requests.add(new Recorded(
                exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(), headers));
        try {
            if (path.equals("/oauth/token")) {
                token(exchange);
                return;
            }
            CountDownLatch latch = gate;
            if (latch != null) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (failStatus != 0) {
                send(exchange, failStatus, failBody);
                return;
            }
            if (!authorized(headers.get("authorization"))) {
                send(exchange, 401, error(401, null, "Unauthorized").toString());
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, error(405, null, "Only GET is supported").toString());
                return;
            }
            if (path.equals("/Users") || path.equals("/Groups")) {
                list(exchange, path.equals("/Users") ? users : groups);
            } else if (path.startsWith("/Users/") || path.startsWith("/Groups/")) {
                Map<String, ObjectNode> store = path.startsWith("/Users/") ? users : groups;
                String id = URLDecoder.decode(path.substring(path.indexOf('/', 1) + 1), StandardCharsets.UTF_8);
                ObjectNode resource;
                synchronized (this) {
                    resource = store.get(id);
                }
                if (resource == null) {
                    send(
                            exchange,
                            404,
                            error(404, null, "Resource " + id + " not found").toString());
                } else {
                    send(exchange, 200, resource.toString());
                }
            } else {
                send(exchange, 404, error(404, null, "No such endpoint " + path).toString());
            }
        } catch (RuntimeException e) {
            send(exchange, 500, error(500, null, e.toString()).toString());
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(String header) {
        if (oauthRequired) {
            return header != null && header.startsWith("Bearer ") && validTokens.contains(header.substring(7));
        }
        return requiredAuthorization == null || requiredAuthorization.equals(header);
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        lastTokenForm = Map.copyOf(form);
        if (tokenFailStatus != 0) {
            send(exchange, tokenFailStatus, tokenFailBody);
            return;
        }
        String grant = form.get("grant_type");
        if ("client_credentials".equals(grant)) {
            if (!clientId.equals(form.get("client_id")) || !clientSecret.equals(form.get("client_secret"))) {
                send(exchange, 401, "{\"error\":\"invalid_client\",\"error_description\":\"bad client\"}");
                return;
            }
        } else if ("password".equals(grant)) {
            String expected = passwords.get(form.get("username"));
            if (expected == null || !expected.equals(form.get("password"))) {
                send(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");
                return;
            }
        } else {
            send(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        String token = UUID.randomUUID().toString();
        if ("client_credentials".equals(grant)) {
            validTokens.add(token);
            tokensIssued.incrementAndGet();
        }
        send(
                exchange,
                200,
                "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}");
    }

    private void list(HttpExchange exchange, Map<String, ObjectNode> store) throws IOException {
        Recorded q = new Recorded("GET", "", exchange.getRequestURI().getRawQuery(), Map.of());
        String filter = q.param("filter");
        ScimFilterEvaluator.Node node;
        try {
            node = filter == null ? resource -> true : ScimFilterEvaluator.parse(filter);
        } catch (IllegalArgumentException e) {
            send(
                    exchange,
                    400,
                    error(400, "invalidFilter", "Bad filter '" + filter + "': " + e.getMessage())
                            .toString());
            return;
        }
        if (!memberFilterSupported && filter != null && filter.contains("members[")) {
            send(
                    exchange,
                    400,
                    error(400, "invalidFilter", "Filtering by members is not supported")
                            .toString());
            return;
        }
        List<ObjectNode> matching = new ArrayList<>();
        synchronized (this) {
            for (ObjectNode resource : store.values()) {
                if (node.test(resource)) {
                    matching.add(resource);
                }
            }
        }
        String sortBy = q.param("sortBy");
        if (sortBy != null) {
            Comparator<ObjectNode> byValue = Comparator.comparing(
                    r -> ScimFilterEvaluator.values(r, sortBy).stream()
                            .findFirst()
                            .map(JsonNode::asText)
                            .orElse(""),
                    String.CASE_INSENSITIVE_ORDER);
            matching.sort("descending".equals(q.param("sortOrder")) ? byValue.reversed() : byValue);
        }
        int startIndex = Math.max(1, q.param("startIndex") == null ? 1 : Integer.parseInt(q.param("startIndex")));
        int count = q.param("count") == null ? matching.size() : Integer.parseInt(q.param("count"));
        count = Math.min(count, maxPageSize);
        ObjectNode response = MAPPER.createObjectNode();
        response.putArray("schemas").add("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        response.put("totalResults", matching.size());
        ArrayNode resources = response.putArray("Resources");
        for (int i = startIndex - 1; i < matching.size() && resources.size() < count; i++) {
            resources.add(matching.get(i));
        }
        response.put("startIndex", startIndex);
        response.put("itemsPerPage", resources.size());
        send(exchange, 200, response.toString());
    }

    private static ObjectNode error(int status, String scimType, String detail) {
        ObjectNode error = MAPPER.createObjectNode();
        error.putArray("schemas").add(ERROR_SCHEMA);
        if (scimType != null) {
            error.put("scimType", scimType);
        }
        error.put("detail", detail);
        error.put("status", Integer.toString(status));
        return error;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
