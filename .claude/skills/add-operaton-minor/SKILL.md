---
name: add-operaton-minor
description: Roll a new Operaton minor version (e.g. 2.3) through the compatibility matrix — add the compatibility pin, then verify each extension against it and add the minor to every extension that passes, fixing or recording the ones that do not. Use when a new Operaton minor is released, when the compatibility-new-minor workflow opened its PR, or when asked to "support Operaton X.Y".
---

# add-operaton-minor

Rules: `docs/conventions/compatibility.md`. A version that is not verified is never claimed.

## Steps

1. **Pin.** `compatibility/operaton-<minor>/pom.xml` exists (the weekly workflow's PR, or copy
   the newest folder and set `operaton.version` to the newest `<minor>.x` on Maven Central).
2. **Default version.** If the minor is the newest, raise `operaton.version` in
   `parent/pom.xml` (and `spring-boot.version` to the Spring Boot that Operaton minor uses: read
   `version.spring-boot` in operaton/operaton's root `pom.xml` at the release tag) and the image
   tag in client examples.
3. **Verify every extension** (parallel subagents, one per extension, each with the scoped
   command only):
   `./mvnw -B -pl <ext>/operaton-<kind>-<name> -am verify -Doperaton.version=<pin>`; clients run
   their integration tests with `OPERATON_VERSION=<pin>`.
4. **Per extension:** passes → add the minor to `compatibility` in `extension.yml` and the README
   table. Fails → fix it when the fix is small and keeps older minors green; otherwise leave the
   minor out and open an issue with the failure (after the user approves, per CLAUDE.md #2).
5. **PR to develop** per extension (or one PR for the pin plus all passing extensions), listing
   the result for every extension.
