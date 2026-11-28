/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.BadUserRequestException;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.extensions.plugin.identityscim.ScimIdentityProviderPlugin;
import org.operaton.extensions.plugin.identityscim.support.FakeScimServer;
import org.operaton.extensions.plugin.identityscim.support.Scim;

class ScimDirectoryTest {

    private static final int ALL = Integer.MAX_VALUE;
    private static final SortSpec BY_ID = new SortSpec(SortSpec.Field.ID, true);

    private FakeScimServer server;

    @BeforeEach
    void startServer() {
        server = Scim.seededServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private ScimDirectory directory() {
        return directory(plugin -> {});
    }

    private ScimDirectory directory(Consumer<ScimIdentityProviderPlugin> configure) {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        plugin.setCacheTtlMillis(0);
        configure.accept(plugin);
        var settings = plugin.validate();
        return new ScimDirectory(settings, ScimHttpClient.create(settings));
    }

    private static UserCriteria users() {
        return new UserCriteria(null, null, null, null, null, null, null, null, null);
    }

    private static UserCriteria userId(String id) {
        return new UserCriteria(id, null, null, null, null, null, null, null, null);
    }

    private static UserCriteria memberOf(String groupId) {
        return new UserCriteria(null, null, null, null, null, null, null, null, groupId);
    }

    private static GroupCriteria groups() {
        return new GroupCriteria(null, null, null, null, null, null);
    }

    private static GroupCriteria groupsOf(String userId) {
        return new GroupCriteria(null, null, null, null, null, userId);
    }

    private static List<String> ids(List<ScimUser> users) {
        return users.stream().map(ScimUser::id).toList();
    }

    private static List<String> groupIds(List<ScimGroup> groups) {
        return groups.stream().map(ScimGroup::id).toList();
    }

    // ---- mapping

    @Test
    void mapsScimUsersToEngineUsers() {
        List<ScimUser> found = directory().users(userId("alice"), List.of(), 0, ALL, null);

        assertThat(found).singleElement().satisfies(user -> {
            assertThat(user.scimId()).isEqualTo("u-1");
            assertThat(user.id()).isEqualTo("alice");
            assertThat(user.firstName()).isEqualTo("Alice");
            assertThat(user.lastName()).isEqualTo("Anderson");
            assertThat(user.email()).as("the primary email").isEqualTo("alice@example.com");
        });
    }

    @Test
    void honorsACustomAttributeMapping() {
        ScimDirectory directory = directory(p -> {
            p.setUserIdAttribute("id");
            p.setUserEmailAttribute("emails[type eq \"home\"].value");
            p.setGroupIdAttribute("id");
            p.setGroupNameAttribute("displayName");
        });

        ScimUser user = directory.findUser("u-2");
        assertThat(user.id()).isEqualTo("u-2");
        assertThat(user.email()).isEqualTo("private-bob@example.com");
        assertThat(directory.users(
                        new UserCriteria(null, null, null, null, null, null, "private-bob@example.com", null, null),
                        List.of(),
                        0,
                        ALL,
                        null))
                .extracting(ScimUser::id)
                .containsExactly("u-2");
        assertThat(server.requestsTo("/Users")
                        .get(server.requestsTo("/Users").size() - 1)
                        .param("filter"))
                .isEqualTo("emails[type eq \"home\" and value eq \"private-bob@example.com\"]");
        assertThat(directory.findGroup("g-1").name()).isEqualTo("approvers");
    }

    @Test
    void aUserWithoutTheIdAttributeFailsInsteadOfFallingBack() {
        ScimDirectory directory = directory(p -> p.setUserIdAttribute("externalId"));

        assertThatThrownBy(() -> directory.users(users(), List.of(), 0, ALL, null))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("u-")
                .hasMessageContaining("externalId")
                .hasMessageContaining("userIdAttribute");
    }

    // ---- paging, filters, sorting

    @Test
    void walksThroughPagesOfTheConfiguredSize() {
        ScimDirectory directory = directory(p -> p.setPageSize(1));

        List<ScimUser> found = directory.users(users(), List.of(), 0, ALL, null);

        assertThat(ids(found)).containsExactly("alice", "bob", "carol", "dave");
        List<FakeScimServer.Recorded> requests = server.requestsTo("/Users");
        assertThat(requests).extracting(r -> r.param("startIndex")).containsExactly("1", "2", "3", "4");
        assertThat(requests).extracting(r -> r.param("count")).containsOnly("1");
    }

    @Test
    void followsAServerThatReturnsFewerResourcesThanAskedFor() {
        server.maxPageSize(2);

        List<ScimUser> found = directory().users(users(), List.of(), 0, ALL, null);

        assertThat(ids(found)).containsExactly("alice", "bob", "carol", "dave");
        assertThat(server.requestsTo("/Users"))
                .extracting(r -> r.param("startIndex"))
                .containsExactly("1", "3");
    }

    @Test
    void requestsOnlyTheRequestedPage() {
        List<ScimUser> found = directory().users(users(), List.of(BY_ID), 1, 2, null);

        assertThat(ids(found)).containsExactly("bob", "carol");
        assertThat(server.requestsTo("/Users")).singleElement().satisfies(request -> {
            assertThat(request.param("startIndex")).isEqualTo("2");
            assertThat(request.param("count")).isEqualTo("2");
            assertThat(request.param("sortBy")).isEqualTo("userName");
            assertThat(request.param("sortOrder")).isEqualTo("ascending");
        });
    }

    @Test
    void aPageBeyondTheEndIsEmpty() {
        assertThat(directory().users(users(), List.of(), 10, 5, null)).isEmpty();
    }

    @Test
    void translatesTheCriteriaToOneFilter() {
        UserCriteria criteria = new UserCriteria(
                null, List.of("alice", "carol"), null, "%li%", null, "Anderson%", null, "%@example.com", null);

        List<ScimUser> found = directory().users(criteria, List.of(), 0, ALL, null);

        assertThat(ids(found)).containsExactly("alice");
        assertThat(server.requestsTo("/Users").get(0).param("filter"))
                .isEqualTo("(userName eq \"alice\" or userName eq \"carol\") and name.givenName co \"li\""
                        + " and name.familyName sw \"Anderson\" and emails.value ew \"@example.com\"");
    }

    @Test
    void appliesTheBaseFilterToEveryUserRequest() {
        server.active("u-2", false);
        ScimDirectory directory = directory(p -> p.setUserBaseFilter("active eq true"));

        assertThat(ids(directory.users(users(), List.of(), 0, ALL, null))).containsExactly("alice", "carol", "dave");
        assertThat(directory.findUser("bob")).isNull();
        assertThat(directory.countUsers(users(), null)).isEqualTo(3);
        assertThat(server.requestsTo("/Users"))
                .extracting(r -> r.param("filter"))
                .allSatisfy(f -> assertThat(f).startsWith("(active eq true)"));
    }

    @Test
    void ordersByTwoAttributesOnTheClient() {
        server.user("u-5", "erin", "Erin", "Anderson", "erin@example.com");
        UserCriteria all = users();

        List<ScimUser> found = directory()
                .users(
                        all,
                        List.of(
                                new SortSpec(SortSpec.Field.LAST_NAME, true),
                                new SortSpec(SortSpec.Field.FIRST_NAME, false)),
                        0,
                        3,
                        null);

        assertThat(ids(found)).containsExactly("erin", "alice", "bob");
        assertThat(server.requestsTo("/Users"))
                .as("read completely, ordered and cut on the client")
                .hasSize(1);
        assertThat(server.requestsTo("/Users").get(0).param("sortBy")).isNull();
    }

    @Test
    void countsWithoutReadingAPage() {
        long count =
                directory().countUsers(new UserCriteria(null, null, null, "A%", null, null, null, null, null), null);

        assertThat(count).isEqualTo(1);
        assertThat(server.requestsTo("/Users"))
                .singleElement()
                .satisfies(request -> assertThat(request.param("count")).isEqualTo("1"));
    }

    @Test
    void filtersByAVisibilityPredicateBeforePaging() {
        var visible = (java.util.function.Predicate<ScimUser>)
                u -> List.of("bob", "dave").contains(u.id());
        ScimDirectory directory = directory();

        assertThat(ids(directory.users(users(), List.of(BY_ID), 1, 1, visible))).containsExactly("dave");
        assertThat(directory.countUsers(users(), visible)).isEqualTo(2);
    }

    @Test
    void anUnsupportedLikePatternFailsLoudly() {
        ScimDirectory directory = directory();

        assertThatThrownBy(() -> directory.users(
                        new UserCriteria(null, null, null, "a%b", null, null, null, null, null),
                        List.of(),
                        0,
                        ALL,
                        null))
                .isInstanceOf(BadUserRequestException.class);
    }

    // ---- single lookups

    @Test
    void findsOneUserOrNone() {
        ScimDirectory directory = directory();

        assertThat(directory.findUser("carol").email()).isEqualTo("carol@example.com");
        assertThat(directory.findUser("nobody")).isNull();
        assertThat(directory.findGroup("clerks").scimId()).isEqualTo("g-2");
        assertThat(directory.findGroup("nothing")).isNull();
    }

    @Test
    void aNonUniqueIdFailsLoudly() {
        server.user("u-9", "alice", "Other", "Alice", "other@example.com").group("g-9", "approvers");
        ScimDirectory directory = directory();

        assertThatThrownBy(() -> directory.findUser("alice"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("More than one SCIM user")
                .hasMessageContaining("alice");
        assertThatThrownBy(() -> directory.findGroup("approvers"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("More than one SCIM group");
    }

    // ---- malformed answers

    @Test
    void aListResponseWithoutTotalResultsFails() {
        server.failWith(200, "{\"Resources\":[]}");
        assertThatThrownBy(() -> directory().users(users(), List.of(), 0, ALL, null))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("totalResults");
    }

    @Test
    void resourcesThatAreNotAnArrayFail() {
        server.failWith(200, "{\"totalResults\":1,\"Resources\":{}}");
        assertThatThrownBy(() -> directory().users(users(), List.of(), 0, ALL, null))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("Resources");
    }

    @Test
    void missingResourcesDespiteATotalFail() {
        server.failWith(200, "{\"totalResults\":3}");
        assertThatThrownBy(() -> directory().users(users(), List.of(), 0, ALL, null))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("returned no Resources");
    }

    @Test
    void aResourceWithoutAnIdFails() {
        server.failWith(200, "{\"totalResults\":1,\"Resources\":[{\"userName\":\"x\"}]}");
        assertThatThrownBy(() -> directory().users(users(), List.of(), 0, ALL, null))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("without an 'id'");
    }

    // ---- membership: users of a group

    @Test
    void listsTheMembersOfAGroupAndAppliesTheOtherCriteria() {
        ScimDirectory directory = directory();

        assertThat(ids(directory.users(memberOf("approvers"), List.of(BY_ID), 0, ALL, null)))
                .containsExactly("alice", "carol");
        assertThat(ids(directory.users(memberOf("approvers"), List.of(BY_ID), 1, 1, null)))
                .containsExactly("carol");
        assertThat(ids(directory.users(
                        new UserCriteria(null, null, null, "c%", null, null, null, null, "approvers"),
                        List.of(),
                        0,
                        ALL,
                        null)))
                .containsExactly("carol");
        assertThat(directory.countUsers(memberOf("approvers"), null)).isEqualTo(2);
        assertThat(directory.users(memberOf("no-such-group"), List.of(), 0, ALL, null))
                .isEmpty();
    }

    @Test
    void membersWithADanglingReferenceAreSkipped() {
        server.group("g-9", "ghosts", "u-1", "u-ghost");

        assertThat(ids(directory().users(memberOf("ghosts"), List.of(), 0, ALL, null)))
                .containsExactly("alice");
    }

    @Test
    void membersOfTypeGroupAreNotUsers() {
        server.group("g-9", "nested", "u-1", "g-1");

        assertThat(ids(directory().users(memberOf("nested"), List.of(), 0, ALL, null)))
                .containsExactly("alice");
        assertThat(server.requestsTo("/Groups/g-1")).isEmpty();
    }

    @Test
    void theBaseFilterAppliesToMembersToo() {
        server.active("u-3", false);
        ScimDirectory directory = directory(p -> p.setUserBaseFilter("active eq true"));

        assertThat(ids(directory.users(memberOf("approvers"), List.of(), 0, ALL, null)))
                .containsExactly("alice");
    }

    // ---- membership: groups

    @Test
    void groupsOfAUserAreFoundByMemberFilter() {
        ScimDirectory directory = directory();

        assertThat(groupIds(directory.groups(
                        groupsOf("alice"), List.of(new SortSpec(SortSpec.Field.NAME, true)), 0, ALL, null)))
                .containsExactly("all-staff", "approvers");
        assertThat(server.requestsTo("/Groups").get(0).param("filter")).isEqualTo("members[value eq \"u-1\"]");
        assertThat(directory.countGroups(groupsOf("bob"), null)).isEqualTo(2);
        assertThat(directory.groups(groupsOf("nobody"), List.of(), 0, ALL, null))
                .isEmpty();
        assertThat(server.requestsTo("/Groups"))
                .as("an unknown user needs no group request")
                .hasSize(2);
    }

    @Test
    void groupCriteriaCombineWithMembership() {
        GroupCriteria criteria = new GroupCriteria(null, null, null, "app%", null, "alice");

        assertThat(groupIds(directory().groups(criteria, List.of(), 0, ALL, null)))
                .containsExactly("approvers");
        assertThat(server.requestsTo("/Groups").get(0).param("filter"))
                .isEqualTo("displayName sw \"app\" and members[value eq \"u-1\"]");
    }

    @Test
    void groupsAreOrderedAndFilteredByNameAndId() {
        ScimDirectory directory = directory();

        assertThat(groupIds(directory.groups(groups(), List.of(new SortSpec(SortSpec.Field.ID, false)), 0, ALL, null)))
                .containsExactly("operaton-admin", "clerks", "approvers", "all-staff");
        assertThat(groupIds(directory.groups(
                        new GroupCriteria(null, List.of("clerks", "approvers"), null, null, null, null),
                        List.of(BY_ID),
                        0,
                        ALL,
                        null)))
                .containsExactly("approvers", "clerks");
        assertThat(directory.countGroups(new GroupCriteria(null, null, "clerks", null, null, null), null))
                .isEqualTo(1);
    }

    @Test
    void theGroupTypeIsScim() {
        ScimDirectory directory = directory();

        assertThat(directory.groups(new GroupCriteria(null, null, null, null, "LDAP", null), List.of(), 0, ALL, null))
                .isEmpty();
        assertThat(directory.countGroups(new GroupCriteria(null, null, null, null, "LDAP", null), null))
                .isZero();
        assertThat(server.requestsTo("/Groups")).isEmpty();
        assertThat(directory.groups(new GroupCriteria(null, null, null, null, "SCIM", null), List.of(), 0, ALL, null))
                .hasSize(4);
    }

    @Test
    void theGroupBaseFilterAppliesToEveryGroupRequest() {
        ScimDirectory directory = directory(p -> p.setGroupBaseFilter("displayName sw \"a\""));

        assertThat(groupIds(directory.groups(groups(), List.of(BY_ID), 0, ALL, null)))
                .containsExactly("all-staff", "approvers");
        assertThat(directory.findGroup("clerks")).isNull();
    }

    @Test
    void groupsCanBeReadFromTheUsersGroupsClaim() {
        server.withUserGroupsClaim().memberFilterSupported(false);
        ScimDirectory directory = directory(p -> p.setMembershipSource("user-groups"));

        assertThat(groupIds(directory.groups(
                        groupsOf("alice"), List.of(new SortSpec(SortSpec.Field.NAME, true)), 0, ALL, null)))
                .containsExactly("all-staff", "approvers");
        assertThat(directory.countGroups(groupsOf("dave"), null)).isEqualTo(2);
        assertThat(groupIds(directory.groups(
                        new GroupCriteria(null, null, "approvers", null, null, "alice"), List.of(), 0, ALL, null)))
                .containsExactly("approvers");
        assertThat(server.requestsTo("/Groups"))
                .as("no filtering by member; the referenced groups are fetched by id")
                .extracting(FakeScimServer.Recorded::path)
                .allMatch(path -> path.startsWith("/Groups/"));
    }

    @Test
    void theMemberFilterOfAServerThatCannotDoItFailsLoudly() {
        server.memberFilterSupported(false);

        assertThatThrownBy(() -> directory().groups(groupsOf("alice"), List.of(), 0, ALL, null))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("status 400")
                .hasMessageContaining("members");
    }
}
