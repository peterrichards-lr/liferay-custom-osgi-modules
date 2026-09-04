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
| [`fragment-override`](#fragment-override) | **scaffold only** | Headless API rejects specification updates on published site initializer pages |

### fragment-override

**Not implemented — deliberately.** The scaffold exists so the design and its
open questions are recorded somewhere a developer will find them.

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

**Two configuration routes must be ruled out before any Java is written**,
either of which makes this module unnecessary:

1. A feature flag may already unlock the PUT. The sibling restriction on
   `POST /v1.0/sites/{siteId}/site-pages` is gated by
   `feature.flag.LPS-178052=true`.
2. Site Initializer update support (LPS-165482) exposes a *Synchronize* action
   that may be the supported path outright.

Background:
[liferay-docker-manager#883](https://github.com/peterrichards-lr/liferay-docker-manager/issues/883)
(upstream request) and
[#1601](https://github.com/peterrichards-lr/liferay-docker-manager/issues/1601)
(feasibility).

## Building

```bash
./gradlew :modules:fragment-override:build
```

The JAR lands in `modules/fragment-override/build/libs/`.

## Consuming a module

Deploy the JAR into a running instance's `osgi/modules`. With LDM:

```bash
ldm deploy <project> modules/fragment-override/build/libs/<name>.jar
```

## Liferay version

`gradle.properties` pins `liferay.workspace.product=dxp-2026.q1.12-lts`.

That pin is a **compile target, not a support range.** Consumers may run a wide
span of Liferay versions, and a bundle compiled against one
`com.liferay.fragment.api` may not resolve against another — the javadocs show
51.0.1 and 57.0.0 for different releases. Any module shipped from here needs a
deliberate `Import-Package` range and testing at both ends of the range it
claims to support.
