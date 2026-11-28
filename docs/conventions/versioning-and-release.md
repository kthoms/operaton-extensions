# Versioning and release

The mechanics are specified in [repository-design.md](repository-design.md#release); this document
covers what contributors need to know.

## Versions

- Each extension has its own [SemVer](https://semver.org) version, held by its aggregator POM
  (shared by its modules) or, for clients, by `package.json`, `pyproject.toml` or `Cargo.toml`.
  A Go client has no version file: its version is the tag.
- `develop` always holds the next minor as `-SNAPSHOT`; a release branch holds the next patch.
  Contributors never change versions by hand.
- Breaking changes (public API, configuration properties, dropping a supported Operaton minor)
  need a major version: mark them `!` in the commit and PR title.
- Tags: `<kinds>/<name>/v<version>`, for example `plugins/history-cleanup-metrics/v1.0.0` or `clients/go/v1.0.0` (the form Go requires
  for a module in a subfolder).

## Branches

| Branch | Use |
|---|---|
| `develop` | default; every feature PR targets it |
| `main` | release trigger |
| `feature/*` | one piece of work |
| `release/<kinds>/<name>/<major>.<minor>.x` | patches for one released minor |

A fix for a released version goes to `develop` first, then is cherry-picked to the release
branch with `git cherry-pick -x`.

## Releasing

- **Minor and major**: merging `develop` into `main` releases every extension changed since its last
  tag; untouched extensions are not released.
- **Registries**: Java artifacts go to Maven Central; clients to their registry (npm with
  provenance, PyPI trusted publishing, crates.io; Go needs only the tag). Secrets and details:
  [repository-design.md](repository-design.md#release).
- **Patch**: a monthly workflow (and a manual trigger) releases every release branch with new commits.
- **Dependabot**: updates on `develop` (minor and patch), on `compatibility/*` and on release
  branches (patch only). Green Dependabot PRs and eligible agent PRs are auto-merged by
  `auto-merge.yml`.
- The changelog is generated from Conventional Commits that touch the extension's folder; write
  commit subjects for readers of the changelog.

## Contributor and agent rules

Must:

- branch from `develop`, open the PR against `develop`, use a Conventional Commit title with the
  extension as scope (`feat(connector-kafka): add SASL support`, `fix(client-python): …`);
- keep each PR to one extension unless it changes shared build files;
- leave the version and `CHANGELOG.md` alone.

Must not:

- push to `develop`, `main` or `release/**` directly;
- tag, create releases, edit release workflows' inputs, or touch release secrets;
- merge `develop` into `main` or trigger release workflows.

Agents never release on their own: only a maintainer starts a release, by merging into `main` or
dispatching a release workflow.
