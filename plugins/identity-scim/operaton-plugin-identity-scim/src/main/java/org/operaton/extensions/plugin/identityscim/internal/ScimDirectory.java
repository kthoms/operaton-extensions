/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimIdentityProviderReadOnly.java (Apache-2.0)
 * Ported: the query logic (findUsersWithoutGroupId, findUsersByGroupId, findGroupByQueryCriteria), the
 * mapping of SCIM resources to users and groups (transformUser, transformGroup), the membership lookup
 * through the group's members attribute and through members[value eq "..."], the single sort attribute
 * sent to the server. Changes: engine independent (no command context); paging that follows servers
 * which return fewer resources than asked for; a count that does not request a page; the other
 * criteria of a member-of-group query are applied (the original ignored them); client-side ordering
 * when the server cannot do it; a missing id attribute fails instead of falling back to the SCIM id.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.extensions.plugin.identityscim.ScimMembershipSource;
import org.operaton.extensions.plugin.identityscim.ScimSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads users, groups and their relations from the SCIM server and applies the attribute mapping.
 * Engine independent; every method either returns what the server holds or throws.
 *
 * <p>Queries that the server can answer (filter, one sort attribute, a page) are sent as one
 * request per page. Queries that need client-side work (a visibility predicate, several sort
 * attributes, member-of-group and user-groups lookups) read the matching resources completely and
 * then filter, order and slice them.
 *
 * <p>Thread-safe: immutable apart from the client's cache.
 */
public final class ScimDirectory {

    /** The type reported for every group. */
    public static final String GROUP_TYPE = "SCIM";

    private static final Logger LOG = LoggerFactory.getLogger(ScimDirectory.class);

    private final ScimSettings settings;
    private final ScimSettings.Mapping mapping;
    private final ScimHttpClient client;

    /**
     * Creates the directory.
     *
     * @param settings the validated settings
     * @param client the SCIM client
     */
    public ScimDirectory(ScimSettings settings, ScimHttpClient client) {
        this.settings = settings;
        this.mapping = settings.mapping();
        this.client = client;
    }

    // ---- users

    /**
     * Lists users.
     *
     * @param criteria the query criteria
     * @param order the ordering, most significant first
     * @param first the index of the first result, 0-based
     * @param max the maximum number of results; {@link Integer#MAX_VALUE} for all
     * @param visible restricts the result to the users it accepts; {@code null} accepts all
     * @return the users
     */
    public List<ScimUser> users(
            UserCriteria criteria, List<SortSpec> order, int first, int max, Predicate<ScimUser> visible) {
        if (criteria.groupId() != null) {
            List<ScimUser> members = new ArrayList<>();
            for (ScimUser member : membersOf(criteria.groupId())) {
                if (matches(criteria, member) && (visible == null || visible.test(member))) {
                    members.add(member);
                }
            }
            members.sort(comparator(order, ScimDirectory::userValue));
            return slice(members, first, max);
        }
        return serverSide(
                settings.usersEndpoint(),
                userFilter(criteria),
                order,
                this::toUser,
                ScimDirectory::userValue,
                this::userSortAttribute,
                visible,
                first,
                max);
    }

    /**
     * Counts users.
     *
     * @param criteria the query criteria
     * @param visible restricts the count to the users it accepts; {@code null} accepts all
     * @return the number of matching users
     */
    public long countUsers(UserCriteria criteria, Predicate<ScimUser> visible) {
        if (criteria.groupId() != null || visible != null) {
            return users(criteria, List.of(), 0, Integer.MAX_VALUE, visible).size();
        }
        return total(settings.usersEndpoint(), userFilter(criteria));
    }

    /**
     * Finds the user with the engine user id.
     *
     * @param userId the engine user id
     * @return the user, or {@code null} when there is none
     * @throws IdentityProviderException when several users have the id
     */
    public ScimUser findUser(String userId) {
        List<JsonNode> found = page(
                settings.usersEndpoint(),
                ScimFilters.and(List.of(
                        nullToEmpty(ScimFilters.base(mapping.userBaseFilter())),
                        ScimFilters.eq(mapping.userId(), userId))),
                null,
                true,
                0,
                2);
        if (found.size() > 1) {
            throw new IdentityProviderException("More than one SCIM user has " + mapping.userId() + " '" + userId
                    + "'; the user id must be unique");
        }
        return found.isEmpty() ? null : toUser(found.get(0));
    }

