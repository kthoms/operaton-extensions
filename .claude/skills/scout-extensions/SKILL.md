---
name: scout-extensions
description: Identify and propose new Operaton extensions (plugins, connectors, clients) worth building next, from Camunda 7 community extensions, CIB seven, n8n integration categories, Operaton issues and user questions — with license check and a ranked proposal written to the catalog backlog. Use when asked "what should we build next", "is there an extension for X" (and there is none), or periodically to grow the catalog.
---

# scout-extensions

Goal over time: "Is there an extension for …?" is answered with yes.

## Steps

1. **Read the current state:** `docs/catalog.md` (built, in progress, backlog) and the open
   issues labelled `extension-proposal` (`gh issue list --label extension-proposal`).
2. **Collect signals** (read-only, parallel subagents where useful):
   - Operaton issues and discussions asking for integrations (`gh search issues --repo operaton/operaton <term>`).
   - camunda-community-hub and camunda org repositories for Camunda 7 (stars, last push, license).
   - cibseven/cibseven modules (`connect/`, `engine-plugins/`).
   - n8n integration categories and usage ranking (n8n.io/integrations) — ideas only, the
     Sustainable Use License forbids code reuse.
   - What Operaton core already ships (never duplicate it).
3. **Judge each candidate:** demand, fit for a BPMN engine (server-side, transactional or
   async), self-contained (one module, few dependencies), testable (embedded engine,
   Testcontainers), example feasible, license (port vs. fresh).
4. **Propose:** a ranked list of the top candidates with one line each (what, why now, source
   and license, port or fresh, test approach). Add them to the backlog table in
   `docs/catalog.md` in a PR to develop. When the user asked for autonomy, continue with the
   `new-extension` skill for the top candidates; otherwise open one issue per candidate
   (label `extension-proposal`) after the user approves.
