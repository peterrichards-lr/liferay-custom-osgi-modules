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

<!-- markdownlint-disable MD049 -->
---
*Last Updated: 2026-09-04* | *Last Reviewed: 2026-09-04*