    private String userFilter(UserCriteria c) {
        List<String> conditions = new ArrayList<>();
        conditions.add(ScimFilters.base(mapping.userBaseFilter()));
        if (c.id() != null) {
            conditions.add(ScimFilters.eq(mapping.userId(), c.id()));
        }
        if (c.ids() != null && !c.ids().isEmpty()) {
            conditions.add(ScimFilters.anyOf(mapping.userId(), c.ids()));
        }
        if (c.firstName() != null) {
            conditions.add(ScimFilters.eq(mapping.userFirstName(), c.firstName()));
        }
        if (c.firstNameLike() != null) {
            conditions.add(ScimFilters.like(mapping.userFirstName(), c.firstNameLike()));
        }
        if (c.lastName() != null) {
            conditions.add(ScimFilters.eq(mapping.userLastName(), c.lastName()));
        }
        if (c.lastNameLike() != null) {
            conditions.add(ScimFilters.like(mapping.userLastName(), c.lastNameLike()));
        }
        if (c.email() != null) {
            conditions.add(ScimFilters.eq(mapping.userEmail(), c.email()));
        }
        if (c.emailLike() != null) {
            conditions.add(ScimFilters.like(mapping.userEmail(), c.emailLike()));
        }
        return ScimFilters.and(conditions);
    }

    private static boolean matches(UserCriteria c, ScimUser user) {
        return (c.id() == null || c.id().equalsIgnoreCase(user.id()))
                && (c.ids() == null
                        || c.ids().isEmpty()
                        || c.ids().stream().anyMatch(i -> i.equalsIgnoreCase(user.id())))
                && (c.firstName() == null || c.firstName().equalsIgnoreCase(user.firstName()))
                && (c.firstNameLike() == null || ScimFilters.matchesLike(c.firstNameLike(), user.firstName()))
                && (c.lastName() == null || c.lastName().equalsIgnoreCase(user.lastName()))
                && (c.lastNameLike() == null || ScimFilters.matchesLike(c.lastNameLike(), user.lastName()))
                && (c.email() == null || c.email().equalsIgnoreCase(user.email()))
                && (c.emailLike() == null || ScimFilters.matchesLike(c.emailLike(), user.email()));
    }

    private ScimUser toUser(JsonNode resource) {
        String scimId = requiredId(resource, "user");
        String id = ScimJson.text(resource, mapping.userId());
        if (id == null || id.isBlank()) {
            throw new IdentityProviderException("The SCIM user '" + scimId + "' has no value for the attribute '"
                    + mapping.userId() + "' (userIdAttribute); it cannot become an engine user");
        }
        return new ScimUser(
                scimId,
                id,
                ScimJson.text(resource, mapping.userFirstName()),
                ScimJson.text(resource, mapping.userLastName()),
                ScimJson.text(resource, mapping.userEmail()),
                resource);
    }

    private String userSortAttribute(SortSpec.Field field) {
        return switch (field) {
            case ID -> ScimFilters.sortPath(mapping.userId());
            case FIRST_NAME -> ScimFilters.sortPath(mapping.userFirstName());
            case LAST_NAME -> ScimFilters.sortPath(mapping.userLastName());
            case EMAIL -> ScimFilters.sortPath(mapping.userEmail());
            default -> throw new IllegalArgumentException("Users cannot be ordered by " + field);
        };
    }

    private static String userValue(ScimUser user, SortSpec.Field field) {
        return switch (field) {
            case ID -> user.id();
            case FIRST_NAME -> user.firstName();
            case LAST_NAME -> user.lastName();
            case EMAIL -> user.email();
            default -> throw new IllegalArgumentException("Users cannot be ordered by " + field);
        };
    }

