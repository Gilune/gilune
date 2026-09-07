# ADR 0025: Compact source-qualified index targets

Date: 2026-09-06
Status: Compact storage and query migration implemented and validated. Android runtime validation remains unavailable; see the [delivery report](../research/compact-migration-2026-09-06.md).

## Problem and evidence

The [cross-plugin benchmark](../research/cross-plugin-search-2026-09-06.md)
exposed repeated full-candidate processing and projection before pagination.
The [records/facts comparison](../research/record-index-prototype-2026-09-06.md)
shows that compact joins, bounded SQL selection and projection after selection
can address this without requiring a physical table for each plugin.

Joining contributions must preserve their source revision. A producer can fail
after another producer has already processed new bytes at the same path and
offset. An integer identifier must never make those revisions appear compatible.

## Decision

Derived index schema 6 assigns private integer IDs to sources, snapshot-local
targets and `(namespace, predicate)` pairs. A target describes a document root,
body line or Markdown item using the existing shared parser's byte ranges and
map version. Plugins continue to emit their own facts through existing contracts.
Neither target storage nor its maintenance interprets domain predicates.
The source map's parser version is a stored generated column, computed once on
write. Reading it from the full JSON per target caused superlinear maintenance
in the large-document profile and is intentionally avoided.

`facts.target_id` binds only when path, source version, location kind and location
key match the current source map. Unversioned legacy facts retain a null target
binding. Predicates are qualified by producer namespace even when their visible
names coincide. The kernel owns both dictionaries and their indexes.

Schema 7 resolves predicate IDs once per distinct owned predicate and target IDs
once per referenced location in the existing producer write transaction. The
shared writer inserts those IDs with the facts, avoiding a second SQL update and
index maintenance for every row. Small batches use individual indexed target
lookups; larger batches read the requested target kinds and retain referenced
locations only. No binding cache survives a transaction or crosses producer
commits; callbacks still run outside the write transaction.

All production fact inserts use this writer, including legacy callback output.
The old insert trigger is retained only in comparison fixtures: a guarded trigger
still incurred substantial measured overhead even when its body did not run.
Source-map lifecycle triggers maintain derived associations when sources change.
Replacing a source map detaches old bindings and maintains current targets;
only facts for the replacement source version can rebind. Failed producers retain
their previous facts and failure evidence, without joining them to new targets.
A text-only rebuild can rebind facts for unchanged bytes without invoking plugins.
Moves, deletion, producer removal and transaction rollback use the same lifecycle.
Namespace removal clears its predicate dictionary; a full rebuild clears all of it.

IDs are not exported object identity, durable task identity or permission tokens.
Deleted target IDs are not reallocated within an index lifetime; schema 10 can
retain a live coordinate slot across revisions while invalidating old bindings.
Rebuilds have separate index identity/generation. Public results retain path,
source version and location.
Producer receipts and index completeness remain necessary, especially for absence
queries. An ID join alone does not prove a producer has finished indexing.

## Incremental adoption

The location-query API now projects ranges from target rows rather than decoding
an entire source map for each document in a page. Selection, ordering, completeness
and public pagination semantics remain unchanged. This is the first production
consumer of the shared target foundation.

The existing wide fact columns, indexes and source-map JSON remain during executor
integration. Two partial covering indexes support target/predicate/value and
predicate/value/target access for version-bound facts. This deliberately adds
temporary storage and write cost; it is not the final compact storage footprint.
Measure that cost before removing compatibility representations once all consumers
have migrated. Do not infer whole-index savings from isolated prototype stores.

Opening an older derived schema discards and rebuilds the index through the normal
lifecycle. Markdown, plugin settings, durable object data and public plugin callback
versions do not change. This is not a People-to-Tasks dependency or domain migration.

## Production fact-query route

Schema 8 adds indexes on physical line coordinates and target source byte starts.
The public item-search route uses source-qualified target IDs for fact-only
requests without text highlighting/ranking. The kernel compiles generic predicates
and value operations, including numeric comparisons, membership, whole-clause
negation and explicit exact/root/inherited scope. Value comparison semantics are
shared with the existing wide executor; no plugin-specific query branch is added.

Default ordering selects a bounded page in path/item-key order. Fact ordering
visits the value index first and checks the independent minimum value per target,
then fills from a separate missing-value phase. A filter witness need not be the
minimum sort value. Duplicate facts and coincident predicates from different
owners do not duplicate targets. Grouped queries aggregate matching target IDs
and load only the requested snippets for returned documents. Small exact positive
sets can bound candidates through capped value-index probes.

Projection reads only selected targets, their current facts, root facts and the
indexed physical lines overlapping their own ranges. It does not decode whole
source maps or whole document bodies. Returned text retains the existing CRLF,
Unicode, nested-item, indentation and trailing-blank-line behavior.

