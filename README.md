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

There are two routes, and which one you want depends on whether you need a
build-time dependency or just the bundle.

### As a file (no authentication)

The usual case. An OSGi bundle is consumed by being dropped into a running
instance's `osgi/modules`, not by being compiled against.

Every release attaches its JARs as release assets, which are downloadable
anonymously:

```bash
gh release download --repo peterrichards-lr/liferay-custom-osgi-modules \
  --pattern '*.jar'

ldm deploy <project> com.liferay.fragment.override-1.0.0.jar
```

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
    compileOnly group: "com.liferay.custom.osgi", name: "fragment-override", version: "1.0.0"
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

### Open decision: one artifact, or one per Liferay line?

This is the repo's central unresolved question, and it should be settled with
evidence before a second module is added.

**The case for one artifact per Liferay tag** — mirroring how LDM manages
pre-warmed seeds — is that it is guaranteed correct. Every consumer gets a
bundle built against exactly their line, and no resolution surprise is
possible.

**The case against** is that it is not what OSGi is for. A seed is a database
and filesystem snapshot: inherently version-specific, with no mechanism for
spanning versions. A bundle is code compiled against an API, and OSGi's package
versioning exists precisely so that one artifact can declare
`Import-Package: com.liferay.fragment.service;version="[X,Y)"` and resolve
across every runtime in that range. Shipping one artifact per tag gives up that
mechanism, and produces N artifacts that are usually byte-identical.

**The question is empirical, not architectural:** do the *exported package*
versions actually change across the Liferay lines we care about? If
`com.liferay.fragment.service` exports the same package version from
2025.q4 through 2026.q1, one bundle covers both and per-tag builds are waste.
If Liferay bumped the package major version, no single range can span it and
per-line artifacts are unavoidable.

Nothing here answers that yet, because `fragment-override` is a scaffold that
imports **no** `com.liferay.*` packages at all — its manifest reads
`Import-Package: jakarta.ws.rs,jakarta.ws.rs.core,java.lang,java.util`. There
is currently zero evidence in either direction.

**Proposed way to settle it**, in order:

1. Make the first module import what it actually needs, and read the resulting
   `Import-Package` range off the manifest.
2. Add a CI matrix that *resolves* that bundle against each Liferay line to be
   supported — resolution, not compilation, is the thing that fails.
3. Ship one artifact if the matrix is green. Only if resolution genuinely fails
   across the span, fall back to per-line artifacts, versioned
   `<module>-<version>-dxp-<line>`.

Doing (3) pre-emptively would be building N pipelines to solve a problem that
may not exist; doing (1) and (2) costs one module's worth of work and answers
it for every module afterwards.
