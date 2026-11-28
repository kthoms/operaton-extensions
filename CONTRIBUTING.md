# Contributing

Thank you for contributing. The full rules are in [docs/conventions](docs/conventions/README.md);
for a new extension follow [contributing-an-extension.md](docs/conventions/contributing-an-extension.md).

## Workflow

- Branch from `develop` as `feature/<topic>`; open the pull request against `develop`.
  Never push to `develop`, `main` or `release/**`.
- Use [Conventional Commits](https://www.conventionalcommits.org) for commits and the PR title,
  with the extension as scope: `feat(connector-kafka): add SASL support`.
- For Java extensions run `./mvnw spotless:apply` and `./mvnw -B verify`; for clients use the language's
  tooling ([clients.md](docs/conventions/clients.md)). Run it (or build the one extension, see the
  [README](README.md#build)) before pushing.
- The `build-result` check must be green: it covers tests per supported Operaton minor, the
  example's integration tests (Docker required), formatting and the structure check.
- State the source and license of any code not written for this repository
  ([licensing-and-attribution.md](docs/conventions/licensing-and-attribution.md)).
- Do not edit versions or `CHANGELOG.md`; releases are done by maintainers
  ([versioning-and-release.md](docs/conventions/versioning-and-release.md)).

If a rule is in your way, change the convention first, in its own PR, then the code.

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
