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
