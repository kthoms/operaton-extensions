/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The plugin against WireMock, the standard SCIM stub of the example: shows what goes over the wire
 * (path, filter, paging parameters, headers) as the SCIM protocol specifies it, independent of the
 * in-JVM server the other tests use.
 */
@Testcontainers
@Timeout(180)
class ScimWireMockIT {

    @Container
    static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.13.1")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private ProcessEngine engine;

    @BeforeAll
    static void containerIsUp() {
        assertThat(WIREMOCK.isRunning()).isTrue();
    }

    @BeforeEach
    void resetStubs() throws Exception {
        admin("DELETE", "/__admin/mappings", null);
        admin("DELETE", "/__admin/requests", null);
    }

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @AfterAll
    static void done() {}

    private static String admin(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080) + path));
        request.header("Content-Type", "application/json");
        request.method(
                method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isBetween(200, 201);
        return response.body();
    }

    private static void stub(String filter, String startIndex, String count, String resources, int total)
            throws Exception {
        ObjectNode mapping = JSON.createObjectNode();
        ObjectNode request = mapping.putObject("request");
        request.put("method", "GET");
        request.put("urlPath", "/scim/v2/" + (filter.startsWith("members") ? "Groups" : "Users"));
        request.putObject("headers").putObject("Authorization").put("equalTo", "Bearer t0ken");
        ObjectNode query = request.putObject("queryParameters");
        query.putObject("filter").put("equalTo", filter);
        query.putObject("startIndex").put("equalTo", startIndex);
        query.putObject("count").put("equalTo", count);
        ObjectNode response = mapping.putObject("response");
        response.put("status", 200);
        response.putObject("headers").put("Content-Type", "application/scim+json");
        ObjectNode body = JSON.createObjectNode();
        body.putArray("schemas").add("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        body.put("totalResults", total);
        body.set("Resources", JSON.readTree(resources));
        response.put("body", body.toString());
        admin("POST", "/__admin/mappings", mapping.toString());
    }

    private ProcessEngine engine(Consumer<ScimIdentityProviderPlugin> customizer) {
        ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
        plugin.setBaseUrl("http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080) + "/scim/v2");
        plugin.setBearerToken("t0ken");
        plugin.setCacheTtlMillis(0);
        customizer.accept(plugin);
        StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setProcessEngineName("scim-wiremock-" + UUID.randomUUID());
        configuration.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        configuration.setJobExecutorActivate(false);
        configuration.getProcessEnginePlugins().add(plugin);
        engine = configuration.buildProcessEngine();
        return engine;
    }

    private static final String ALICE = """
            {"id":"u-alice","userName":"alice","name":{"givenName":"Alice","familyName":"Anderson"},
             "emails":[{"value":"alice@example.com","type":"work","primary":true}]}""";
    private static final String BOB = """
            {"id":"u-bob","userName":"bob","name":{"givenName":"Bob","familyName":"Brown"},
             "emails":[{"value":"bob@example.com","type":"work"}]}""";
    private static final String CAROL = """
            {"id":"u-carol","userName":"carol","name":{"givenName":"Carol","familyName":"Clark"},
             "emails":[{"value":"carol@example.com","type":"work"}]}""";

    @Test
    void findsAUserByIdWithTheFilterTheSpecificationDefines() throws Exception {
        stub("userName eq \"alice\"", "1", "2", "[" + ALICE + "]", 1);

        User alice = engine(p -> {})
                .getIdentityService()
                .createUserQuery()
                .userId("alice")
                .singleResult();

        assertThat(alice.getFirstName()).isEqualTo("Alice");
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void walksThroughPagesWithStartIndexAndCount() throws Exception {
        stub("(active eq true)", "1", "2", "[" + ALICE + "," + BOB + "]", 3);
        stub("(active eq true)", "3", "2", "[" + CAROL + "]", 3);

        ProcessEngine scim = engine(p -> {
            p.setPageSize(2);
            p.setUserBaseFilter("active eq true");
        });

        assertThat(scim.getIdentityService().createUserQuery().list())
                .extracting(User::getId)
                .containsExactly("alice", "bob", "carol");
    }

    @Test
    void findsTheGroupsOfAUserByMembership() throws Exception {
        stub("userName eq \"alice\"", "1", "2", "[" + ALICE + "]", 1);
        stub(
                "members[value eq \"u-alice\"]",
                "1",
                "100",
                "[{\"id\":\"g-1\",\"displayName\":\"approvers\"},{\"id\":\"g-2\",\"displayName\":\"all-staff\"}]",
                2);

        assertThat(engine(p -> {})
                        .getIdentityService()
                        .createGroupQuery()
                        .groupMember("alice")
                        .list())
                .extracting(Group::getId)
                .containsExactly("approvers", "all-staff");
    }

    @Test
    void aRequestTheStubDoesNotKnowFailsInsteadOfReturningNothing() {
        assertThatThrownBy(() -> engine(p -> {})
                        .getIdentityService()
                        .createUserQuery()
                        .userId("alice")
                        .singleResult())
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("status 404");
    }

    @Test
    void everyRequestCarriesTheBearerToken() throws Exception {
        stub("userName eq \"alice\"", "1", "2", "[" + ALICE + "]", 1);
        engine(p -> {}).getIdentityService().createUserQuery().userId("alice").singleResult();

        String count = admin("POST", "/__admin/requests/count", """
                {"method":"GET","urlPath":"/scim/v2/Users","headers":{"Authorization":{"equalTo":"Bearer t0ken"},
                 "Accept":{"contains":"application/scim+json"}}}""");

        assertThat(count).contains("\"count\" : 1");
    }
}
