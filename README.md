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
| [`commerce-site-type`](#commerce-site-type) | **available** | Reads and sets a commerce channel's B2B/B2C/B2X site type, and reports allowed account types |
| [`client-extension-entry`](#client-extension-entry) | **available** | Exposes the portlet id Liferay composes for a client extension, which no Liferay API publishes |
| [`user-group-recommendations`](#user-group-recommendations) | **available** | Serves hand-picked blog entries per user group, as a stand-in for Analytics Cloud / LDP content recommendations |

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

Reads and sets a commerce channel's **site type** (`B2C` / `B2B` / `B2X`), and
reports the account types that follow from it.

No headless Liferay API (`/o/headless-commerce-admin-channel/v1.0/channels`) exposes
a channel's site type, and none sets it either — a channel created through the
API always has the value unset, which Liferay then treats as B2C. Inside Liferay, this setting is stored in group-scoped OSGi
configuration (`com.liferay.commerce.account`) on the channel's own `Group`
(`classNameId = CommerceChannel`, `classPK = channelId`).

#### Endpoints

```
GET /o/commerce-site-type/channels/{channelId}/site-type
PUT /o/commerce-site-type/channels/{channelId}/site-type
```

Both return the same shape:
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

##### Setting the site type

```
PUT /o/commerce-site-type/channels/{channelId}/site-type
Content-Type: application/json

{"siteType": 1}
```

`siteType` must be `0`, `1` or `2`; anything else is HTTP 400 `BadRequest`.

The write targets group scope on the channel's own `Group`, through
`ModifiableSettings.setValue` + `store()` on the `com.liferay.commerce.account`
settings — the same store the `GET` reads and the same scope the admin UI
writes.

**The response is read back from the store after the write, not echoed from the
request.** `configuredScope` is the field to check: it reads `GROUP` only when
the value was found explicitly set at group scope, so anything else means the
write did not land where it was aimed. A mismatch is also logged as a warning
server-side.

That indirection is deliberate rather than defensive habit. A commerce
channel's site type is known not to persist until it is changed *and saved* in
Liferay's own UI — changing to `B2B`, saving, changing back and saving again is
the observed workaround — which suggests the UI does something beyond writing
this key. **Whether `setValue` + `store()` alone is sufficient is not yet
confirmed on a live portal** (see
[#30](https://github.com/peterrichards-lr/liferay-custom-osgi-modules/issues/30)).
Until it is, do not treat HTTP 200 as proof: compare `siteType` and
`configuredScope` in the response, and confirm through a path that does not
read the same settings object — the account types the channel actually accepts,
or the admin UI showing the value as chosen rather than defaulted.

A unit test (`testSetSiteType_WriteSilentlyDiscarded_ResponseDoesNotClaimSuccess`)
pins the behaviour for that case: against a store that accepts a write and keeps
nothing, the endpoint reports `NOT_CONFIGURED` / `NONE` rather than claiming the
requested value.

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
- **Authorization (`GET`)**: Callers require omniadmin, company admin, or `VIEW` permission on the
  channel or its group (`com.liferay.commerce.product.model.CommerceChannel`). Unauthorized
  callers return HTTP 403 `Forbidden`.
- **Authorization (`PUT`)**: `UPDATE` rather than `VIEW`. A channel's site type decides which
  account types can order in it, so being able to read it does not entitle a caller to change
  it. `VIEW` alone returns HTTP 403 `Forbidden`.

#### Liferay versions

Compiled against `dxp-2026.q3.0`, the workspace pin.

The `Import-Package` ranges in `bnd.bnd` are bounded to the current major of each
package and are **kernel-only** — `com.liferay.portal.kernel.{exception, json, log,
model, security.permission, service, settings, util}`. Kernel-only does not mean
line-agnostic: `model`, `service` and `util` all crossed a major between
q1.12-lts and q3.0. Nothing from a commerce
bundle is imported: the channel is reached through `GroupLocalServiceUtil` and
referred to by class-name string, and the site type is read through
`GroupServiceSettingsLocator` and its company and system counterparts.

**Runtime verification is outstanding.** The 13 unit tests exercise the resolution
logic with mocks; they do not exercise OSGi wiring, so the ranges above are not yet
confirmed at either end of any line span. Deploying to a `2026.q3.0` instance
and to one other line, and confirming the bundle resolves and
`/o/commerce-site-type/status` answers, is what would settle it.

Until then this module claims only the line it was compiled against. Kernel packages
change major less often than application packages, so a single artifact spanning
several lines is plausible — but that is a hypothesis about this import set, not a
tested claim, and per the resolution above it is a property of the imports rather
than of the repository.

### client-extension-entry

Exposes the **portlet id** Liferay composes for a client extension, together
with the entry metadata a configuration panel needs.

A link into a client extension's configuration screen in the Control Panel needs
that portlet id, and it is not published anywhere a caller outside the portal JVM
can read it. What was ruled out first:

- GraphQL's `ClientExtension` type carries only `clientExtensionConfig` and
  `externalReferenceCode`. No id, and no top-level query for entries.
- No headless REST API covers client extension entries.
- The portlet id is not stored at all. It is assembled at deploy time by
  `CETDeployerImpl`, so there is no row to read even with database access.

Inside OSGi it is a short method call, which is what this module wraps.

#### Endpoints

```
GET /o/client-extension-entry/entries/{externalReferenceCode}
GET /o/client-extension-entry/entries?keywords=&type=&page=1&pageSize=100
GET /o/client-extension-entry/status
```

A single entry returns:

```json
{
  "externalReferenceCode": "LXC:liferay-ai-commerce-accelerator-configuration",
  "requestedExternalReferenceCode": "liferay-ai-commerce-accelerator-configuration",
  "companyId": 99367122642203,
  "entryId": null,
  "sourceType": "CONFIGURATION",
  "name": "AICA Configuration",
  "description": "",
  "type": "customElement",
  "status": 0,
  "statusLabel": "approved",
  "sourceCodeURL": "",
  "baseURL": "http://localhost:3000",
  "hasPortlet": true,
  "portletId": "com_liferay_client_extension_web_internal_portlet_ClientExtensionEntryPortlet_99367122642203_LXC_liferay_ai_commerce_accelerator_configuration",
  "instanceable": true,
  "friendlyURLMapping": "aica-configuration"
}
```

The list variant wraps the same objects in
`{"companyId": ..., "page": ..., "pageSize": ..., "totalCount": ..., "entries": [...]}`,
so a configuration panel can report which extensions are actually deployed
rather than which ones it hopes are.

#### The number in the portlet id is the company id, not the entry id

Worth stating plainly, because it is the natural assumption and it is wrong.
Both values are large counter-issued longs and look alike. Liferay composes the
id as:

```java
"com_liferay_client_extension_web_internal_portlet_" +
    "ClientExtensionEntryPortlet_" + cet.getCompanyId() + "_" +
        CETUtil.normalizeExternalReferenceCodeForPortletId(
            cet.getExternalReferenceCode())
```

Verified by decompiling `com.liferay.client.extension.web 1.0.94`, the artifact
`dxp-2026.q1.12-lts` shipped, rather than from `master` alone. The
company id segment was introduced by `client-extension-web` upgrade step
`v3_0_1` (`UpgradePortletId`), which renamed `prefix + externalReferenceCode` to
`prefix + companyId + "_" + externalReferenceCode`.

The practical consequence is the same either way — a hardcoded portlet id breaks
on every fresh database, because the company id differs — but the diagnosis
matters when reading a portlet id by eye.

`CETUtil.normalizeExternalReferenceCodeForPortletId` is a bare
`replaceAll("\\W", "_")`. An `LXC_` prefix therefore comes from the deployed
external reference code itself, not from prefixing performed by Liferay or by
this module. The module calls Liferay's helper rather than reproducing it.

Only `customElement` and `iframe` extensions register a portlet — those are the
two branches of `CETDeployerImpl#deploy` that compose an id. For every other
type the endpoint reports `hasPortlet: false` and a null `portletId` rather than
composing an id for a portlet that was never registered.

#### Which external reference code to pass

Either the id declared in `client-extension.yaml`, or the prefixed form Liferay
holds. Both resolve, and the response reports which one matched:

- `externalReferenceCode` — the code Liferay holds
- `requestedExternalReferenceCode` — the code the caller passed

The two differ for a workspace-deployed extension.
`CETConfigurationFactory#_getExternalReferenceCode` returns
`"LXC:" + <id declared in client-extension.yaml>`, so
`liferay-ai-commerce-accelerator-configuration` is held as
`LXC:liferay-ai-commerce-accelerator-configuration`. Because
`normalizeExternalReferenceCodeForPortletId` replaces every non-word character,
the **colon** becomes the underscore seen in the portlet id:
`LXC_liferay_ai_commerce_accelerator_configuration`. There is no literal
`LXC_`-prefixed string stored anywhere, which is why searching for one finds
nothing.

An entry created through Client Extension Admin keeps the plain code, with no
prefix — which is why database-backed and configuration-backed extensions in the
same instance look inconsistent.

Requiring a caller to know that convention would put back a smaller version of
the guesswork this module exists to remove, so the endpoint accepts both. One
caveat for anyone reading the log: `CETManagerImpl#getCET` emits a WARN for a
code it cannot find, so the form that misses leaves a line behind even when the
other form succeeds.

#### `sourceType`, and why `CETManager` rather than the local service

- **`DATABASE`**: the extension has a `ClientExtensionEntry` row, created
  through Client Extension Admin. `entryId` is populated.
- **`CONFIGURATION`**: the extension was deployed as a workspace `.zip` and
  exists as OSGi configuration, with no database row. `entryId` is `null`.

Entries are resolved through `CETManager`, not
`ClientExtensionEntryLocalService`. The local service sees only the first kind,
so on its own it returns null for exactly the deployment style that most needs
this endpoint. `CETManager.getCET` consults the database first and the
configuration map second, covering both; the local service is still consulted,
but only to report `entryId` and to distinguish `sourceType`.

#### Status healthcheck

```
GET /o/client-extension-entry/status
```

Intentionally unauthenticated, as a lightweight deployment readiness probe.

#### Security & permissions

- **Authentication**: unauthenticated / guest requests return HTTP 401
  `Unauthorized`.
- **Authorisation**: callers require one of

  1. omniadmin;
  2. company admin;
  3. `ACCESS_IN_CONTROL_PANEL` on
     `com_liferay_client_extension_web_internal_portlet_ClientExtensionAdminPortlet`;
  4. `VIEW` on the `ClientExtensionEntry` model — what Liferay's own
     `ClientExtensionEntryServiceImpl` checks for a read, available only for a
     database-backed entry, since a model resource permission needs a primary
     key.

  Unauthorised callers return HTTP 403 `Forbidden`.

  **Rung 3 is the one to grant a service account.** It is the only rung that
  covers a configuration-backed extension, which has no model resource to hold a
  permission. Grant it in Control Panel → Roles → *[the account's role]* →
  Define Permissions → Control Panel → Client Extensions → Access in Control
  Panel.

  Note what is deliberately *not* in that list: `VIEW` on the
  `com.liferay.client.extension` portlet resource. Its
  `resource-actions/default.xml` supports only `ADD_ENTRY` and `PERMISSIONS`, so
  `VIEW` is not an action an administrator can grant against it — checking it
  would be unreachable code rather than a permission.
- **Authorisation runs before resolution**, so an unauthorised caller cannot use
  the difference between 403 and 404 to discover which external reference codes
  exist.
- **OAuth scope**: `Custom.Client.Extension.Entry.everything.read`, derived from
  `osgi.jaxrs.name`. Deploying the bundle is not sufficient — a service account
  or client extension must be granted the scope explicitly in its
  `client-extension.yaml`.
- **Telling the two 403s apart**, since they need different remedies:
  - **empty body** — Liferay's access control rejected the call before it
    reached the module. The OAuth scope is missing.
  - **JSON body with `"error": "Forbidden"`** — the module rejected the call.
    The caller is authenticated and in scope but holds none of the four
    permissions above; a matching WARN naming the user id is in the log.
- **Unknown external reference code** returns HTTP 404 `NotFound`, never a
  `NullPointerException`.

#### Liferay versions

Compiled against `dxp-2026.q3.0`, the workspace pin.

Unlike `commerce-site-type`, this module cannot be kernel-only: the client
extension registry has no kernel-facing API, so it imports
`com.liferay.client.extension.{constants, model, service, type, type.manager,
util}` alongside the kernel packages and `com.liferay.portal.vulcan.pagination`.
This is a **per-DXP-line artifact** — see the resolution below. Note which half
of that import set actually forced the q1.12-lts → q3.0 rebuild: every
`com.liferay.client.extension.*` range still held on 2026.Q3.0, and the kernel
packages did not.

**Runtime verification is outstanding.** The 22 unit tests exercise the
composition, source-type and authorisation logic with mocks; they do not
exercise OSGi wiring. Deploying to a `2026.q3.0` instance and confirming
that the bundle resolves, that `/o/client-extension-entry/status` answers, and
that the returned `portletId` matches the one Liferay registered, is what would
settle it.

### user-group-recommendations

A **collection provider** that serves a hand-picked set of blog entries chosen
per user group, so a Collection Display fragment shows different posts depending
on who is logged in.

This is a deliberate **stand-in for Analytics Cloud / LDP content
recommendations**, for environments where those cannot be made to work.

#### What was ruled out first

Liferay already personalises collections by user segment
([Personalizing Collections](https://learn.liferay.com/w/dxp/personalization/experiences/personalizing-collections)),
and segment criteria include User Group membership, so the underlying use case
is natively supported and needs no bundle at all. **If you do not need the
substitution property described below, use the native route and do not deploy
this module.**

It was rejected here for one specific reason. Personalization produces a
*Collection*, which the Collection Display fragment consumes from a different
slot than a *Provider*. A Collection therefore cannot be swapped with the
recommendation provider it is standing in for, whereas this module registers
into the same picker slot — so switching between Plan A and Plan B is a dropdown
change on the page rather than re-authoring it. That substitution property is
the entire justification for the bundle.

#### Using it

The module has no REST surface. Everything is done in the UI, and the only build
artifact is the bundle.

**1. Deploy the bundle.** Drop the jar into the instance's `osgi/modules`, or from
the workspace:

```bash
blade gw deploy
```

Confirm it started — the provider is invisible until the component is active:

```bash
# in the Gogo shell
lb | grep user.group.recommendations      # expect Active
scr:info com.liferay.user.group.recommendations.UserGroupRecommendationsInfoCollectionProvider
```

The component must report **satisfied**. If it is unsatisfied, a `@Reference` did
not bind and the provider will not appear in step 4.

**2. Create the user groups.** *Control Panel → Users → User Groups → Add*. Create
one per audience, for example `Riders` and `Engineering`. The names are yours; the
module reads whatever exists.

**3. Assign users.** *Control Panel → Users and Organizations*, select each user →
*User Groups* → assign. The module **reads** membership, it does not establish it —
nothing happens until users are actually in a group.

**4. Add the collection to a page.** Edit a Content Page → drag in a **Collection
Display** fragment → in its sidebar choose the collection source, and pick
**Recommended for Your Group** from the *Providers* list.

**5. Configure it.** The sidebar now shows one multiselect per user group. Pick the
entries each group should see, in the order they should appear. Set the two
behaviour options, then **Publish**.

**6. Verify.** Do not log out and back in six times. Use
*Control Panel → Users → select a user → Actions → **Impersonate User***, view the
page, then end impersonation. Each group should show exactly the entries configured
for it.

##### If the collection comes back empty

| Symptom | Likely cause |
|---|---|
| Provider missing from the *Providers* list | Bundle not Active, or the component is unsatisfied — check step 1 |
| No user group fields in the sidebar | `getConfigurationInfoForm()` saw no service context. This is the known unverified risk below; the mapping would need to move to OSGi configuration |
| Empty for every user | Nobody is in a user group, or the fields were never saved. Check step 3 |
| Empty for one user only | That user is in no configured group — expected, and controlled by the *no group* behaviour setting |
| Fewer entries than configured | Some are unapproved or deleted; the provider skips them by design |
| Wrong order | See the ordering caveat below |

A failure to resolve the current user is logged at `ERROR` against
`com.liferay.user.group.recommendations`.

#### New CMS content (objects) vs legacy Blogs

The module serves **both**, through two providers:

| Content | Provider | Entity |
|---|---|---|
| Legacy Blogs | declarative `@Component` | `BlogsEntry` |
| New CMS content types (`L_CMS_BLOG`, or a custom type such as MotorBlog) | registered at runtime, one per definition per company | `ObjectEntry` |

**Why the object one cannot be a declarative component.** An object-backed
collection provider must register under `item.class.name =
ObjectDefinition#getClassName()`, and for a custom object that class name
carries a short-name suffix generated when the definition is created --
`com.liferay.object.model.ObjectDefinition#Z7P5` and so on. It is
instance-specific and cannot appear in an annotation. Liferay registers its own
object collection providers programmatically for exactly this reason; see
`ObjectDefinitionDeployerImpl`. `UserGroupRecommendationsObjectRegistrar` mirrors
that, resolving each configured external reference code against every company at
activation and whenever the configuration changes.

> [!NOTE]
> A definition created *after* activation is not picked up until the
> configuration is saved again or the bundle restarts. Tracking definition
> creation would need a model listener and considerably more lifecycle; the
> configuration naming a definition is normally written after it exists.

#### Configuration

Nothing is compiled in. The mapping lives in OSGi configuration, at
*Control Panel &rarr; System Settings &rarr; Content and Data &rarr; User Group
Recommendations*, and deploys as a file under `configs/<env>/osgi/configs/`:

```properties
# com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration.config

objectDefinitionExternalReferenceCodes=["L_CMS_MOTORBLOG"]

objectUserGroupEntries=[\
  "L_CMS_MOTORBLOG|Riders=ERC-SCENIC-ROUTE,ERC-SOLARA-HORIZON,ERC-SOLO-CAMPING",\
  "L_CMS_MOTORBLOG|Engineering=ERC-SOUL-OF-SOLARA,ERC-BLUEPRINT,ERC-SOURCING"\
]

multiGroupStrategy="firstMatch"
fallback="empty"
label="Recommended for Your Group"
```

For legacy Blogs use `userGroupEntries` instead, without the content-type prefix:

```properties
userGroupEntries=["Riders=the-scenic-route,solara-horizon,solo-moto-camping"]
```

Entries are served **in the order written**; the provider never re-sorts, because
the point of naming them individually is that the sequence is chosen. User groups
are matched by name, case-insensitively.

**Reference entries by external reference code.** Object entries resolve by ERC
first and numeric id only as a fallback; legacy blogs resolve by ERC, then
friendly URL, then id. Ids differ between environments, so a configuration file
written against one instance resolves to nothing -- or to unrelated entries -- on
another, which is exactly what a deployable config file exists to avoid.

An earlier revision put this mapping in the page editor via
`ConfigurableInfoCollectionProvider`. It was withdrawn: the configuration is
stored in the page and so is lost on a site rebuild, it must be repeated for
every placement of the fragment, and it can be neither version-controlled nor
seeded by a deployment script.

#### Behaviour

- **Order** — as configured. The provider never re-sorts.
- **Several groups** — first match by default; `combine` unions across groups,
  de-duplicating while keeping configured order.
- **No group, or guest** — empty by default; optionally the most recent entries.
- **Deleted or unapproved entries** — skipped. A configuration naming an entry
  that was later deleted degrades to a shorter list rather than erroring.
- **No service context** — returns empty and logs, rather than throwing.

#### Why `BlogsEntry` and not `AssetEntry`

Blogs register a rich item-specific field set through
`BlogsEntryInfoItemFieldValuesProvider`. Typing the collection to `AssetEntry`
would resolve fields through the *asset* provider instead, silently dropping
blog-specific fields such as subtitle and cover image from fragment mapping.
Liferay types its own recommendation provider
(`UserCommerceMLRecommendationInfoItemCollectionProvider`) to a concrete class
for the same reason.

#### Liferay versions

Compiled against `dxp-2026.q3.0`, the workspace pin.

This is a **per-DXP-line artifact**. It cannot be kernel-only: `com.liferay.info.*`
and `com.liferay.blogs.*` are application packages and move majors across lines.
Every range in `bnd.bnd` was read from the `packageinfo` files in the
`release.dxp.api` jar for this line — see
[#33](https://github.com/peterrichards-lr/liferay-custom-osgi-modules/issues/33)
for why bnd cannot derive them itself.

**Runtime verification is outstanding.** The 9 unit tests exercise selection,
ordering, multi-group strategy, fallback and pagination with mocks, and the
bundle resolves against the pinned distro — but neither exercises the page
editor. Two things specifically need confirming on a live portal:

1. That `getConfigurationInfoForm()` sees a populated `ServiceContextThreadLocal`.
   It takes no arguments, so the company and scope group can only come from the
   thread local. If it is empty there, the form renders with no user group fields
   and the mapping would have to move to OSGi configuration instead.
2. That a multiselect returns values in **selection** order rather than option
   order. The provider preserves whatever order it is given; if the framework
   normalises it, explicit ordering would need a different control.

## Building

```bash
./gradlew :modules:fragment-override:build
./gradlew :modules:search-reindex:build
./gradlew :modules:commerce-site-type:build
./gradlew :modules:client-extension-entry:build
./gradlew :modules:user-group-recommendations:build
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

ldm deploy <project> com.liferay.custom.fragment.override-1.1.0-dxp-2026.q3.0.jar
```

**The DXP line is in the filename, not only the metadata**, e.g.
`com.liferay.custom.fragment.override-1.1.0-dxp-2026.q3.0.jar`. A mismatch
between the bundle and the portal you are deploying into is then visible when
you download it, rather than surfacing later as a resolution failure inside a
running instance.

`Bundle-Version` comes from the release tag, so two builds are always
distinguishable and a `.ldmp` package can record exactly which bundle it
contains. A locally built jar is versioned `1.0.0-SNAPSHOT` and can never be
mistaken for a released one.

### Machine-readable release manifest (`modules.json`)

Each release publishes a machine-readable `modules.json` asset (and `modules.json.sha256`) describing all bundles in the release:

- **`schemaVersion`**: Top-level integer (`1`) indicating the schema format version.
- **`release`**: Top-level release tag string (e.g. `v3.0.0`).
- **`bundles`**: Dictionary keyed by `Bundle-SymbolicName` (`bsn`) for $O(1)$ lookup:
  - `module`: Repository module directory name.
  - `bsn`: Bundle symbolic name (immutable OSGi identity).
  - `version`: Bundle version extracted directly from the built JAR's `META-INF/MANIFEST.MF`.
  - `dxpLine`: Target DXP line for the bundle artifact.
  - `replaces`: Array of superseded BSNs (e.g. `["com.liferay.fragment.override"]` or `["com.liferay.accelerator.reindex.endpoint"]`), allowing consumer deploy scripts to automatically discover and prune retired bundles. This encompasses bundles that originated outside this workspace prior to module adoption as well as workspace-internal renames.
  - `asset`: Filename of the release JAR.
  - `sha256`: SHA-256 digest of the release JAR.

> [!NOTE]
> **Integrity vs. Authenticity**: `modules.json.sha256` verifies download/transport integrity (detecting truncated transfers). The embedded `sha256` digests within `modules.json` serve as the verifiable asset anchors that consumers can record locally to pin bundles.

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

`gradle.properties` pins `liferay.workspace.product=dxp-2026.q3.0`.

That pin is a **compile target, not a support range.**

### Resolution: per-DXP-line is a property of a module's imports

The question of whether to publish a single generic artifact or one artifact per
Liferay DXP line has been settled with empirical evidence:

1. **Breaking Package Export Increments**: Inspection of target platform baselines
   revealed that Liferay bumps major package versions across DXP quarterly lines.
   For example, `com.liferay.fragment.service` bumped from `15.0.0` in DXP 2025.Q4
   to `16.0.0` in DXP 2026.Q1, and again to `17.1.0` in DXP 2026.Q3.
2. **Bounded OSGi Consumer Ranges**: Under OSGi semantic versioning, consumer
   import ranges for services and models must be bounded to the current major version
   (`[17.0, 18.0)` for `com.liferay.fragment.service`, `[5.0, 6.0)` for
   `com.liferay.fragment.model`). A bundle compiled against 2026.Q1 cannot satisfy its
   wiring requirements on 2025.Q4 runtimes.
3. **Kernel packages are not the stable exception.** This was assumed here and in
   consuming projects, on the strength of q1.7-lts through q1.12-lts holding steady.
   Moving from `dxp-2026.q1.12-lts` to `dxp-2026.q3.0` broke it: four kernel
   packages crossed a major in a single hop.

   | Kernel package | q1.12-lts range | Exported on 2026.Q3.0 |
   |---|---|---|
   | `com.liferay.portal.kernel.model` | `[48.0,49.0)` | `51.0.0` |
   | `com.liferay.portal.kernel.search` | `[22.0,23.0)` | `24.2.0` |
   | `com.liferay.portal.kernel.service` | `[52.0,53.0)` | `54.1.0` |
   | `com.liferay.portal.kernel.util` | `[96.0,97.0)` | `100.0.0` |

   Only `com.liferay.portal.kernel.model` appeared in the deployment log, because
   the OSGi resolver reports the first unsatisfied requirement and stops. Read a
   `Could not resolve module` error as *at least* one broken range, never as
   exactly one.

### Keeping the ranges honest

The ranges in each `bnd.bnd` are hand-written, and necessarily so: the modules
compile against the aggregate `release.dxp.api` jar, which carries no
`Export-Package` header, so bnd derives **no version at all** rather than a
wrong one. An unversioned import matches any exported version and then binds to
whatever the portal happens to have, which is worse than a stale range.

The numbers are nonetheless *in* that jar, as one `packageinfo` file per
package. `scripts/check_import_ranges.py` reads them and checks every declared
range against the line named by `liferay.workspace.product`:

```bash
jar=$(./gradlew -q printDxpApiJar)

python3 scripts/check_import_ranges.py "$jar"          # verify
python3 scripts/check_import_ranges.py "$jar" --fix    # rewrite every bnd.bnd
```

`--fix` makes a line rebump mechanical: change `liferay.workspace.product`,
rerun with `--fix`, rebuild.

**It does not replace either existing gate.** `resolve` validates the whole
wiring against the real distro; the per-package manifest guard in `publish.yml`
catches an import that reached the manifest unversioned. What this adds is
cheaper and more complete reporting of one specific failure — a range pinned to
the wrong line. The OSGi resolver stops at the **first** unsatisfied
requirement, so a rebump surfaces one broken range per build; this names all of
them at once. Run against the `q1.12-lts` jar, today's ranges report 17
mismatches, including the four kernel packages in the table above.

One thing the check deliberately does not do is tighten the convention. This
repository floors a range to the major (`[24.0,25.0)` for an exported `24.2.0`),
whereas bnd's own consumer policy `${range;[==,+)}` would floor to the compiled
minor (`[24.2,25.0)`). The repository's form is **wider**, and so permits
binding to an older minor that may lack methods the code was compiled against.
25 of 33 imported packages differ on this point. Pass `--policy bnd` to see what
the narrower ranges would be; adopting them is tracked separately from
[#33](https://github.com/peterrichards-lr/liferay-custom-osgi-modules/issues/33).

A bundle is a per-DXP-line artifact whenever it imports packages whose major
versions differ across the lines it targets. On the evidence above, every module
in this repository is one — a kernel-only import set does not buy line
independence.

This is a consequence of a module's imports, not a blanket rule of this repository.
Check your own import set before assuming it applies — a module importing
only packages that are stable across your target lines can ship as a single
artifact spanning releases.

The `-dxp-<tag>.jar` suffix in release asset filenames (e.g.
`com.liferay.custom.fragment.override-1.1.0-dxp-2026.q3.0.jar`) remains standard
either way: it is correct for per-line bundles and harmless for a bundle that
spans lines.

<!-- markdownlint-disable MD049 -->
---
*Last Updated: 2026-09-14* | *Last Reviewed: 2026-09-14*