The current API still requires an exact total, so count and selection run in the
same read snapshot. Count avoids sorting/projection but remains synchronous.
Text/relevance and residual-callback item queries, and ordinary document/line
search, retain the existing route until their complete semantics migrate.

## Remaining query design

One shared declarative executor should select bounded target IDs, apply ordering
and continuation in SQL, and project only the returned page. It must preserve
explicit root/item scope, text/residual behavior, producer completeness, generation
checks and all supported query semantics. Scalar fact producers remain supported.
Structured records can later add typed correlated fields without prescribing a
universal physical table layout in the plugin API.

Exact counts need an explicit generation-bound result/state and independent read
execution so a slow count does not block a first page through the Store mutex.
Cancellation, count invalidation and shell presentation belong in this integration.
Fact-only Tasks queries now consume the generic target executor. This does not
establish performance for unconverted query shapes or independent counts.

## Verification

Target conformance tests exercise CRLF/Unicode ranges, independent producers,
same-offset revision changes with a failed producer, text-only rebuild, moves,
removal/deletion, rollback, and derived schema-version rebuild without source edits.
The rebuild fixture changes prior version markers (5, 6 and 7); it is not an archived
full database fixture. Existing scoped tests also cover more than 10,000 items
and zero query-time source reads.

Batch writer conformance compares every logical fact and binding with compatibility
inserts, including duplicate values, owned predicates, root/line/item locations,
Unicode, stale and unversioned facts. It exercises large batches, target replacement
between transactions, and failure after a partial insert: facts, dictionary entries
and producer receipts roll back together. Ordinary batch inserts perform no
additional per-row update.

Target query conformance compares complete ordered pages and continuations against
the previous executor over all fact operations, scopes, multi-valued sorts,
independent owners, grouped/zero-snippet results and incomplete source receipts.
Bounded-projection instrumentation checks that a two-item page from 3,000 indexed
items reconstructs only two items and two lines. Public cursor expiry and
query-time filesystem isolation remain covered.

The opt-in `target_metadata_maintenance_profile` isolates map replacement with and
without target maintenance at 100, 1,000 and 10,000 items. It excludes parsing,
fact writes, filesystem reconciliation and durable I/O; it cannot establish an
end-to-end indexing budget. Delivery evidence belongs in the implementation plan.

## Schema 9: complete physical storage integration

Replace the wide physical fact table with `fact_values` plus an interned
`fact_locations` dictionary. Predicate ownership remains in `index_predicates`.
A read projection preserves legacy anchor APIs over these same tables. Source
version and location remain available for stale/unversioned producers without
promoting them into current targets. Ordinary writes resolve dictionaries once
per batch and write compact rows directly. Existing per-source transactions,
receipts, rollback and source lifecycle remain authoritative. Collect orphaned
location entries only when their final fact has been removed.

All item execution uses targets, including text and residual predicates. Lookup
intersects target IDs and probes narrowed roots. Declarative document/line pages
without relevance use bounded SQL selection; legacy coordinate semantics are
projected from normalized facts. Relevance and opaque callbacks still require
candidate evaluation with bounded retained results. This is an execution cost,
not a second storage model. Raw source-map layout remains necessary evidence.

The versioned search contract adds immutable query handles, nullable pending
counts, independent read-only connections, generation-bound exact counts and
cancellation. The shells publish pages independently of counts and discard
obsolete results. Existing exact-total APIs remain compatible. No domain-specific
predicate, People/Tasks dependency or plugin-owned SQL table is introduced.

Current evidence and measured limitations are recorded in the
[migration report](../research/compact-migration-2026-09-06.md). Earlier sections
record the incremental rollout; their wide-storage and pending-count descriptions
apply to schemas 6–8, not the current representation.

## Schema 10: differential target and fact maintenance

Source-map updates retain target rows at matching kind/local keys, update only
changed metadata, and add/remove changed keys. The existing detach/rebind boundary
is retained: a private target ID surviving an update cannot cause a failed
producer's old facts to join to the new source. Reader transactions retain their
previous WAL snapshot, and public source-version references do not change meaning.

Producer commits compare their complete ordered output with stored fact rows.
Only changed value columns are replaced; unchanged values keep their rows while
location/target columns advance as required. Additions/removals change row counts.
This is a positional comparison within a producer, preserving duplicate counts
and output order. Inserts/reordering can update later positions; it is not a
minimal-diff or task-identity algorithm. Values are not shared across producers,
and one producer's receipt cannot advance another producer's evidence.

The public callback/query contracts and physical fact tables are unchanged.
Schema 10 rebuilds old disposable indexes to install the target maintenance
triggers. Source parsing and plugin recognition still run over the complete
document. Revision changes still incur binding work proportional to the facts;
removing that cost would require a separately proven provenance representation.

