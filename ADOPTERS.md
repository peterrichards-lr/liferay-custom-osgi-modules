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
| [liferay-docker-manager](https://github.com/peterrichards-lr/liferay-docker-manager) | `fragment-override` | *pending* | Drove the module. Configuration routes ruled out (LPS-178052 is POST-only, LPS-165482 is sync-only; tracked in LPD-99955). Will switch once the module is implemented behind `feature.flag.LPD-99955=true`. See [#1601](https://github.com/peterrichards-lr/liferay-docker-manager/issues/1601). |

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

<!-- markdownlint-disable MD049 -->
---
*Last Updated: 2026-09-04* | *Last Reviewed: 2026-09-04*
