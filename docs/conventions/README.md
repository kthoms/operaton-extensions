# Conventions

Central rules for this repository, which holds three kinds of extension: plugins, connectors
(Java) and clients (external task clients in other languages). [repository-design.md](repository-design.md) is the source of
truth for layout, build, compatibility verification, branches, CI and release; the other documents
add the rules a contributor needs for one topic and link to the spec instead of repeating it.

| Document | Content |
|---|---|
| [repository-design.md](repository-design.md) | Layout, build, compatibility verification, branches, CI, release, Dependabot, workflow security (the spec) |
| [module-structure.md](module-structure.md) | Per-extension layout, naming, manifest, self-containment, what is released |
| [coding.md](coding.md) | Java (plugins, connectors): formatting, logging, error handling, configuration, concurrency, tests, license headers |
| [clients.md](clients.md) | External task clients: per-language tooling, common API and feature set, tests, publishing, ports |
| [documentation.md](documentation.md) | Required README sections, further docs, catalog entry rule |
| [examples.md](examples.md) | The `example/` project standard |
| [compatibility.md](compatibility.md) | Declaring and verifying supported Operaton minors, adding a minor, handling failures |
| [licensing-and-attribution.md](licensing-and-attribution.md) | Allowed licenses, ports, NOTICE entries |
| [versioning-and-release.md](versioning-and-release.md) | SemVer per extension, tags, branches, release flow, rules for contributors and agents |
| [contributing-an-extension.md](contributing-an-extension.md) | Step-by-step guide for a new extension |

## Rule for changing conventions

**Change the convention first, then the code.** A pull request that needs a rule to be different
changes the convention document in the same pull request (or an earlier one) and states why.
Code, templates, scripts and workflows follow the documents, never the other way round.
