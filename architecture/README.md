# Gilune Architecture Package

Status: **Draft v0 for validation**  
Last updated: 2026-08-30

This package turns the product principles in
[`sources/README.md`](../sources/README.md) into a buildable architecture. It
deliberately separates durable architectural commitments from technology choices
that still need to be proved.

It also records one product-sequencing clarification: the system is
**client-first rather than web-first**, with Android and Windows as co-primary
initial clients. References in the source material to “Android first” should not
be read as making the Windows client secondary.

## The name

The project is named **Gilune** — a coined name, pronounced *gih-LOON*
with a hard G. Its primary inspiration is globe gilia (*Gilia
capitata*, also called bluehead gilia), which produces dense,
ball-shaped clusters of many small pale-blue flowers. The symbolism:
each tiny flower is an individual thought, capture, or memory; together
they form a larger, coherent whole. Gilune has no literal dictionary
translation — it is an abstract botanical name carrying the intended
idea: **small fragments of thought gathered into something whole**
(owner, 2026-08-30).

The project was previously called *Linoema* (from *Linum*, the flax genus, and
Greek *noēma*, that which is thought — “threads of thought”: captures preserved
as they occurred, then connected and woven into an enduring body of memory).
Before Linoema, the working title was the **Memory Project**, which the product
principles in [`sources/README.md`](../sources/README.md) still carry and from
which the `memory`/`org.memory` contract namespaces derive. This package
predates the rename; references to either earlier name in source material
should be read as Gilune.

## How to read this package

1. [`01-constitution.md`](01-constitution.md) defines the non-negotiable
   invariants.
2. [`02-kernel-contract.md`](02-kernel-contract.md) draws the kernel, platform,
   and plugin boundaries.
3. [`03-data-model.md`](03-data-model.md) defines the durable data envelopes,
   provenance, blobs, and deletion behaviour.
4. [`04-processing-model.md`](04-processing-model.md) defines transactions,
   events, jobs, retries, and background processing.
5. [`05-plugin-system.md`](05-plugin-system.md) defines plugin contracts,
   capabilities, packaging, and UI extensions.
6. [`06-sync-and-security.md`](06-sync-and-security.md) defines what must remain
   stable across devices and server implementations.
7. [`07-compatibility.md`](07-compatibility.md) defines API, schema, migration,
   and uninstall behaviour.
8. [`09-engineering-conventions.md`](09-engineering-conventions.md) defines how
   the code itself is written: structure, comments, DRY/SOLID as boundary
   discipline, per-language rules.
9. [`decisions/`](decisions/) contains Architecture Decision Records (ADRs).
10. [`spikes/README.md`](spikes/README.md) defines the prototypes that must pass
    before the proposed stack is adopted.
11. [`prior-art.md`](prior-art.md) maps existing systems to the mechanisms
    worth adopting from each; spikes cite and verify it.

## Status vocabulary

- **Baseline**: an architectural invariant. Changing it requires an explicit
  product-level decision, not an implementation refactor.
- **Draft**: the contract is concrete enough to prototype but can still change
  as the spikes expose problems.
- **Proposed ADR**: a technology or implementation choice awaiting evidence.
- **Accepted ADR**: the default for v0. It can be superseded only by another
  ADR that documents the migration consequences.

## Current decision map

| Decision | Status | Validation |
| --- | --- | --- |
| Local-first, source-preserving architecture | Baseline | Capture/recovery spike |
| Small kernel with capability-mediated plugins | Baseline | Plugin spike |
| Durable, at-least-once event processing | Accepted for v0 | Capture/recovery spike |
| Kotlin Multiplatform/Compose application shell | Accepted for v0 (provisional; device budgets assumed) | Kernel integration spike |
| Rust portable kernel | Accepted for v0 (provisional; device budgets assumed) | Kernel integration spike |
| SQLite plus transactional blob storage | Accepted for v0 | Capture/recovery spike |
| WIT contracts and WebAssembly component plugins | Proposed | Plugin spike |
| Bundled mobile plugin execution | Accepted for v0 | Plugin and policy spikes |
| Installable sandboxed desktop/server components | Proposed | Plugin spike |
| Operation-based sync with an opaque encrypted relay | Proposed | Sync spike |
| Declarative third-party UI contributions | Accepted for v0 | Plugin spike |
| Markdown vault projection; the vault is the export | Accepted for v0 | Capture/recovery spike |
| Headless sync agent with LAN-first device sync | Proposed | Kernel and sync spikes |
| MIT licensing repository-wide (single licence since 2026-09-01) | Accepted | Owner ratification (2026-08-30, amended 2026-09-01) |
| Downloadable store plugins via the sandboxed runtime | Accepted (direction; store test submission gates promises) | S5 research + future test submission |

## What is intentionally not selected yet

The next domain delivery is planned in
[`People and shared document refactoring`](plans/people-and-document-refactoring.md).
It records the agreed plain-text direction, proposed public contracts, ordered
milestones and acceptance scenarios. It is a plan, not an implemented feature
or an accepted replacement for the existing contracts.

The following choices are kept outside the foundation because they are intended
to be replaceable:

- transcription, language, embedding, and routing models;
- hosted AI providers;
- vector indexing engine;
- sync network transport;
- server web framework and hosting vendor;
- marketplace and discovery service;
- domain schemas beyond the sample plugins used to validate the architecture.

## Exit gate

The foundation acceptance review the spike plan requires is prepared in
[`foundation-review.md`](foundation-review.md) (2026-08-30) — ADR
dispositions, the constitution-to-evidence trace, consolidated gates, and
a go recommendation — awaiting the owner sitting.

Feature development beyond the vertical slice should not depend on the proposed
stack until the spikes demonstrate all of the following:

- a capture survives termination at every persistence boundary;
- duplicate and reordered deliveries cannot corrupt state;
- a denied, crashed, upgraded, disabled, or removed plugin cannot damage or
  strand user data;
- the same plugin contract is usable by a bundled Android implementation and a
  sandboxed desktop component;
- two offline devices converge without copying a live SQLite database;
- complete export works with domain plugins disabled;
- the Rust boundary is buildable, debuggable, and operationally tolerable on
  Android and Windows. If not, the kernel-language ADR must be replaced before
  feature work continues.
