# Contributing an extension

1. **Check the catalog.** Look in [the catalog](../catalog.md), including "Proposed / backlog", and
   open issues. Extend an existing extension rather than duplicating it. For a new one, open an
   issue first (or comment on the backlog entry) to agree on name and scope.
2. **Check the sources' licenses** if you intend to reuse or port anything
   ([licensing-and-attribution.md](licensing-and-attribution.md)).
3. **Create it:**
   ```bash
   tools/new-extension.sh <plugin|connector> <name>
   tools/new-extension.sh client <language>      # js | python | go | rust
   ```
   This creates `<kinds>/<name>/` (or `clients/<language>/`) from `tools/extension-template/` ([module-structure.md](module-structure.md)).
4. **Fill the manifest** `extension.yml`: `title`, `description`, `since`, `compatibility` (start
   with the minors you can verify), `status`; keep the POM `<name>` and `<description>` in line.
5. **Implement** following [coding.md](coding.md) (clients: [clients.md](clients.md)). Unimplemented parts fail loudly (Java: `UnsupportedOperationException("not implemented: …")`).
6. **Test**: unit and engine tests, Testcontainers for external systems. Make sure every declared
   minor passes ([compatibility.md](compatibility.md)). Format and verify with the extension's own tooling (Java: `./mvnw spotless:apply`,
   `./mvnw -B -pl <kinds>/<name> -amd verify`; clients: [clients.md](clients.md)).
7. **Document**: the README with all required sections ([documentation.md](documentation.md)), plus
   `docs/` where useful.
8. **Example**: a runnable `example/` with Testcontainers ITs ([examples.md](examples.md)).
9. **Catalog entry**: run `tools/generate-catalog.py` (the tables of [docs/catalog.md](../catalog.md) are
   generated from `extension.yml`) and remove the backlog entry, if any.
10. **Open the PR** against `develop` from a `feature/*` branch. Title in Conventional Commit form,
    for example `feat(connector-kafka): add Kafka connector` or `feat(client-go): add extendLock`. State sources and licenses of
    reused code. Wait for the `build-result` check; address review comments.

Releasing happens after merge, by maintainers ([versioning-and-release.md](versioning-and-release.md)).
