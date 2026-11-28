# GitHub workflows

The CI and release automation of this repository. The design they implement is
[docs/conventions/repository-design.md](../../docs/conventions/repository-design.md); change it
first, then the workflows. The logic lives in [`.github/scripts/`](../scripts) so it can be run and
tested locally; the workflows mostly wire the scripts together.

All workflows pin actions by commit SHA, start with `permissions: {}` and grant each job only what
it needs, check out with `persist-credentials: false` (pushes go through
`.github/scripts/git-remote.sh` with the token passed per command), and pass every `${{ }}` value
into `run:` blocks through `env:`.

## Workflows

### Build (`build.yml`)

- **Triggers**: pull requests to and pushes on `develop`, `main`, `release/**`; manual dispatch
  (builds every extension). A newer run for the same PR or branch cancels the older one.
- **Jobs**:
  - `changes`: `changed-extensions.sh <base> HEAD` lists the changed extensions (all of them when
    `parent/`, `compatibility/`, the root `pom.xml`, the Maven wrapper, `build.yml` or
    `.github/scripts/` changed); `compat-matrix.sh` and `extension-matrix.sh` turn the list into
    the two matrices below. The step summary shows both.
  - `compat` (one job per extension and Operaton minor of its `extension.yml` `compatibility`):
    - Java: `./mvnw -pl <artifact modules> -am verify -Doperaton.version=<pinned patch>`.
    - Clients: integration tests only, against the Docker image `operaton/operaton:<pinned patch>`
      (`operaton-engine.sh`, env `OPERATON_VERSION`, `OPERATON_URL`).
  - `full` (one job per changed extension, default Operaton version): `check-structure.sh`, then
    - Java: `./mvnw -pl <kinds>/<name> -am -amd verify` (incl. `example/` and its
      Testcontainers ITs);
    - clients (`client-build.sh`): install, lint, unit tests, integration tests against the
      newest declared minor, package. Toolchains: Node.js 22 (npm), Python 3.12 (python), Go
      stable (go), Rust stable via the preinstalled `rustup` (cargo).
  - `build-result`: **the single required status check**. Green when every job succeeded or was
    skipped (nothing changed), red when any failed or was cancelled.

### Label PR (`label-pr.yml`)

- **Trigger**: `pull_request_target` (opened, synchronize, reopened, edited). It only sparse-checks
  out `.github/labels` of the base branch, never the PR head.
- Adds labels by path ([`labels/gh-labeler.yml`](../labels/gh-labeler.yml): `plugin`, `connector`,
  `client`, `compatibility`, `build`, `ci`, `docs`; synced) and by Conventional Commits title
  ([`labels/pr-labeler.yml`](../labels/pr-labeler.yml): the same plus `dependencies` and
  `breaking`; only added).

### Check GitHub Workflow Security (`zizmor-check.yml`)

