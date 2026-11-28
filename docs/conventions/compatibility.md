# Compatibility

An extension claims only the Operaton versions it has been verified against. The mechanism is
specified in [repository-design.md](repository-design.md#compatibility-verification); this
document is the how-to.

## Scope

Operaton **2.x** is supported, starting at 2.0. Operaton 1.x is out of scope: there are no pins, no
matrix entries and no claims for it, and new extensions are not tried against it.

## Declaring

The `compatibility` list in the extension's `extension.yml` names the supported Operaton
**minors** (`[2.0, 2.1, 2.2]`); `since` is the first minor the extension was built for. The
structure check rejects a malformed list. The README's compatibility table is generated from the
manifest ([documentation.md](documentation.md#compatibility)).

## Verifying

CI builds one job per minor in the list. Java extensions are built against the patch pinned in
`compatibility/operaton-<minor>/pom.xml` (the script `.github/scripts/compat-matrix.sh` produces the
matrix). Clients run their integration tests against the real engine image
`operaton/operaton:<version>` (`OPERATON_VERSION`, `OPERATON_URL`, see [clients.md](clients.md)).
To reproduce a Java job locally (Testcontainers needs Docker; on Colima set
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`):

```bash
./mvnw -B -pl <kinds>/<name>/operaton-<kind>-<name> -am verify -Doperaton.version=<x.y.z>
```

Dependabot keeps each pinned POM on the newest patch of its minor.

## Adding a new Operaton minor

1. The weekly workflow opens a PR adding `compatibility/operaton-<minor>/pom.xml`; review and merge
   it. (By hand: copy the previous folder, set the version, add the module to the root POM.)
2. For each extension that should support it, in its own PR: add the minor to
   `compatibility` in `extension.yml`, fix what breaks, and let CI verify. Supporting a new minor is a
   deliberate change per extension; it is never added in bulk without a green run.
3. If the newest minor is now the highest supported, `operaton.version` in the parent POM moves
   to its latest patch ([repository-design.md](repository-design.md#build)).

## When a minor fails

Fix the extension, or remove the minor from `compatibility` in `extension.yml` in the same PR; never
merge a red matrix entry and never keep an unverified claim. Removing a minor changes what users
can rely on: use a `feat!`/`fix!` title and say so in the PR when a released claim is dropped.
Prefer a fix that works across minors over version-specific branches in the code. If a failure comes
from a new patch of Operaton (a Dependabot PR on `compatibility/*`), report it upstream and
treat it as a failing minor until resolved.