    private List<ScimUser> membersOf(String groupId) {
        ScimGroup group = findGroup(groupId);
        if (group == null) {
            return List.of();
        }
        JsonNode resource = client.resource(settings.groupsEndpoint(), group.scimId());
        List<ScimUser> members = new ArrayList<>();
        for (JsonNode member : ScimJson.elements(resource, mapping.groupMembers())) {
            String type = member.path("type").asText(null);
            if (type != null && !type.equalsIgnoreCase("User")) {
                continue;
            }
            String value = member.path("value").asText(null);
            if (value == null || value.isBlank()) {
                throw new IdentityProviderException(
                        "The SCIM group '" + group.scimId() + "' has a member without a 'value'");
            }
            JsonNode user = fetchByScimId(settings.usersEndpoint(), value, mapping.userBaseFilter());
            if (user != null) {
                members.add(toUser(user));
            }
        }
        return members;
    }

    // ---- groups

    /**
     * Lists groups.
     *
     * @param criteria the query criteria
     * @param order the ordering, most significant first
     * @param first the index of the first result, 0-based
     * @param max the maximum number of results; {@link Integer#MAX_VALUE} for all
     * @param visible restricts the result to the groups it accepts; {@code null} accepts all
     * @return the groups
     */
    public List<ScimGroup> groups(
            GroupCriteria criteria, List<SortSpec> order, int first, int max, Predicate<ScimGroup> visible) {
        if (criteria.type() != null && !GROUP_TYPE.equals(criteria.type())) {
            return List.of();
        }
        List<SortSpec> effectiveOrder =
                order.stream().filter(s -> s.field() != SortSpec.Field.TYPE).toList();
        if (criteria.userId() != null && mapping.membershipSource() == ScimMembershipSource.USER_GROUPS) {
            List<ScimGroup> groups = new ArrayList<>();
            for (ScimGroup group : groupsOfUserByClaim(criteria.userId())) {
                if (matches(criteria, group) && (visible == null || visible.test(group))) {
                    groups.add(group);
                }
            }
            groups.sort(comparator(effectiveOrder, ScimDirectory::groupValue));
            return slice(groups, first, max);
        }
        ScimUser member = null;
        if (criteria.userId() != null) {
            member = findUser(criteria.userId());
            if (member == null) {
                return List.of();
            }
        }
        String filter = groupFilter(criteria, member);
        return serverSide(
                settings.groupsEndpoint(),
                filter,
                effectiveOrder,
                this::toGroup,
                ScimDirectory::groupValue,
                this::groupSortAttribute,
                visible,
                first,
                max);
    }

    /**
     * Counts groups.
     *
     * @param criteria the query criteria
     * @param visible restricts the count to the groups it accepts; {@code null} accepts all
     * @return the number of matching groups
     */
    public long countGroups(GroupCriteria criteria, Predicate<ScimGroup> visible) {
        boolean byClaim = criteria.userId() != null && mapping.membershipSource() == ScimMembershipSource.USER_GROUPS;
        if (byClaim || visible != null || (criteria.type() != null && !GROUP_TYPE.equals(criteria.type()))) {
            return groups(criteria, List.of(), 0, Integer.MAX_VALUE, visible).size();
        }
        ScimUser member = null;
        if (criteria.userId() != null) {
            member = findUser(criteria.userId());
            if (member == null) {
                return 0;
            }
        }
        return total(settings.groupsEndpoint(), groupFilter(criteria, member));
    }

    /**
     * Finds the group with the engine group id.
     *
     * @param groupId the engine group id
     * @return the group, or {@code null} when there is none
     * @throws IdentityProviderException when several groups have the id
     */
    public ScimGroup findGroup(String groupId) {
        List<JsonNode> found = page(
                settings.groupsEndpoint(),
                ScimFilters.and(List.of(
                        nullToEmpty(ScimFilters.base(mapping.groupBaseFilter())),
                        ScimFilters.eq(mapping.groupId(), groupId))),
                null,
                true,
                0,
                2);
        if (found.size() > 1) {
            throw new IdentityProviderException("More than one SCIM group has " + mapping.groupId() + " '" + groupId
                    + "'; the group id must be unique");
        }
        return found.isEmpty() ? null : toGroup(found.get(0));
    }

