# Contributing

## What belongs here

A module that works around a **platform-level Liferay limitation** which more
than one project could hit. If it only makes sense for one consumer, it belongs
in that consumer's repository.

The test is simple: could a project with no connection to yours deploy this
bundle and get value from it? If not, it is not a shared module.

## Before writing a module

**Rule out configuration first.** Liferay restrictions are frequently gated by
feature flags or have a supported path that is merely undocumented. A portal
property is cheaper to maintain than a bundle, for everyone, forever.

Record what you ruled out and how — in the module's javadoc and in the README
entry. A future reader needs to know whether "we built a bundle" was a
considered decision or a first instinct.

## What a module needs

1. **A README entry** stating what limitation it addresses and what was ruled
   out first.
2. **A deliberate `Import-Package` range** in `bnd.bnd`. Consumers run a wide
   span of Liferay versions and a bundle compiled against one API version may
   not resolve against another. Do not accept whatever bnd infers.
3. **A statement of the versions it was tested against**, at both ends of the
   range it claims.
4. **Javadoc that explains why the module exists**, not just what the class
   does. The reason is the part that decays.

## Naming

Modules are named for the **capability**, not the consumer. `fragment-override`,
not `ldm-fragment-override` — the next project to need it should not have to
read another project's name to understand what it does.

## After deploying it somewhere

Add a row to [ADOPTERS.md](./ADOPTERS.md).

## Authorisation and security

A module exposing an endpoint must apply consistent authorisation principles:

1. **Authorise at the narrowest scope the operation affects, matching permissions to effects**:
   - `VIEW` on the target entity or containing group for read operations (e.g. `commerce-site-type`).
   - `UPDATE` on the containing layout or entity for write operations (e.g. `fragment-override`).
   - `Omniadmin` is reserved strictly for operations that genuinely affect the entire portal instance (e.g. `search-reindex`), and its use must be justified in the module's javadoc.
2. **Authenticate before authorising, and separately**:
   - Return HTTP 401 `Unauthorized` for unauthenticated or guest callers (`user == null || user.isDefaultUser()`).
   - Return HTTP 403 `Forbidden` only for authenticated users lacking the required permission.
   - Never collapse these into a single 403; doing so makes unauthenticated service account calls indistinguishable from permission failures.
3. **Attribute mutations to the caller**:
   - Any module performing writes or mutations must record the authenticated caller's identity (e.g. `user.getUserId()`) in audit logs and entity updates, never attributing changes to an entity's original creator.
4. **Service accounts and client extensions**:
   - Prioritise correctness over convenience: when client extensions authenticate via machine service accounts, administrators must grant those accounts scoped permissions rather than relying on blanket administrative bypasses.
5. **Deployment healthchecks**:
   - Lightweight status probes (e.g. `/status`) are intentionally unauthenticated, returning high-level deployment readiness without exposing sensitive metadata.

<!-- markdownlint-disable MD049 -->
---
*Last Updated: 2026-09-04* | *Last Reviewed: 2026-09-04*
