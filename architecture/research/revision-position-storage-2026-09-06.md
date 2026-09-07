# Revision position storage

Date: 2026-09-06
Status: **Test-only experiment. Production remains on index schema 11.**

The owner asked us to remove the cost of moving coordinates after a local edit,
while keeping queries at least as fast. This experiment establishes a storage
direction; it does not report prototype component timings as total edit latency.

## What the stored values actually do

| Stored value | Current use | Proposed treatment |
| --- | --- | --- |
| Target ID, predicate ID, typed fact value | Indexed filtering, joins between independent plugin facts, sorting by a fact | Keep the existing compact records and indexes |
| Item start used as `local_key` | Document order and continuation tie-breaker, as well as a public byte location | Separate private order from public position |
| Start/end and own ranges | Snippets, text evaluation, navigation, refactoring evidence | Read from a revision's compact position blocks when needed |
| Physical line number and byte range | Finding indexed prose and projecting a source location | Keep text addressed by a private line slot; resolve current number/range from positions |
| Per-location source version | Preventing facts from referring to a different revision | Share a source frame, advanced only after every affected producer validates fresh output |
| Source map and producer receipts | Parser context and current-index completeness | Retain these guarantees; changing representation must not validate a failed producer |

The problem is duplication of position and revision data in many indexed rows.
A longer first line changes the absolute offsets of thousands of untouched
items. Those are location changes, not new task or People facts. Query matching
does not need to rewrite or inspect every absolute position to keep working.

## Experiment

The test implementation is
[`position_prototype.rs`](../../kernel/crates/gilune-kernel/src/search/position_prototype.rs).
It is compiled only for native tests. It contains no plugin names or semantics.

- Preserve the existing fact filtering, indexes, count and selection paths.
- Add a private position slot to each item target.
- Encode line positions and item ranges into a binary map, split into 4 KiB
  SQLite BLOB rows. Fixed 128-byte item records contain the common first own
  range and first-line coordinates; extra ranges use a separate tail. Nested
  items retain their separate own ranges. Line and item blocks have separate
  address ranges so an item's slot resolves without loading a directory.
- Load only referenced blocks. A cache is reset on every query, keeps one source
  at a time, and holds at most 256 blocks (1 MiB). Sequential scans fetch up to
  four neighbouring blocks together. No warm cross-query geometry
  cache is needed to obtain the reported results.
- Project returned items and evaluate text from these blocks and indexed prose.
  All original public byte ranges, line numbers, snippets, facts and cursors
  must equal the existing executor's results.
- An isolated result reads its 128-byte record directly through SQL `substr`,
  without materialising a whole block in Rust. Reuse document metadata already
  fetched to construct the containing search hit. This removes repeated metadata
  queries as well as the old coordinate lookups; projection timings measure both
  changes rather than attributing everything to the storage encoding alone.
- For a length-only edit, publish updated blocks and one revision record in a
  SQLite transaction. Unchanged block contents are not updated.

The first experiment used a single whole-document BLOB. It passed all semantic
comparisons but added work to a grouped query returning one item from the large
document. It was superseded by bounded blocks and direct position slots. The
intermediate logs remain available; they are not the selected design's results.

## Measurement boundaries

Query comparisons use actual Kotlin-exported DSL requests from the earlier
53-case matrix. Current and experimental execution alternate within each case
on the same database and native binary. Full results/counts and page-only
results/cursors are compared, and source-file reads must remain zero. Separate
runs cover 50,000 tasks across 500 documents and 10,000 tasks in one document.

The write comparison invokes the current `advance_coordinate_slots` path,
including its layout encoding, coordinate matching and per-location revision
updates. The experimental path includes target-slot enumeration, binary encoding,
block comparison/writes and revision publication. Both start with parsed before
and after snapshots, commit using WAL with `synchronous=FULL`, and restore their
inputs outside the timed section. This is a position-publication component
comparison. It excludes parsing, plugin work, full fact validation/comparison,
text maintenance, reconciliation and operation/history work.

The write fixture inserts ` longer 😀` into the first task's text in the retained
10,000-task document. Every experimental item position and own range is checked
against the new parser layout. The fact values and private selection rows are
unchanged by that experimental publication. No production source files are edited
by the native query or write experiments; they use database copies.

## Results

All samples and DSL strings are retained in
[`revision-position-results-2026-09-06.json`](revision-position-results-2026-09-06.json).
The final write run measured **225.27 ms median for current coordinate
publication versus 13.25 ms for position blocks**, about 17 times faster.
Seven samples ranged from 223.37–234.52 ms and 11.49–18.55 ms respectively.
Changed SQLite rows fell from **51,005 to 376**: 375 geometry blocks and one
revision record. The encoded map is 1,533,952 bytes. This is not final index
disk size; the experimental databases still contain the old representation.

Representative page-only medians, including result construction:

| Fixture and request | Current ms | Position blocks ms |
| --- | ---: | ---: |
| 10k in one document: all tasks, first 50 | 2.48 | 2.06 |
| 10k: Alice's open tasks due in September, first 50 | 2.82 | 2.44 |
| 10k: same query sorted by due date, first 50 | 9.54 | 8.98 |
| 10k: all tasks, first result (15 alternating samples) | 0.218 | 0.212 |
| 50k across 500 documents: all tasks, first 50 | 4.24 | 3.34 |
| 50k: Alice's open September tasks, first 50 | 5.14 | 4.27 |
| 50k: same query, page 19 of 50 results | 5.40 | 4.54 |
| 50k: same query, first 500 | 28.18 | 19.15 |
| 50k: broad item text search, first 50 | 308.54 | 283.21 |

The Alice request is the actual exported DSL:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

