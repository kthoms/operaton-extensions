# Identity SCIM Plugin

Resolves Operaton users, groups and group membership from a SCIM 2.0 server (Microsoft Entra ID, Okta, Keycloak and others) as a read-only identity provider.

## When to use

Use it when your people and teams are managed in an identity platform that speaks SCIM 2.0 and the
engine should follow it: candidate groups of user tasks, task assignment, authorizations granted to
groups, and the Cockpit/Tasklist user and group pickers all read the SCIM service on demand. Nothing
is copied into the engine's tables, so a leaver or a group change is effective as soon as the
server reports it (and the cache, 60 seconds by default, expires).

Not for:

- creating users, groups or memberships through the engine: the provider is read-only (`identityService.saveUser` fails with `UnsupportedOperationException`);
- tenants: SCIM has none, the provider reports no tenants (`createTenantQuery()` is always empty, tenant criteria on user and group queries fail with `BadUserRequestException`);
- verifying passwords with SCIM: the protocol has no credential check, see [Password check](#password-check);
- LDAP or Active Directory servers (use the engine's LDAP plugin) and SSO login (use the Spring Security / OIDC integration, which can run next to this plugin for authorization).

### How queries are answered

| Engine query | SCIM request |
|---|---|
| users by id, name, email (exact or like), `userIdIn` | `GET /Users?filter=…&startIndex=…&count=…` |
| users, ordered by one attribute | the same with `sortBy`/`sortOrder` |
| user count | the same with `count=1`, the answer's `totalResults` |
| groups by id, name, `groupIdIn` | `GET /Groups?filter=…` |
| groups of a user (`groupMember`) | `GET /Groups?filter=members[value eq "<scim id>"]` (default), or the user's `groups` claim (`membership-source: user-groups`) |
| members of a group (`memberOfGroup`) | `GET /Groups/<id>`, then `GET /Users/<member id>` per member; the other criteria and the ordering are applied by the plugin |

Like patterns: `%x%` (co), `x%` (sw), `%x` (ew), `x` (eq), `%` (pr); a `%` inside the pattern fails
with `BadUserRequestException`. Queries ordered by more than one attribute, and queries when the
engine's authorization is on, read the matching resources completely and order, filter and page them
in the plugin (SCIM sorts by one attribute, and paging must follow the authorization filter).

Not supported, and failing with a `BadUserRequestException` instead of returning something wrong:
native user queries, `potentialStarter` and tenant criteria.

## Installation

**Spring Boot** (Operaton Spring Boot starter 2.x):

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-identity-scim-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

and set at least `operaton.extensions.plugin.identity-scim.base-url` and the credentials. The
starter registers the plugin as a `ProcessEnginePlugin` bean; invalid configuration fails the
application at startup.

**Plain engine**: add `operaton-plugin-identity-scim` and register the plugin:

```java
ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
plugin.setBaseUrl("https://scim.example.com/v2");
plugin.setBearerToken(System.getenv("SCIM_TOKEN"));
configuration.getProcessEnginePlugins().add(plugin);
```

or in `operaton.cfg.xml`:

```xml
<property name="processEnginePlugins"><list>
  <bean class="org.operaton.extensions.plugin.identityscim.ScimIdentityProviderPlugin">
    <property name="baseUrl" value="https://scim.example.com/v2"/>
    <property name="bearerToken" value="${scim.token}"/>
  </bean>
</list></property>
```

**Shared engine / distribution**: put the jar into the container's `lib/` and add the plugin class
and its simple-typed properties to the `process-engine` element of `bpm-platform.xml`. Jackson comes
from the engine (the dependency is `provided`); there are no other dependencies. Use one plugin
instance per engine.

## Usage

Once registered, the engine's identity service reads from SCIM:

```java
identityService.createUserQuery().memberOfGroup("approvers").list();
identityService.createGroupQuery().groupMember("alice").list();
```

```xml
<userTask id="approve" name="Approve expense" operaton:candidateGroups="approvers"/>
```

`approvers` is the group id: by default the SCIM `displayName` of the group. Whoever the server lists
as a member of that group is a candidate; `taskService.createTaskQuery().taskCandidateUser("alice")`
resolves it. Authorizations can be granted to the group ids as usual and apply to the members; set
`operaton-admin` (the engine's administrator group) as a SCIM group for administrators (register
`AdministratorAuthorizationPlugin` so it gets its authorizations, as the example does).

### Authorization of directory reads

With the engine's authorization enabled and a user authenticated, user queries return the
authenticated user itself plus every user the user has the READ permission on (resource `USER`);
group queries return the groups with READ permission (resource `GROUP`). Counts and pages follow
this filter. Set `authorization-check-enabled: false` to make the directory readable to everyone.

### Password check

`identityService.checkPassword` (used by the webapps' login) is answered by `password-check`:

- `unsupported` (default): throws an `IdentityProviderException` saying so. SCIM defines no credential check, so nothing is guessed.
- `oauth-password`: verifies the password at an OAuth 2.0 token endpoint with the resource owner password credentials grant (RFC 6749, 4.3). The user must exist in SCIM first; the SCIM user id is sent as `username`. A `200` answer means valid; `invalid_grant` (status 400 or 401) means wrong; any other answer (a misconfigured client, a server error, no connection) throws. Passwords are never logged, cached or stored. The grant is deprecated by OAuth 2.1 and unsupported by some identity providers: prefer SSO.

The source plugin's optional `/Users/.search` password filter is not ported: it sends the password to the SCIM server in a non-standard request.

### Failures

Every unexpected answer fails the query with an `IdentityProviderException` that names the request
and the server's `detail`: a status other than 200 (401, 403, 404, 429, 5xx), a body that is not a JSON
object, a list response without `totalResults`, a user without a value for the id attribute,
ambiguous ids, timeouts, connection errors. Nothing is retried except one renewal of an expired OAuth2
token. Where the failure happens inside a task query's parameters the engine wraps it in a
`ProcessEnginePersistenceException`; the `IdentityProviderException` is its root cause.
A member or group reference that points to a resource the server no longer has (404) is skipped with a
`WARN` log.

## Configuration

Plugin properties (setters on `ScimIdentityProviderPlugin`; enumerations as strings, case-insensitive,
`-` or `_`). Spring Boot: `operaton.extensions.plugin.identity-scim.<name>` in kebab-case; durations
(`connect-timeout`, `request-timeout`, `cache.ttl`) as `10s`; the groups below are nested (`oauth2.token-url`,
`user.id-attribute`, `group.base-filter`, `cache.max-entries`, `password-check.mode`, …). Secrets are never defaulted and never logged.

| Plugin property (Spring Boot) | Default | Meaning |
|---|---|---|
| `baseUrl` (`base-url`) | required | Base URL of the SCIM service, e.g. `https://scim.example.com/v2`. A warning is logged for `http` with credentials. |
| `usersEndpoint`, `groupsEndpoint` (`users-endpoint`, `groups-endpoint`) | `/Users`, `/Groups` | Resource paths below the base URL. |
| `authenticationType` (`authentication-type`) | `bearer` | `none`, `bearer`, `basic` or `oauth2` (client credentials). |
| `bearerToken` (`bearer-token`) | | Token for `bearer`. |
| `username`, `password` | | Service account for `basic`. |
| `oauth2TokenUrl`, `oauth2ClientId`, `oauth2ClientSecret`, `oauth2Scope` (`oauth2.token-url`, …) | | Client credentials for `oauth2`. The token is fetched on first use, reused, renewed 30 s (at most half its lifetime) before it expires, and once after a 401. |
| `userIdAttribute` (`user.id-attribute`) | `userName` | SCIM attribute that becomes the user id. |
| `userFirstNameAttribute`, `userLastNameAttribute` | `name.givenName`, `name.familyName` | First and last name. |
| `userEmailAttribute` | `emails.value` | Email. Multi-valued attributes on the path select the `primary` element, else `type=work`, else the first; `emails[type eq "work"].value` selects by a sub-attribute (filters are rewritten to `emails[type eq "work" and value eq "…"]`). Dotted URNs of extension schemas are not supported in paths. |
| `userGroupsAttribute` (`user.groups-attribute`) | `groups` | The user's group claim, for `membership-source: user-groups`. |
| `userBaseFilter` (`user.base-filter`) | none | SCIM filter every user request is restricted to, e.g. `active eq true`. |
| `groupIdAttribute` | `displayName` | SCIM attribute that becomes the group id (the value used in `candidateGroups` and authorizations). A group without a value for it fails the query. |
| `groupNameAttribute` | `displayName` | Group name. |
| `groupMembersAttribute` | `members` | Group attribute that lists members (`value`, optional `type`; only `User` members count). |
| `groupBaseFilter` (`group.base-filter`) | none | SCIM filter every group request is restricted to. |
| `membershipSource` (`membership-source`) | `group-members` | `group-members`: filter groups by `members[value eq "<id>"]`; `user-groups`: read the user's `groups` claim and fetch each group (for servers that cannot filter by member, e.g. Entra ID). |
| `pageSize` (`page-size`) | `100` | `count` per request when walking result sets. Servers that return fewer resources are followed. |
| `connectTimeoutMillis`, `requestTimeoutMillis` (`connect-timeout`, `request-timeout`) | 10 s, 30 s | HTTP timeouts. |
| `cacheTtlMillis` (`cache.ttl`) | 60 s | Time to live of cached GET responses; `0` disables the cache. |
| `cacheMaxEntries` (`cache.max-entries`) | `500` | Maximum cached responses; the oldest is evicted. |
| `authorizationCheckEnabled` (`authorization-check-enabled`) | `true` | Filter user and group queries by READ permission when the engine's authorization is on. |
| `customHeaders` (`custom-headers.<name>`) | none | Extra headers on every SCIM request. `Authorization` is rejected unless `authenticationType=none`. |
| `passwordCheck` (`password-check.mode`) | `unsupported` | `unsupported` or `oauth-password`. |
| `passwordTokenUrl`, `passwordClientId`, `passwordClientSecret`, `passwordScope` (`password-check.*`) | | Token endpoint and client of `oauth-password` (token URL and client id required). |
| (`enabled`) | `true` | Spring Boot only: register the plugin. |

### Mapping to other servers

| Server | Typical settings |
|---|---|
| Keycloak (SCIM extension) | defaults |
| Okta | defaults; bearer token of an API service app |
| Microsoft Entra ID | `membership-source: user-groups` if the groups endpoint cannot filter by member; `authentication-type: oauth2` |

These are starting points, not verified against the products: the tests run against a SCIM stub
(in-JVM and WireMock), not against Entra ID, Okta or Keycloak.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.0 | 2.0.x (latest patch) |
| 2.1 | 2.1.x (latest patch) |
| 2.2 | 2.2.x (latest patch) |

## Example

[`example/`](example/): an expense approval process whose approvers and finance clerks come from a
SCIM server (a WireMock container with mappings). A user task is assigned to the SCIM group
`approvers`, the SCIM group `operaton-admin` administers the engine, and Cockpit/Tasklist login checks
passwords at a stand-in token endpoint. Run it with `docker compose up -d --wait`, `./mvnw spring-boot:run`;
`./mvnw verify` runs it end to end with Testcontainers.

## Development

```bash
./mvnw -B -pl plugins/identity-scim -amd verify          # whole extension incl. the example (Docker needed)
./mvnw -B -pl plugins/identity-scim/operaton-plugin-identity-scim -am verify -Doperaton.version=2.1.6
```

On Colima set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

### Tests

- Unit tests of the filter translation, attribute paths, cache, HTTP client (authentication types, errors, timeouts, caching, OAuth2 renewal) and directory (paging, filters, membership strategies) against `FakeScimServer`, a SCIM server in the test JVM that evaluates real SCIM filters, so a malformed filter fails the test.
- Engine tests with `ProcessEngineExtension` and H2: user and group queries, task candidates, read-only behaviour, unsupported queries, authorization (READ grants to SCIM users and groups, administrator group), OAuth2 access with `user-groups` membership and the password check.
- `ScimWireMockIT`: the same plugin against the `wiremock/wiremock` container, asserting path, filter, paging and header on the wire.

### Design decisions (best guesses)

- **Port, not a copy.** The source ([cibseven engine-plugins/identity-scim](https://github.com/cibseven/cibseven/tree/main/engine-plugins/identity-scim), Apache-2.0) was adapted to Operaton packages and APIs; ported files keep the CIB header with a "Ported from" line and a list of changes, see [NOTICE](NOTICE). Changed on purpose: the JDK `HttpClient` replaces the shaded Apache HttpClient 5; one client and cache per plugin instead of per session (the original opened a connection pool per command); authorization filtering happens before paging so pages and counts agree; the other criteria of a member-of-group query are applied (the original ignored them); a missing id attribute and every malformed answer fail instead of falling back or being logged; `checkPassword` no longer answers `true` for every existing user when authentication is off; the write mode, basic-auth-only password protocol (`/Users/.search`), the trust-all TLS option and the cached password hash are not ported (use the JVM trust store for private CAs).
- **Group id default `displayName`** (the source used `externalId`, which most servers leave empty); so `candidateGroups="approvers"` works with names.
- **No retry/backoff** for 429 and 5xx: they fail the engine command loudly; put a gateway in front if you need it.
- **Group-claim mapping** means the choice of membership source and the claim attribute; renaming groups (SCIM name to a different engine group id) is done with `group-id-attribute`.
- **Tenants** are reported as none rather than failing, because the engine asks for them on authentication; tenant criteria fail.
- **Group members** are resolved with one request per member (cached); very large groups are slow when listed as users.

### Known limitations

- Read-only; no tenants; no native or `potentialStarter` queries; one engine per plugin instance.
- SCIM servers differ: the plugin needs `filter` on users and groups, `startIndex`/`count`, `totalResults`, and (for `group-members`) a filter on `members[value eq …]`. Sorting is optional on the server side; `sortBy` is only sent for a single ordering attribute.
- Not verified against Entra ID, Okta or Keycloak themselves, only against the stubs.