    private String groupFilter(GroupCriteria c, ScimUser member) {
        List<String> conditions = new ArrayList<>();
        conditions.add(ScimFilters.base(mapping.groupBaseFilter()));
        if (c.id() != null) {
            conditions.add(ScimFilters.eq(mapping.groupId(), c.id()));
        }
        if (c.ids() != null && !c.ids().isEmpty()) {
            conditions.add(ScimFilters.anyOf(mapping.groupId(), c.ids()));
        }
        if (c.name() != null) {
            conditions.add(ScimFilters.eq(mapping.groupName(), c.name()));
        }
        if (c.nameLike() != null) {
            conditions.add(ScimFilters.like(mapping.groupName(), c.nameLike()));
        }
        if (member != null) {
            conditions.add(ScimFilters.memberValue(mapping.groupMembers(), member.scimId()));
        }
        return ScimFilters.and(conditions);
    }

    private static boolean matches(GroupCriteria c, ScimGroup group) {
        return (c.id() == null || c.id().equalsIgnoreCase(group.id()))
                && (c.ids() == null
                        || c.ids().isEmpty()
                        || c.ids().stream().anyMatch(i -> i.equalsIgnoreCase(group.id())))
                && (c.name() == null || c.name().equalsIgnoreCase(group.name()))
                && (c.nameLike() == null || ScimFilters.matchesLike(c.nameLike(), group.name()));
    }

    private ScimGroup toGroup(JsonNode resource) {
        String scimId = requiredId(resource, "group");
        String id = ScimJson.text(resource, mapping.groupId());
        if (id == null || id.isBlank()) {
            throw new IdentityProviderException("The SCIM group '" + scimId + "' has no value for the attribute '"
                    + mapping.groupId() + "' (groupIdAttribute); it cannot become an engine group");
        }
        return new ScimGroup(scimId, id, ScimJson.text(resource, mapping.groupName()), resource);
    }

    private String groupSortAttribute(SortSpec.Field field) {
        return switch (field) {
            case ID -> ScimFilters.sortPath(mapping.groupId());
            case NAME -> ScimFilters.sortPath(mapping.groupName());
            default -> throw new IllegalArgumentException("Groups cannot be ordered by " + field);
        };
    }

    private static String groupValue(ScimGroup group, SortSpec.Field field) {
        return switch (field) {
            case ID -> group.id();
            case NAME -> group.name();
            default -> throw new IllegalArgumentException("Groups cannot be ordered by " + field);
        };
    }

    private List<ScimGroup> groupsOfUserByClaim(String userId) {
        ScimUser user = findUser(userId);
        if (user == null) {
            return List.of();
        }
        List<ScimGroup> groups = new ArrayList<>();
        for (JsonNode reference : ScimJson.elements(user.resource(), mapping.userGroups())) {
            String value = reference.path("value").asText(null);
            if (value == null || value.isBlank()) {
                throw new IdentityProviderException("The SCIM user '" + user.scimId() + "' has a '"
                        + mapping.userGroups() + "' entry without a 'value'");
            }
            JsonNode group = fetchByScimId(settings.groupsEndpoint(), value, mapping.groupBaseFilter());
            if (group != null) {
                groups.add(toGroup(group));
            }
        }
        return groups;
    }

    // ---- shared

    /** Drops the cached responses and tokens. */
    public void close() {
        client.clearCache();
    }

    private <T> List<T> serverSide(
            String endpoint,
            String filter,
            List<SortSpec> order,
            Function<JsonNode, T> mapper,
            BiFunction<T, SortSpec.Field, String> value,
            Function<SortSpec.Field, String> sortAttribute,
            Predicate<T> visible,
            int first,
            int max) {
        boolean clientSide = visible != null || order.size() > 1;
        if (!clientSide) {
            SortSpec sort = order.isEmpty() ? null : order.get(0);
            return page(
                            endpoint,
                            filter,
                            sort == null ? null : sortAttribute.apply(sort.field()),
                            sort == null || sort.ascending(),
                            first,
                            max)
                    .stream()
                    .map(mapper)
                    .toList();
        }
        List<T> all = new ArrayList<>();
        for (JsonNode resource : page(endpoint, filter, null, true, 0, Integer.MAX_VALUE)) {
            T entity = mapper.apply(resource);
            if (visible == null || visible.test(entity)) {
                all.add(entity);
            }
        }
        all.sort(comparator(order, value));
        return slice(all, first, max);
    }