It matches 3,500 items in the 10k fixture and 17,500 in the 50k fixture. Sorting
is the request's separate `org.memory.tasks.due` sort contribution. Page-only
figures exclude the separately available exact count. The JSON also retains
complete/count-inclusive timings; those must not be confused with page latency.

Both 53-case matrices passed exact result, count, snippet, source range and
continuation comparisons. Additional runs cover page 19, 500 results, one result
and grouped queries. All assert zero source-file reads. No repeatable material
query regression was found in these measured paths. Some initial medians were
slightly higher, including queries whose matching SQL was unchanged. Follow-up
21-sample alternating runs measured grouped Alice pages at 122.10 versus 120.63
ms, grouped due-sorted pages at 137.33 versus 135.46 ms, and a single due-sorted
result at 73.76 versus 73.37 ms. The raw initial and repeat samples are retained.
This is measured evidence on these fixtures, not a universal latency guarantee.

The native correctness fixture additionally covers nested items, quoted lists,
fences, duplicate lines, CRLF, Unicode and missing final newlines. The original
schema-11 vault fixture still contains the same 63,505 facts with no missing,
additional, unbound or incorrectly qualified facts after the experiments; see
[`local-input-fact-equivalence-2026-09-06.json`](local-input-fact-equivalence-2026-09-06.json).

The selected decoder/prefetch version passed release workspace Clippy with
warnings denied. The complete release workspace suite passed **583 tests, zero
failures, 24 ignored/opt-in**. The final native checks completed on 2026-09-07.
The normal packaged Windows DLL was restored and verified after the workspace
test build; the experimental position reader is not present in that DLL.
The preceding local-input changes also completed their desktop and Android build
checks: 635 desktop tests passed, both native Android ABIs and their bindings
rebuilt, and app/test APKs compiled. No Android device was attached; runtime
device checks remain unrun. These checks validate the schema-11 runtime, not a
production deployment of this position-storage experiment.

For context, the final real Kotlin/FFI timing run still measures about 695 ms
for a warm longer-line edit on schema 11. Its coordinate step is about 204 ms,
with another 126 ms of source-map/target maintenance and 154 ms of full fact
comparison/receipts/index work. Those are separately measured production spans,
not timings of a complete save using the prototype. See the
[local-input profile](local-input-publication-2026-09-06.md#validation).

## Delivery design

1. **Keep compact fact selection.** Introduce private order labels and position
   slots in the target records. Queries continue joining fact values by target ID.
   A private order label must preserve document order but need not equal a byte
   offset. Existing generation-qualified continuations expire after an edit.
2. **Separate evidence from order.** The SDK continues accepting and returning
   revision-qualified absolute locations. The kernel translates them to/from
   private slots. Plugins do not acquire knowledge of position storage.
3. **Share revision validity.** Normalise repeated path/revision identity into
   source frames. A fully prepared coherent publication can advance one shared
   frame after all owners validate their results. If an owner is absent or fails,
   keep its old frame/evidence and detach it from current targets. Use the existing
   conservative replacement path rather than rebinding stale facts.
4. **Publish geometry in blocks.** Store current byte/line/range data in small
   records outside the fact selection indexes. Metadata and blocks become visible
   atomically. Readers stay within their SQLite snapshot. Retain old frame data
   while any stale evidence still refers to it; garbage collect only unreferenced
   frames. Index rebuild remains a valid recovery path.
5. **Handle insertions without renumbering everything.** Allocate private slots
   independently of document order. New items receive slots and an order label
   between neighbours; deletions free slots. Rebalance a bounded region when
   labels run out. Slot reuse is a storage choice, never a stable domain identity.
   No hidden task identity or historical task-tracking promise is introduced.
6. **Address prose by slots too.** A prefix paragraph must not force every indexed
   text row to acquire a different physical line number. Preserve FTS row IDs and
   unchanged prose; resolve line numbers through the position map. Preserve the
   legacy line-query API by projecting its public numbers after selection.
7. **Migrate every reader.** Item projection/text scanning alone is insufficient.
   Scoped location queries, root/line lookup, legacy anchor queries, sorting,
   pagination, navigation and refactoring snapshots must use the same position
   resolver and source-frame checks. Avoid a compatibility view that computes
   absolute positions for every candidate before filtering.

## Required gates before production replacement

- The current experiment preserves existing target order keys. Implement and test
  insertion/deletion/reordering and bounded relabelling before claiming that the
  production index no longer shifts indexed coordinates.
- The experimental revision row models a shared frame but does not migrate
  `fact_locations` or failure recovery. Prove missing/failed/removed producers,
  revision revisits and crash rollback with the real publication lifecycle.
- Freeze a versioned binary encoding with checked bounds, corruption detection,
  byte/Unicode limits, and deterministic rebuild/migration fixtures. The test
  decoder deliberately assumes its own encoder's valid data.
- Repeat the complete DSL matrix and query-plan checks after removing the old
  coordinate columns, not only while the prototype coexists with them. Include
  tiny/large documents, 1/50/500 results, late pages, full enumeration, text and
  opaque residuals, grouped queries, and all public location lookup routes.
- Record end-to-end edits for value changes, longer lines, prefix insertion,
  deletion and nested-structure changes. The rest of the pipeline still assembles
  and compares complete fact output; position savings alone do not prove a final
  edit latency target.
- Validate native/FFI/shell behaviour and source evidence after migration, and
  measure final disk size without the duplicate experimental tables/columns.

This direction removes mass updates from the indexed selection records without
moving domain interpretation into the kernel. It still rewrites compact geometry
blocks after a length change. Strictly local geometry writes could later use
relative block bases, but they are not required to test the much larger saving
from eliminating thousands of indexed coordinate/revision mutations first.
