# AI Agent Context

Single source of truth for any AI coding agent working in this repository.
Provider-specific files redirect here; do not duplicate context into them.

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

- **Do not implement `fragment-override` yet.** Two configuration routes must
  be ruled out first — see the README. Writing it before that risks
  maintaining Java that a portal property makes redundant.
- **Rule out configuration before writing any module.** Feature flags and
  undocumented supported paths are common; a property is cheaper than a bundle
  for everyone, permanently. Record what was ruled out and how.
- **`Import-Package` ranges are the real risk.** Consumers run a wide span of
  Liferay versions and a bundle compiled against one `com.liferay.*` API
  version may not resolve against another. Never ship whatever bnd infers.
- **One artifact vs one per Liferay line is UNDECIDED** — see README. Do not
  build per-tag pipelines pre-emptively; the question is empirical (do the
  exported package versions actually change across the supported span?) and no
  module imports `com.liferay.*` yet, so there is no evidence either way.
  Settle it with a resolution matrix, not by assumption.
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

---
*Last Updated: 2026-09-04* | *Last Reviewed: 2026-09-04*