- **Triggers**: pushes on and PRs to `develop`, `main`, `release/**` that change a workflow or
  `dependabot.yml`. Runs [zizmor](https://docs.zizmor.sh) (persona `regular`).

### Auto-Merge Eligible PRs (`auto-merge.yml`)

- **Triggers**: every three hours; manual dispatch.
- Squash-merges open PRs that match a rule of [`auto-merge.config.yml`](../auto-merge.config.yml)
  once every check is green (and `build-result` has run), the merge state is clean and no review
  thread is open; approves them first. Asks Dependabot to recreate conflicting PRs. Only PRs to
  `develop` or `release/**` are ever merged, never `main`. Current rules: Dependabot Maven and
  GitHub Actions patch/minor updates on `develop`, Dependabot patch updates on `release/**`, and
  PRs by `kthoms` labelled `auto-merge` on `develop`.

### Release (`release.yml`): minor releases

- **Triggers**: push to `main` (merging `develop` into `main`); manual dispatch on `main` with
  `dry_run` (default `true`) and an optional `extensions` override (paths, comma- or
  space-separated).
- **Jobs**:
  - `detect`: `release-info.sh extensions-to-release`: every extension whose folder differs from
    its last `x.y.0` tag (ignoring `CHANGELOG.md` and the release/`-SNAPSHOT` version bumps); an
    extension without a tag is released. `release-info.sh plan` computes version, tag and branches.
  - `release`: one `release-extension.yml` call per extension (matrix, `ref: main`).
  - `develop`: for every extension whose tag now exists (so also after partial failures): set
    the next minor development version, take `CHANGELOG.md` from the tag, append the Dependabot
    entry of the new release branch (`dependabot-release-entry.sh`), and push one commit
    `chore(release): prepare next development versions` to `develop`. Not run in a dry run.

### Release check (`release-check.yml`): the release as a dry run on every pull request to `main`

Merging `develop` into `main` releases, so the pull request proves the release is ready first. The
check performs the whole release on the *result of the merge* and stops only before the remote
writes (pushing tags and branches, publishing, creating GitHub releases, pushing to `develop`).

- **Triggers**: pull requests to `main` (also drafts: the check is the reason to open one early);
  manual dispatch on any branch with an optional `extensions` override.
- **Jobs**:
  - `source` (**Source branch**): the pull request must come from `develop` of this repository.
  - `detect`: checks out `refs/pull/<n>/merge`, lists the extensions the merge would release
    (`release-info.sh extensions-to-release`) and their plans, as `release.yml` does.
  - `release` (**Dry run**): one `release-extension.yml` call per extension with `dry_run: true`,
    `check: true` and `source_ref` set to the merge result. Check mode signs with a throwaway GPG key
    and uses placeholder Maven Central credentials, so JReleaser validates the whole Java release
    (changelog, checksums, signing, Maven Central POM and file rules, GitHub release) without any
    secret; clients run their install, lint, unit and package stages, `npm publish --dry-run` and
    `cargo publish --dry-run`. Version, tag and release branch are made locally.
  - `prepare-develop`: the commit the real release makes on `develop` (next versions, changelogs,
    Dependabot entries of the release branches), made locally with `prepare-develop.sh`.
  - `release-check` (**Release check**): the one result to require.
- **Required checks on `main`**: `Source branch` and `Release check` (plus `build-result`).

### Release extension (`release-extension.yml`): reusable

- **Trigger**: `workflow_call` with `extension`, `ref` (`main` or the extension's
  `release/<kinds>/<name>/<x>.<y>.x`) and `dry_run`; the publishing secrets are passed explicitly.
- **Outputs**: `version`, `tag`, `released`, `release_branch`, `next_develop_version`.
- **Jobs**:
  - `release`: plan, dry-run decision, set the release version and write the changelog
    (`release-git.sh prepare`, `changelog.sh`), build and test (Java: `-Prelease deploy` into
    `<ext>/target/staging-deploy`; clients: `client-build.sh`), commit on a detached HEAD and tag
    (`release-git.sh commit-tag`), push the tag. Java: JReleaser `full-release` (GitHub release +
    Maven Central, signed) with [`jreleaser/jreleaser.yml`](../jreleaser/jreleaser.yml).
  - `publish-npm` (`npm publish --provenance --access public`), `publish-pypi` (trusted publishing,
    environment `pypi`), `publish-cargo` (`cargo publish`); Go needs only the tag.
  - `github-release` (clients): JReleaser `release` with
    [`jreleaser/jreleaser-client.yml`](../jreleaser/jreleaser-client.yml).
  - `finalize`: create the release branch from the tag (minor release) or advance it (patch
    release) to the next patch development version (`release-git.sh bump`) and push it.
- **Versions**: Maven/npm/cargo hold `x.y.z-SNAPSHOT`, Python `x.y.z.dev0` (PEP 440) between
  releases; the release version drops the suffix. Go has no version file: on `main` it is the next
  minor of the last tag (`0.1.0` for the first release, like the template), on a release branch the next patch.
- **Tags**: `<kinds>/<name>/v<version>`; release branches `release/<kinds>/<name>/<x>.<y>.x`.

### Release patches (`release-patches.yml`)

- **Triggers**: monthly (`0 4 1 * *`); manual dispatch with `dry_run` (default `true`) and an
  optional `branches` override.
- `detect`: `release-info.sh patch-candidates`: every `release/**` branch with changes since the
  last tag of its line; `release`: one `release-extension.yml` call per branch.
- The release branch's own `.github/scripts` run (they are checked out with the branch).

### Compatibility - new Operaton minor (`compatibility-new-minor.yml`)

- **Triggers**: weekly (Monday 05:00 UTC); manual dispatch.
- Reads `org/operaton/bpm/operaton-bom/maven-metadata.xml` from Maven Central; for every minor
  newer than the newest `compatibility/operaton-<minor>/` folder, `compat-new-minor.sh --create`
  copies that folder with the latest final patch, and a PR to `develop` is opened with `gh`.

## Dry runs

Every release workflow has a `dry_run` input. A release also runs as a dry run when a secret its
extension needs is missing (Java: the GPG and Maven Central secrets; npm: `NPM_TOKEN`; cargo:
`CARGO_REGISTRY_TOKEN`; Python and Go need none). A dry run builds, tests, writes the changelog and
makes the release commit, tag and branch locally, runs `npm publish --dry-run` /
`cargo publish --dry-run` / JReleaser with `JRELEASER_DRY_RUN=true` (Java only when the secrets
are present), and pushes and publishes nothing.

## Secrets and settings

| Secret | Used for |
|---|---|
| `GPG_PUBLIC_KEY`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` | signing the Maven Central deployment (armored keys) |
| `MAVENCENTRAL_USERNAME`, `MAVENCENTRAL_PASSWORD` | Maven Central Portal user token |
| `NPM_TOKEN` | publishing the JS client (`@operaton-extensions/external-task-client`) |
| `CARGO_REGISTRY_TOKEN` | publishing the Rust client to crates.io |
| — | PyPI uses trusted publishing: register this repository, workflow `release-extension.yml` and environment `pypi` as trusted publisher of `operaton-external-task-client` |

Repository settings the workflows rely on:

- Branch protection on `develop`, `main`, `release/**`: require the `build-result` check; on `main` also
  `Release check` and `Source branch`, and require that the pull request comes from `develop`.
- The release workflows push with the `GITHUB_TOKEN` to `develop` (next versions) and to
  `release/**` (release branches) and push tags: allow GitHub Actions to bypass the protection of
  these branches (or replace the token with a GitHub App token).
- "Allow GitHub Actions to create and approve pull requests" (auto-merge approvals,
  compatibility PRs).
- Events caused by the `GITHUB_TOKEN` do not start workflows: the compatibility PR needs a
  close/reopen to build, and commits merged by `auto-merge.yml` do not trigger a push build.
- npm provenance requires `repository` in `clients/js/package.json` to point at this repository.

## Scripts

| Script | Purpose |
|---|---|
| `changed-extensions.sh <base> <head>` | JSON array of changed extension paths |
| `compat-matrix.sh '<json>'` | compatibility matrix (`extension`, `build`, `minor`, `operaton_version`, `modules`) |
| `extension-matrix.sh '<json>'` | full-build matrix (`extension`, `build`, `operaton_version`) |
| `check-structure.sh <ext>` | layout, manifest, README headings, example, NOTICE, tests |
| `release-info.sh <command>` | version arithmetic, tags, release branches, release detection, release plan |
| `changelog.sh <ext> <prev-tag> <version> [<notes>]` | Conventional-Commits changelog |
| `set-version.sh <ext> <version>` | set the version in the extension's build file(s) |
| `release-git.sh prepare\|commit-tag\|bump` | the local git steps of a release |
| `prepare-develop.sh '<plans>'` | the commit a minor release makes on develop (used by `release.yml` and the release check) |
| `mvn-retry.sh <maven args>` | the Maven wrapper, run again only when dependencies could not be downloaded |
| `dependabot-release-entry.sh <ext> <branch>` | Dependabot entry of a release branch |
| `compat-new-minor.sh <maven-metadata.xml> [--create]` | new Operaton minors and their folders |
| `client-build.sh <ext> <stage>` | install / lint / unit / integration / package of a client |
| `operaton-engine.sh start <version>\|stop` | Operaton Docker container for client integration tests |
| `jreleaser-config.sh <template> <ext> <out>` | render a JReleaser configuration for one extension |
| `git-remote.sh push\|fetch <args>` | authenticated push/fetch with `GH_TOKEN` |
| `ext.py` | shared helpers: manifest, POM and version file reading and writing |

The scripts need `bash`, `git`, `jq` and `python3` (standard library only) and run on macOS as
well, e.g. `./.github/scripts/check-structure.sh connectors/kafka` or
`./.github/scripts/release-info.sh extensions-to-release`.
