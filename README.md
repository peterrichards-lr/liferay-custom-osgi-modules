# liferay-custom-osgi-modules

A Liferay Workspace for OSGi modules that solve Liferay platform limitations
which several projects run into independently.

## Why this repo exists

Some Liferay restrictions can only be worked around from **inside** the portal
JVM. Working around them from outside — direct SQL, Gogo shell, headless
retries — tends to produce brittle, unsupported code that bypasses cache
invalidation, model listeners and indexing.

Building a bundle needs a Liferay Workspace, and plenty of the tools that hit
these restrictions are not JVM projects at all. [Liferay Docker
Manager](https://github.com/peterrichards-lr/liferay-docker-manager) is
Python; a client extension may be TypeScript. This repo is the workspace those
projects can borrow, so a shared problem gets one shared bundle instead of one
workaround per project.

## Scope

Modules here address a **platform-level** limitation that is not specific to
any one consumer. If a module is only meaningful to a single project, it
belongs in that project.

Anyone is welcome to add a module. See [CONTRIBUTING.md](./CONTRIBUTING.md)
for what a module needs, and [ADOPTERS.md](./ADOPTERS.md) for who is using
what — add yourself there when you consume or contribute one.

## Modules

| Module | State | Solves |
|---|---|---|
| [`fragment-override`](#fragment-override) | **in development** | Headless API rejects specification updates on published site initializer pages |
| [`search-reindex`](#search-reindex) | **available** | Triggers asynchronous search reindexing for arbitrary entity classes without a published Headless or GraphQL mutation |
| [`commerce-site-type`](#commerce-site-type) | **available** | Exposes a commerce channel's B2B/B2C/B2X site type and allowed account types |

### fragment-override

The restriction is Liferay's, not any one project's:
`PUT /o/headless-admin-site/v1.0/sites/{siteId}/site-pages/{sitePageId}`
returns HTTP 400 `UnsupportedOperationException` on published site initializer
pages, so any tool that needs to rewrite fragment configuration — a microservice
URL, an API endpoint — hits the same wall.

LDM's current workaround rewrites `fragmententrylink.editablevalues` with a
direct SQL `REGEXP_REPLACE`. It is a regex over a JSON column, its `WHERE`
clause is unscoped (every matching row in the instance is rewritten), it only
supports PostgreSQL/MySQL, and the portal caches fragment configuration in
memory with no Gogo command able to invalidate it — so the patched rows stay
invisible until a restart.

Inside the JVM this becomes a supported call:

```java
FragmentEntryLinkLocalService.updateFragmentEntryLink(
    userId, fragmentEntryLinkId, editableValues, updateClassedModel)
```

which routes through the service layer, so cache invalidation, model listeners
and indexing are Liferay's concern rather than the caller's.

#### Upstream Investigation & Feature Flag

Both configuration alternatives were investigated and ruled out:
1. `feature.flag.LPS-178052=true` only controls `POST /v1.0/sites/{siteId}/site-pages` (creating pages) and does not unlock PUT updates for published initializer pages.
2. Site Initializer update support (`LPS-165482`) only synchronizes bundled descriptor content from the ZIP; it does not provide dynamic programmatic runtime overrides.

Upstream feature request: [LPD-99955](https://liferay.atlassian.net/browse/LPD-99955).

To prevent accidental or unauthorized writes, this module's PUT endpoint is explicitly gated behind:
```properties
feature.flag.LPD-99955=true
```
in `portal-ext.properties` (with `feature.flag.LPS-178052=true` accepted as a backward-compatible alias).

#### Endpoints & Merge Semantics

- `PUT /o/fragment-override/fragment-entry-links/{fragmentEntryLinkId}`: Recursively deep-merges the caller's JSON payload over existing `editableValues`, preserving unmentioned keys and nested configuration objects (closes [#9](https://github.com/peterrichards-lr/liferay-custom-osgi-modules/issues/9), unblocking [LDM #1602](https://github.com/peterrichards-lr/liferay-docker-manager/issues/1602)). If existing database values are corrupted or unparseable, the endpoint safely refuses to overwrite and returns HTTP 409 `Conflict` (`CorruptedState`).
- `PATCH /o/fragment-override/fragment-entry-links/{fragmentEntryLinkId}`: Explicit HTTP PATCH alias supporting the same server-side deep-merge semantics.
- `GET /o/fragment-override/fragment-entry-links/{fragmentEntryLinkId}`: Returns the current `editableValues` JSON document and layout metadata for inspection.
- `GET /o/fragment-override/status`: Reports module status and feature flag enablement.

#### Authentication & Permissions
All mutation and inspection endpoints require an authenticated user with omniadmin, company admin, group admin, or layout `UPDATE` permission (protecting sensitive service endpoints and configuration).

Background:
[liferay-docker-manager#883](https://github.com/peterrichards-lr/liferay-docker-manager/issues/883)
(upstream request),
[#1601](https://github.com/peterrichards-lr/liferay-docker-manager/issues/1601)
(feasibility), and
[#1602](https://github.com/peterrichards-lr/liferay-docker-manager/issues/1602)
(consumer integration).

### search-reindex

Adopted from `aica-reindex-endpoint` in [liferay-ai-commerce-accelerator](https://github.com/peterrichards-lr/liferay-ai-commerce-accelerator).

No Liferay Headless REST or GraphQL API permits triggering a search reindex for an arbitrary entity model class (e.g. `com.liferay.commerce.product.model.CPDefinition`). Data-generation, seeding, and migration tools that generate content in bulk require search indexing so newly added entities appear in search and headless queries immediately.

This module provides a secure JAX-RS whiteboard endpoint:
- `POST /o/search-reindex/reindex/all`: Schedules reindexing across all portal company indexes.
- `POST /o/search-reindex/reindex/{className}`: Schedules reindexing for the specified entity class.
- `GET /o/search-reindex/status`: Healthcheck returning `{"status":"active","module":"search-reindex"}`.

#### Security & Permissions
The endpoint is strictly gated:
- Unauthenticated / guest requests return HTTP 401 `Unauthorized`.
- Non-omniadmin callers return HTTP 403 `Forbidden`.
- OAuth callers require the `Custom.Search.Reindex` scope (derived from `osgi.jaxrs.name`), since deploying the bundle alone is not sufficient.

### commerce-site-type

Exposes a commerce channel's **site type** (`B2C` / `B2B` / `B2X`) and allowed
account types.

No headless Liferay API (`/o/headless-commerce-admin-channel/v1.0/channels`) exposes
a channel's site type. Inside Liferay, this setting is stored in group-scoped OSGi
configuration (`com.liferay.commerce.account`) on the channel's own `Group`
(`classNameId = CommerceChannel`, `classPK = channelId`).

#### Endpoint

```
GET /o/commerce-site-type/channels/{channelId}/site-type
```

Returns:
```json
{
  "channelId": 34562,
  "siteType": 1,
  "siteTypeLabel": "B2B",
  "siteTypeStatus": "CONFIGURED",
  "configuredScope": "GROUP",
  "allowedAccountTypes": ["business", "supplier"],
  "configured": true
}
```

#### Status & Configuration Lifecycle

The response cleanly separates interpretation (`siteTypeStatus`) from provenance (`configuredScope`):

##### `siteTypeStatus`

- **`CONFIGURED`**: Explicitly set in admin to a recognized type (`B2B`, `B2C`, `B2X`). `allowedAccountTypes` contains the allowed types. Consumers validate strictly.
- **`NOT_CONFIGURED`**: Not explicitly configured at any scope. Liferay defaults `commerceSiteType` to `0` (B2C) while account configuration defaults to B2X. When `NOT_CONFIGURED`, `allowedAccountTypes` is empty `[]` and `configured` is `false`. Consumers should warn and fail open. Remedy: operator sets site type in Commerce → Channels.
- **`UNRECOGNISED`**: Channel is configured to a site type value not recognized by this bundle version (e.g. `3`). Returns raw integer `siteType`, `siteTypeLabel: "UNKNOWN"`, `allowedAccountTypes: []`, and `configured: true`. Consumers should warn and fail open. Remedy: tooling update.

##### `configuredScope`

- **`GROUP`**: Explicitly configured on the channel's own group.
- **`COMPANY`**: Inherited from the company (virtual instance) level.
- **`SYSTEM`**: Inherited from system configuration.
- **`NONE`**: Not explicitly configured at any scope (`configured: false`).

Site types supported:
- `0`: `B2C` (allowed account types: `["person"]`)
- `1`: `B2B` (allowed account types: `["business", "supplier"]`)
- `2`: `B2X` (allowed account types: `["business", "person", "supplier"]`)

#### Status Healthcheck
```
GET /o/commerce-site-type/status
```

The `/status` healthcheck is intentionally unauthenticated as a lightweight
deployment readiness probe to verify that the module is installed and responding.

#### Security & Permissions

- **Authentication**: Unauthenticated / guest requests return HTTP 401 `Unauthorized`.
- **Authorization**: Callers require omniadmin, company admin, or `VIEW` permission on the
  channel or its group (`com.liferay.commerce.product.model.CommerceChannel`). Unauthorized
  callers return HTTP 403 `Forbidden`.

#### Liferay versions

Compiled against `dxp-2026.q1.12-lts`, the workspace pin.

The `Import-Package` ranges in `bnd.bnd` are bounded to the current major of each
package and are **kernel-only** — `com.liferay.portal.kernel.{exception, json, log,
model, security.permission, service, settings, util}`. Nothing from a commerce
bundle is imported: the channel is reached through `GroupLocalServiceUtil` and
referred to by class-name string, and the site type is read through
`GroupServiceSettingsLocator` and its company and system counterparts.

**Runtime verification is outstanding.** The 13 unit tests exercise the resolution
logic with mocks; they do not exercise OSGi wiring, so the ranges above are not yet
confirmed at either end of any line span. Deploying to a `2026.q1.12-lts` instance
and to one other line, and confirming the bundle resolves and
`/o/commerce-site-type/status` answers, is what would settle it.

Until then this module claims only the line it was compiled against. Kernel packages
change major less often than application packages, so a single artifact spanning
several lines is plausible — but that is a hypothesis about this import set, not a
tested claim, and per the resolution above it is a property of the imports rather
than of the repository.

## Building

```bash
./gradlew :modules:fragment-override:build
./gradlew :modules:search-reindex:build
./gradlew :modules:commerce-site-type:build
```

The JAR lands in `modules/<module-name>/build/libs/`.

## Consuming a module

There are two routes, and which one you want depends on whether you need a
build-time dependency or just the bundle.

### As a file (no authentication)

The usual case. An OSGi bundle is consumed by being dropped into a running
instance's `osgi/modules`, not by being compiled against.

Every release attaches its JARs as release assets, downloadable anonymously,
each with a `.sha256` beside it:

```bash
gh release download v1.1.0 \
  --repo peterrichards-lr/liferay-custom-osgi-modules \
  --pattern '*.jar' --pattern '*.sha256'

shasum -a 256 -c *.sha256

ldm deploy <project> com.liferay.fragment.override-1.1.0-dxp-2026.q1.12-lts.jar
```

**The DXP line is in the filename, not only the metadata**, e.g.
`com.liferay.fragment.override-1.1.0-dxp-2026.q1.12-lts.jar`. A mismatch
between the bundle and the portal you are deploying into is then visible when
you download it, rather than surfacing later as a resolution failure inside a
running instance.

`Bundle-Version` comes from the release tag, so two builds are always
distinguishable and a `.ldmp` package can record exactly which bundle it
contains. A locally built jar is versioned `1.0.0-SNAPSHOT` and can never be
mistaken for a released one.

### As a Maven/Gradle dependency (authentication required)

Published to GitHub Packages as `com.liferay.custom.osgi:<module>:<version>`.

**GitHub Packages' Maven registry requires authentication even for public
packages** — only the Container registry allows anonymous pulls. So consuming
this way needs a token with `read:packages`, which is friction the release
assets above avoid. Use this route when you genuinely need a resolvable
coordinate; otherwise take the file.

```gradle
repositories {
    maven {
        url "https://maven.pkg.github.com/peterrichards-lr/liferay-custom-osgi-modules"
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")   // needs read:packages
        }
    }
}

dependencies {
    compileOnly group: "com.liferay.custom.osgi", name: "commerce-site-type", version: "1.0.0"
}
```

## Publishing

Publishing is triggered by **publishing a GitHub release**, not by merging: a
registry coordinate is immutable once someone consumes it, so it should be a
deliberate act with a version behind it.

`.github/workflows/publish.yml` builds, checks that no bundle imports
`com.liferay.*` without a version range, publishes to GitHub Packages, and
attaches the JARs to the release.

To rehearse without publishing, run the workflow manually with `dry_run` left
at its default.

## Liferay version

`gradle.properties` pins `liferay.workspace.product=dxp-2026.q1.12-lts`.

That pin is a **compile target, not a support range.**

### Resolution: per-DXP-line is a property of a module's imports

The question of whether to publish a single generic artifact or one artifact per
Liferay DXP line has been settled with empirical evidence:

1. **Breaking Package Export Increments**: Inspection of target platform baselines
   revealed that Liferay bumps major package versions across DXP quarterly lines.
   For example, `com.liferay.fragment.service` bumped from `15.0.0` in DXP 2025.Q4
   to `16.0.0` in DXP 2026.Q1, and `com.liferay.portal.kernel.util` sits at `96.6.0`.
2. **Bounded OSGi Consumer Ranges**: Under OSGi semantic versioning, consumer
   import ranges for services and models must be bounded to the current major version
   (`[16.0, 17.0)` for `com.liferay.fragment.service`, `[5.0, 6.0)` for
   `com.liferay.fragment.model`). A bundle compiled against 2026.Q1 cannot satisfy its
   wiring requirements on 2025.Q4 runtimes.

A bundle is a per-DXP-line artifact whenever it imports packages whose major
versions differ across the lines it targets. `fragment-override` is one:
`com.liferay.fragment.service` is `15.x` on 2025.Q4 and `16.x` on 2026.Q1, so
no single bounded range spans both.

This is a consequence of a module's imports, not a blanket rule of this repository.
Check your own import set before assuming it applies — a module importing
only packages that are stable across your target lines can ship as a single
artifact spanning releases.

The `-dxp-<tag>.jar` suffix in release asset filenames (e.g.
`com.liferay.fragment.override-1.1.0-dxp-2026.q1.12-lts.jar`) remains standard
either way: it is correct for per-line bundles and harmless for a bundle that
spans lines.

<!-- markdownlint-disable MD049 -->
---
*Last Updated: 2026-09-04* | *Last Reviewed: 2026-09-04*
