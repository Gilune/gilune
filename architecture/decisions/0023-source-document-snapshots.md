# ADR 0023: Exact source snapshots and shared Markdown locations

Date: 2026-09-05
Status: Implemented. Journalled apply/recovery extends this foundation in [ADR 0026](0026-shared-editor-and-document-refactoring.md).

## Problem

The People/refactoring plan requires independent plugins to attach meaning to the
same Markdown item and propose precise plain-text changes. Existing index bodies
normalise newlines, while source files can contain CRLF, BOM and Unicode. A line
in that derived text is insufficient evidence for rewriting original bytes.

Visible revision markers can also outlive their content: an external editor can
change a projected file before reconciliation. Treating the marker alone as proof
of a source revision would attach new evidence to an old revision. Metadata-free
Markdown must participate without being adopted just to prepare a rename.

## Decision

Introduce the pure `gilune-document` crate for immutable full-source text,
UTF-8 byte spans, shared Markdown structure and validated text proposals. The
kernel provides read-only vault snapshots with source versions qualified by
local database identity, exact content, path and observed object/projection/head
state. UniFFI exposes the same values and offset conversions to both shells.

Common structure identifies document root, physical body lines and whole
Markdown list items. Continuations belong to their item and nested items retain
separate ownership. Plugins interpret vocabulary over these locations; there
is no dependency between People and another domain plugin. Item keys are valid
only inside a content version, without implying persistent inline-object IDs.

A managed revision ID is reported only for verified current projection bytes.
Changed bytes, head movement, copied identity, quarantine, conflict and deletion
have explicit states. Reading and preparing changes do not reconcile, adopt,
project or write. Search continues to use indexed content and never calls the
snapshot file-reading service.

Proposals require exact source versions and valid non-overlapping Unicode byte
ranges. Kernel metadata and envelope boundaries are protected by source ranges
and resolved-value checks. Unsupported property source shapes with reserved keys
protect the whole header rather than exposing guessed writable ranges.

The [document contract](../../contracts/documents/README.md) defines the versioned
parser behaviour, exclusions, metadata rules, public APIs and conformance tests.
It records pinned parser dependencies so a structural change can invalidate
future persisted maps deliberately.

## Consequences and remaining gates

- The pure crate enforces one useful dependency boundary now. Kotlin SDK/domain
  packaging and the existing Tasks registrations still need their planned move.
- Full source snapshots consume document-sized memory and require a file read
  when preparing an edit. This cost is outside interactive search matching.
- Rich YAML validation and byte preservation do not yet replace the existing
  projection/reconciliation property parser. Typed page round-trip remains P1.
- No filesystem transaction, write lease, revision commit, journal, open-buffer
  coordination, undo or recovery guarantee is implied by preparation. P4 must
  implement and fault-test these before a refactoring apply API is delivered.
- Shared Markdown locations exist at the source boundary; index persistence and
  query selection must migrate explicitly before claiming whole-item matching.
- Conformance tests exercise unrelated document labels through the pure crate,
  kernel and generated Kotlin bindings. They cover nested lists, a 10,001-item
  map, exact CRLF/Unicode, metadata structure, stale sources, vault isolation,
  unchanged-file/head movement and unmanaged preparation without writes.

This extends ADRs 0012, 0020 and 0021 without changing their current write or
query semantics. The implementation evidence in the
[delivery plan](../plans/people-and-document-refactoring.md#11-implementation-evidence--source-document-foundation-2026-09-05)
records platform checks and outstanding work.


## Implementation addendum — projection properties (2026-09-05)

Projection/reconciliation now preserves a versioned raw header in revision payloads,
updates only generated kernel metadata, and retains unknown properties through
rebuilds and ordinary sync operations. Processor revisions preserve this envelope.
The document contract records the supported YAML source shapes and compatibility
limits. Valid foreign YAML without reserved identity remains unmanaged; existing
quarantine records still require an explicit recovery path.

This closes the body-only ingestion loss path identified during P1. It does not
introduce typed-page creation, an ownership registry or a property editor. Older
projection binaries do not implement the envelope; mixed-version writer gating
remains a release requirement. No SQL or FFI signature change is introduced here.