The [implementation report](../research/incremental-fact-target-maintenance-2026-09-06.md)
records conformance and complete application measurements: the fresh 10,000-task
comparison improves from 1.733 to 1.652 seconds (4.7%). Binding maintenance and
durable commits remain substantial costs; row reuse alone does not make updates
proportional to changed task values.

## Schema 11: coherent source publication and local derivation

The [local-input report](../research/local-input-publication-2026-09-06.md)
records an optional callback contract that derives only selected locations.
The kernel compares local bytes and parser context, reuses successful outputs
and relocates them when their coordinates shift. Cache loss requires complete
derivation; it never changes source identity or commit eligibility.

For aligned one-path edits, prepare all available producer outputs before the
write transaction. Recheck cursors/versions inside it. Shared locations can
advance only with fresh output from every owning producer. Otherwise ordinary
invalidation preserves missing/failed owners' old evidence. Within a coherent
publication, target slots can move and fact comparison is local to reused
locations. SQL failure rolls back the publication; callback failures retain
independent namespace results. No callback executes inside the transaction.

Absolute byte ranges and per-location revision bindings still cause broad
metadata writes. Block-relative positions and coarser validity records remain
unimplemented investigation topics. Query SQL and public source identities are
unchanged; schema 11 rebuilds disposable indexes to install the new triggers.

The subsequent [position-storage experiment](../research/revision-position-storage-2026-09-06.md)
tests keeping fact selection records stable while storing revision positions in
bounded blocks. Its coordinate-publication component and query comparisons are
promising, but it is compiled only for tests. It does not amend this ADR's
production schema or establish a shared-frame failure/recovery contract; those
delivery gates are recorded in plan section 37.

## Schema 12: shared revision frames

Normalise the repeated path/revision pair into `fact_frames`, referenced by
`fact_location_slots`. The existing `fact_locations` shape becomes a relational
view. Compact selection still joins opaque values directly by target ID; it
does not resolve positions or join frames before filtering.

Only the existing coherent-publication eligibility check can advance a frame:
every owner must have freshly validated output for the new revision. Missing or
failed owners retain old evidence through conservative replacement. Destination
frame collisions also use that path. Removed anchors stay at the old revision
until their owning producers replace them. Garbage collection removes only
unreferenced locations/frames. All mutations share the existing transaction and
WAL snapshot boundary. Older indexes rebuild without rewriting vault documents.

This is the first production part of the position-storage delivery. Absolute
coordinate writes and target ordering remain unchanged; binary position blocks
are still test-only. Measurements and limits are recorded in the
[shared-frame report](../research/shared-revision-frames-2026-09-07.md).

## Schema 13: relative position blocks and private order

Store source coordinates in small, relative SQL blocks. `target_slots` holds
private target IDs, sparse ordering labels and block-relative anchors/ranges;
`position_blocks` supplies the current absolute bases. The `index_targets` view
projects the previous public coordinate shape. This replaces the experimental
binary encoding with relational records: filtering keeps its direct target/value
indexes, while point and range lookups use indexed block predecessors. No binary
map decoder or document-sized read cache is required.

Initial blocks contain at most 64 targets of one kind. Coherent edits retain
blocks, split locally above 128 members and coalesce adjacent small runs.
Moving a prefix changes block bases
and records in the affected block, rather than all following indexed coordinates.
Sparse integer order labels survive ordinary insertions/deletions; exhausted gaps
redistribute a bounded neighbourhood. Arbitrary reordering or parser-context
changes can legitimately require broader work. These labels are private cursor
ordering data, never task/person identity. A new index generation invalidates
old cursors, and result coordinates always describe the returned source revision.

Current fact locations and indexed body text can refer to retained target slots.
Unchanged text keeps its FTS address, and unchanged facts keep their value rows.
Missing/failed producer evidence is materialised at its original coordinates and
revision before live geometry changes. Equal-layout fallback may retain geometry
IDs, but it still detaches old evidence. Removed targets materialise coordinates
before deletion. No unavailable producer implicitly advances with a shared slot.

Geometry preparation, source replacement, fact comparison and receipts remain
inside the existing write transaction. A transaction-local preparation marker
binds the prepared geometry to the exact new layout and is cleared by source
publication. Rollback restores every block, slot, binding and marker. Independent
WAL readers retain their prior source-qualified snapshot. Old disposable indexes
rebuild through the existing schema-version mechanism; source documents and
public plugin contracts are unchanged.

Projection resolves only returned targets. Per-page document metadata is reused;
item snippets read their own bounded line ranges. Sequential text evaluation
retains its bounded line window. Legacy line queries still use indexed literal
locations where required, without making source-file reads part of search.

Delivery measurements and validation are recorded in the
[position-block report](../research/position-block-delivery-2026-09-07.md).
