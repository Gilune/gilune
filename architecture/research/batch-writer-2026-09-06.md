# Production batch writer: complete update measurements

Date: 2026-09-06

The common fact writer is now the production insertion path for every producer.
It resolves private predicate and source-qualified target IDs inside the existing
write transaction and inserts them with each fact. The complete 10,000-task edit
falls from **6.202 to 2.595 seconds median (58% less time)**. This is the real
public reconcile call with Tasks and People, not a sum of isolated components.
The shared search executor and removal of redundant wide storage remain pending.

## Method and complete timings

The existing shape fixture has 10,000 tasks in total, partitioned into documents
containing 100, 1,000 or 10,000 tasks. Each trial changes one due date in one
document, reconciles through the application/kernel boundary, checks the settled
advance, then restores the original date. Three measured edits per shape; the
initial schema rebuild and restoration calls are outside the measured samples.
Both sides use the previously delivered bounded People normalization cache.
No heavy build or test workload ran alongside the timed trials.

| Tasks in edited document | Previous schema 6 median | Schema 7 batch writer median | New range |
| --- | ---: | ---: | ---: |
| 100 | 90.1 ms | 67.0 ms | 57.4–76.0 ms |
| 1,000 | 672.0 ms | 289.7 ms | 282.5–330.4 ms |
| 10,000 | 6,201.8 ms | 2,594.8 ms | 2,580.0–2,688.3 ms |

These are local Windows release measurements with opt-in phase instrumentation,
not OS-cold or Android timings. Three samples describe this run rather than a
latency percentile guarantee. File and commit variability particularly affects
the smaller documents. Settled follow-up advancement takes about 0.26–0.31 ms.

For the largest document, medians of individual phases are:

| Phase | Before | After |
| --- | ---: | ---: |
| Fact insert, receipt and index work | 4,107.4 ms | 597.7 ms |
| Text/FTS insertion | 723.1 ms | 725.1 ms |
| Durable commits | 467.5 ms | 487.8 ms |
| Source-map/target insertion | 127.2 ms | 122.5 ms |
| Source-map/target deletion | 164.7 ms | 161.3 ms |
| All storage transactions | 5,980.9 ms | 2,474.4 ms |

Some spans are nested: storage transactions include inserts, deletes and commits;
source-map deletion is part of deletion work. Do not add the table rows together.
Shared parsing remains about 4.5 ms. All 63,505 facts are still replaced when this
large document changes. This is faster full replacement, not incremental parsing
or individual-task mutation. No storage-size reduction is claimed in this step.

## Why the first integration did not deliver the expected gain

The first implementation retained the old insert trigger with a condition that
skipped its body when the writer supplied IDs. Conformance showed no extra fact
updates, but complete reconciliation still took 6.034 seconds median. The presence
of this guarded trigger remained expensive. Separate full-database write controls
isolated that difference while preserving wide and compact indexes, text/FTS,
source lifecycle triggers and durable commits.

The four controls retain the historical insertion strategy, the earlier batch-ID
prototype with both fact triggers removed, the real writer with a guarded insert
trigger, and the real writer with only that insert trigger removed. Removing just
the insert trigger gives **2.287 seconds median** in the isolated storage control,
close to the earlier prototype. The final complete application run above then
confirms the benefit through actual callbacks and validation. This establishes
the trigger's cost; it does not attribute every millisecond to a particular
SQLite VM opcode or operating-system operation.

Every production insertion already goes through the common writer, including
legacy body/line callback output, so the insert trigger is unnecessary. Its old
SQL remains in a comparison fixture. Source-map and identity-update triggers
remain for lifecycle correctness. Plugin authors never write these tables.

## Correctness and public boundaries

- IDs are resolved once per distinct owned predicate and referenced location.
  Small batches use indexed individual target lookups; larger ones read requested
  target kinds and retain only referenced locations.
- Bindings live only inside the producer's write transaction. No cache crosses
  commits, source replacement or namespace removal. Existing producer version,
  cursor, receipt, failure and rollback handling remains in the plan commit.
- Source revision, path and exact location must match. Stale or unversioned
  facts remain unbound; failed producers keep their previous evidence. Identical
  predicate text in two namespaces retains distinct ownership.
- No plugin syntax or domain semantics enters the writer. Callbacks remain
  outside write transactions and existing plugin APIs are unchanged.
- Derived schema 7 rebuilds older indexes without rewriting authored documents.
  There is no public FFI signature or plugin callback version change.

New conformance compares the writer with the old insert trigger across typed
values, duplicate facts, Unicode/CRLF, root/line/item locations, missing/stale
bindings and independent owners. It checks large batches, source replacement
between transactions, rejection of standalone writes, and rollback after a
partially inserted batch, including predicate dictionary entries and receipts.
The actual updated fixture retains all 63,505 facts with zero multiset differences
and zero invalid current bindings against preserved pre-optimization evidence.

## Evidence and next step

- [Complete update samples](batch-writer-update-2026-09-06.json).
- [Four SQL write controls](batch-writer-trigger-controls-2026-09-06.json).
- [Fact and binding equivalence](batch-writer-fact-equivalence-2026-09-06.json).
- [Raw phase/control measurements](batch-writer-2026-09-06.txt).
- [Previous complete profile](update-path-profile-2026-09-06.md).

Reproduce complete samples with `SearchShapeBenchmarkTest.profileExistingShapeEdits`,
`GILUNE_SHAPE_PROFILE` pointing to the shape vaults, `GILUNE_PROFILE_INDEX=1`, and
`'-Pgilune.kernel.profile=batch-writer-profile'` after building that diagnostic DLL.
Use `kernel/scripts/summarize-update-profile.ps1` to pair independently buffered
native/Kotlin timings. The native `profile_real_update_write_path` opt-in test
reproduces all four controls on disposable copies; it explicitly installs frozen
legacy SQL, independent of the production schema's current trigger definitions.

Next, integrate bounded selection/projection with complete query, count and cursor
semantics, then remove redundant wide storage/index work. Text/FTS replacement and
commit/deletion costs remain material. The current writer improves production
updates without claiming that the compact query migration is complete.

## Validation and packaged artifacts

- Release native workspace: **556 passed, 0 failed, 19 ignored/opt-in**.
  The four-mode native write control passed all 16 trials separately. Full-path
  application profiling also ran separately from the normal desktop suite.
- Release workspace/all-target Clippy and FFI all-target Clippy with
  `index-profile`: passed with warnings denied.
- Desktop against the final Whisper-enabled release kernel: **632 passed,
  0 failed, 5 opt-in methods skipped**. Android app and instrumented-test APK
  builds passed; no attached device was available for runtime testing.
- Windows and both Android ABIs rebuilt and staged with local transcription.
  Kotlin bindings generated independently from Windows, arm64-v8a and x86_64
  are identical to the existing bindings (SHA-256
  `2A8B580E9C260E91481E44B5F66C62EB966E346F7F81A0B3529ED838B90BE23E`).
- The tested normal Windows DLL and packaged resource are byte-identical
  (SHA-256 `822F8187C6E8AD8E16D43C6852DA76A876FC84DAEB083DAA0905F54319109D86`).
  Opt-in instrumentation stays in the separate diagnostic profile directory.

Local build, test, binding and profiling logs are in `app/build/batch-writer-profile`.