    /** Reads up to {@code max} resources of a collection, from the 0-based index {@code first}, page by page. */
    private List<JsonNode> page(String endpoint, String filter, String sortBy, boolean ascending, long first, int max) {
        List<JsonNode> result = new ArrayList<>();
        long startIndex = first + 1;
        long remaining = max;
        while (remaining > 0) {
            int count = (int) Math.min(remaining, settings.pageSize());
            JsonNode response = client.search(endpoint, filter, sortBy, ascending, (int) startIndex, count);
            long total = totalResults(response, endpoint);
            JsonNode resources = response.get("Resources");
            if (resources != null && !resources.isNull() && !resources.isArray()) {
                throw new IdentityProviderException(
                        "The SCIM answer of " + endpoint + " has a 'Resources' that is not an array");
            }
            int returned = resources == null || resources.isNull() ? 0 : resources.size();
            if (returned == 0) {
                if (startIndex <= total) {
                    throw new IdentityProviderException("The SCIM server reports totalResults=" + total + " for "
                            + endpoint + " but returned no Resources at startIndex=" + startIndex);
                }
                break;
            }
            int taken = (int) Math.min(returned, remaining);
            for (int i = 0; i < taken; i++) {
                result.add(resources.get(i));
            }
            startIndex += taken;
            remaining -= taken;
            if (startIndex > total) {
                break;
            }
        }
        return result;
    }

    private long total(String endpoint, String filter) {
        return totalResults(client.search(endpoint, filter, null, true, 1, 1), endpoint);
    }

    private static long totalResults(JsonNode response, String endpoint) {
        JsonNode total = response.get("totalResults");
        if (total == null || !total.canConvertToLong() || !total.isIntegralNumber()) {
            throw new IdentityProviderException(
                    "The SCIM answer of " + endpoint + " has no numeric 'totalResults' (required by RFC 7644)");
        }
        return total.asLong();
    }

    /** Reads a resource by SCIM id, honoring the base filter; {@code null} when it does not exist. */
    private JsonNode fetchByScimId(String endpoint, String scimId, String baseFilter) {
        if (ScimFilters.base(baseFilter) == null) {
            try {
                return client.resource(endpoint, scimId);
            } catch (ScimHttpException e) {
                if (e.getStatus() == 404) {
                    LOG.warn("SCIM {} lists the unknown resource '{}'; it is skipped", endpoint, scimId);
                    return null;
                }
                throw e;
            }
        }
        List<JsonNode> found = page(
                endpoint,
                ScimFilters.and(List.of(ScimFilters.base(baseFilter), ScimFilters.eq("id", scimId))),
                null,
                true,
                0,
                1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String requiredId(JsonNode resource, String kind) {
        String id = resource.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new IdentityProviderException("The SCIM server returned a " + kind + " without an 'id'");
        }
        return id;
    }

    private static <T> Comparator<T> comparator(List<SortSpec> order, BiFunction<T, SortSpec.Field, String> value) {
        Comparator<T> comparator = (a, b) -> 0;
        for (SortSpec spec : order) {
            Comparator<String> byValue = Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER);
            Comparator<T> next = Comparator.comparing(t -> value.apply(t, spec.field()), byValue);
            comparator = comparator.thenComparing(spec.ascending() ? next : next.reversed());
        }
        return comparator;
    }

    private static <T> List<T> slice(List<T> all, int first, int max) {
        int from = Math.min(Math.max(first, 0), all.size());
        long to = Math.min((long) from + max, all.size());
        return new ArrayList<>(all.subList(from, (int) to));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String toString() {
        return "ScimDirectory[" + settings.baseUrl() + "]";
    }
}
