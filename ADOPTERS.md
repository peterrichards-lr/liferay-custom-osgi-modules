# Adopters

Projects using modules from this repository, and what they use them for.

The point of this file is that a developer arriving at a module can see it
working somewhere real, and see what problem it was actually deployed against
— rather than inferring intent from a README.

**Adding yourself:** open a PR appending a row. One line is enough. If you are
consuming a module in a way its author would not expect, say so in the notes
— that is the most useful column here.

## Consumers

| Project | Module | Since | Notes |
|---|---|---|---|
| [liferay-docker-manager](https://github.com/peterrichards-lr/liferay-docker-manager) | `fragment-override` | *pending* | Drove the module. Currently still on its direct-SQL workaround; will switch once the module is implemented and the two configuration routes in the README are ruled out. See [#1601](https://github.com/peterrichards-lr/liferay-docker-manager/issues/1601). |

## Contributors of modules

| Project | Module | Notes |
|---|---|---|
| — | — | *none yet* |

## Not here

Modules that are specific to one project stay with that project. The AI
Commerce Accelerator's `com.liferay.accelerator.reindex.endpoint` is an example
— it is product logic, not a platform workaround, so it belongs in AICA. If
AICA later has a module that solves a general Liferay limitation, it is welcome
here and should be listed above.
