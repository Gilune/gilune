# ADR 0022: Executable bundled search and suggestion registrations

Date: 2026-09-05
Status: Implemented and validated (2026-09-05).

## Problem

The contribution catalogue exposed `search.types` and configurable task field
providers, but the host selected the Tasks search factory by plugin id and
accepted only one hardcoded values provider. Indexer registration independently
selected Tasks again. A second domain could describe a contribution without
having a supported route to execute it.

The owner requested these layer leaks be cleaned up before planning People,
using a second implementation to test the framework rather than adding another
domain-specific branch.

## Decision

Pair each bundled plugin declaration with typed search, suggestion-provider,
field-slot and indexer registrations. The application composition root chooses
which plugin implementations ship. Generic resolution matches installed state
to local implementations by owner and contribution id. The plugin interprets
its own settings and domain vocabulary; the kernel executes generic queries;
the shell renders and applies returned suggestion candidates.

Add the `suggest.values` slot for explicitly declared provider implementations.
Preserve existing public task syntax and identifiers. Require all three owners
of a cross-plugin field—the publisher, contributor and values provider—to be
enabled. Reject ambiguous static registrations and suppress conflicting dynamic
field keys rather than choose an implementation by ordering.

The exact source contract and lifecycle rules live in
[`contracts/bundled-extensions`](../../contracts/bundled-extensions/README.md).
This supersedes ADR 0015's hardcoded search/provider dispatch only. It preserves
ADR 0021's kernel query execution and ADR 0020's index lifecycle.

## Why this scope

Tasks must use the same executable registration route as the next domain. A
map moved into another helper would leave ownership and lifecycle implicit;
this contract makes both explicit and tests an unrelated implementation through
the real kernel.

Bundled execution does not require a component runtime. This change does not
claim to deliver the separate SDK module, capability sandbox, all UI extension
slots, or a People plugin. Data-backed asynchronous suggestions remain a
separate design question; callbacks must stay pure rather than acquiring
filesystem access to work around that gap.

## Evidence

- Nine new conformance tests pass, including a second domain's indexer and
  search type executing through the real kernel against indexed content after
  the source file changes on disk.
- Existing Tasks/search/suggestion tests pass through the new registry.
- Full desktop suite against the existing release kernel: **585 passed, zero
  failures or skips**, including the nine new contract tests.
- Android application and instrumented-test APKs both compile and package.
  No connected Android device was available; device execution is unverified.
- `git diff --check` passes. No native code or generated bindings changed in
  this refactor, so native rebuilds and Rust suite reruns were not required.
