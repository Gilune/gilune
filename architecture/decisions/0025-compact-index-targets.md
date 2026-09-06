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
Replacing a source map detaches old bindings and replaces targets;
only facts for the replacement source version can rebind. Failed producers retain
their previous facts and failure evidence, without joining them to new targets.
A text-only rebuild can rebind facts for unchanged bytes without invoking plugins.
Moves, deletion, producer removal and transaction rollback use the same lifecycle.
Namespace removal clears its predicate dictionary; a full rebuild clears all of it.

IDs are not exported object identity, durable task identity or permission tokens.
They are not reused within an index lifetime; rebuilds have separate index
identity/generation. Public results retain path, source version and location.
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
