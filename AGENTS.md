# AI Agent Context

Single source of truth for any AI coding agent working in this repository.
Provider-specific files redirect here; do not duplicate context into them.

This file carries rules and conventions only. It holds **no status content**, so
it should not need to change as work progresses — that lives in `.agent-state.md`.

## Provider discovery

Each tool finds this file by its own convention. Those entry points exist only
to route here:

| File | Provider | Mechanism |
|---|---|---|
| [`CLAUDE.md`](./CLAUDE.md) | Claude Code | `@AGENTS.md` **import** — expanded into context at launch |
| [`GEMINI.md`](./GEMINI.md) | Gemini / Antigravity | redirect |
| `.claude/`, `.cursor/`, `.gemini/`, `.windsurf/`, `.github/` | all | blade-generated Liferay rules — see below |

**The import in `CLAUDE.md` is load-bearing.** A markdown link is prose an agent
may or may not follow; `@AGENTS.md` is expanded into context at session start, so
these rules are *loaded* rather than merely referenced. Do not downgrade it to a
link. A symlink (`ln -s AGENTS.md CLAUDE.md`) achieves the same thing, but needs
Administrator privileges or Developer Mode on Windows, which the import does not.

Do not use `@` imports for anything else here. An import loads eagerly into every
session whatever the task, so it is right for this one routing file and wrong for
everything it routes to.

## What this repo is

A Liferay Workspace for OSGi modules that work around **platform-level**
Liferay limitations affecting more than one project. Consumers are often not
JVM projects at all and so cannot build a bundle themselves. See
[README.md](./README.md), [CONTRIBUTING.md](./CONTRIBUTING.md) and
[ADOPTERS.md](./ADOPTERS.md).

## Liferay workspace guidance

`blade init` generated its own rules and copied them into `.claude/`,
`.cursor/`, `.gemini/`, `.windsurf/` and `.github/`. Those are
**upstream-maintained duplicates** of `.workspace-rules/`: refresh them with
`blade init -r`, do not hand-edit them, and do not treat divergence between
them as meaningful.

`.workspace-rules/` is the canonical copy.

## Rules

- **`fragment-override` configuration routes have been ruled out.** Investigation
  confirmed that `LPS-178052` is POST-only and `LPS-165482` does not expose runtime
  fragment overrides; upstream issue is tracked under LPD-99955. The module is
  gated behind `feature.flag.LPD-99955=true` in `portal-ext.properties`.
- **Rule out configuration before writing any module.** Feature flags and
  undocumented supported paths are common; a property is cheaper than a bundle
  for everyone, permanently. Record what was ruled out and how.
- **`Import-Package` ranges are the real risk.** Consumers run a wide span of
  Liferay versions and a bundle compiled against one `com.liferay.*` API
  version may not resolve against another. Never ship whatever bnd infers.
- **Per-DXP-line is a property of a module's imports, not the repository.** A
  bundle is a per-line artifact whenever its imported package versions change
  across targeted lines (e.g. `fragment-override` importing
  `com.liferay.fragment.service`). Modules importing only stable packages may
  span releases with a single artifact.
- **Name modules for the capability, not the consumer.** The next project to
  need one should not have to read another project's name to understand it.
- **Publishing is release-triggered, never merge-triggered.** A registry
  coordinate is immutable once consumed. `.github/workflows/publish.yml` fires
  on a published release.
- **GitHub Packages requires auth even for public packages.** Only the
  Container registry allows anonymous pulls, so release assets are the
  anonymous route and the primary one — an OSGi bundle is normally consumed as
  a file, not as a compile dependency. Do not describe the registry as though
  it were open.
- **Project-specific modules do not belong here.** They stay with their
  project; reuse happens through published artifacts.

## Current work state

In-flight task state — active objectives, checklists, blockers, handoff notes —
lives exclusively in `.agent-state.md` (gitignored). Its tracked seed is
[`.agents/templates/agent-state.md`](./.agents/templates/agent-state.md); if the
scratchpad is missing, recreate it by copying that seed.

- **On session startup**: read `.agent-state.md` to resume in-flight work
  without losing context across a provider switch.
- **During execution**: update it on progress, on hitting a blocker, or when
  pausing.
- **On completion**: clear the completed objective rather than letting it
  accumulate.

Two things must **not** go in it. Durable rules belong in this file. Anything
that has to outlive the task belongs where a future reader will look for it —
what a module ruled out before being written goes in its javadoc and README
entry, per [CONTRIBUTING.md](./CONTRIBUTING.md), and outstanding work goes in a
GitHub issue.

Do not restate shipped history there either. The scratchpad this replaced was a
completed-task log that drifted three releases stale while reading as current;
`git log` and `gh release list` are the authority, and a file that paraphrases
them is a file that will eventually contradict them.

<!-- markdownlint-disable MD049 -->
---
*Last Updated: 2026-09-14* | *Last Reviewed: 2026-09-14*
