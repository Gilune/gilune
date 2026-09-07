# Shared source-revision frames

Date: 2026-09-07
Status: **Production schema-12 foundation; position-block migration remains open.**

This delivers the revision-validity part of the
[position-storage design](revision-position-storage-2026-09-06.md).
It does not replace absolute coordinates with binary position blocks yet.

## Representation and publication

`fact_frames` interns each path/optional source-version pair once.
`fact_location_slots` stores each anchor using its frame ID. `fact_locations`
is a join view exposing the previous shape; `facts` remains a relational view.
No duplicate wide fact table is introduced. Normal producer batches intern
frames and slots directly, inside the existing write transaction.

The compact fact-selection indexes and query compiler are unchanged. Selection
still joins opaque values by target ID. It does not join frames or resolve
positions before filtering. Public source versions, byte ranges, line numbers,
snippets and continuations retain their existing meaning.

The existing coherent-publication check requires fresh validated output from
every owner before advancing the shared frame. Same-coordinate edits then
change one revision row, without updating location slots. Moving anchors still
update coordinates; temporary keys prevent collisions during permutations.
Removed or unsupported anchors retain an old frame until their owning producer
replaces the corresponding facts. Missing/failed owners and destination-frame
collisions use conservative replacement. No stale evidence becomes current
merely because a frame or private target slot can be reused.

Frames, coordinates, fact changes and receipts commit atomically. A reader's
existing WAL snapshot retains its previous revision. SQL failures roll back the
whole publication. Namespace/path cleanup and rebuild collect unreferenced
slots/frames without discarding a failed producer's evidence. Schema-11 tables
and schema-12 views are both handled explicitly by disposable-index reset;
vault documents are not rewritten for the schema change.

## Measurements

[Raw samples, plans and diagnostics](shared-revision-frames-results-2026-09-07.json)
retain both successful comparisons and the initial investigation. Benchmarks
use copies of the saved fixtures. They do not modify the originals.

For the 10,000-task document, isolated revision publication changes **10,001
rows to one**, with a median of **35.75 ms to 0.97 ms** over nine committed
samples. Both use file-backed SQLite, WAL and FULL synchronous commits. This
measures revision writes only, excluding parsing, callbacks, geometry, fact
comparison, text maintenance and reconciliation.

Both 53-case exported DSL matrices pass exact results, counts, snippets, source
ranges and continuation comparisons, with zero source-file reads. Additional
comparisons cover 1/500 results and page 19. All 118 configurations retain their
raw samples; supported fact-only cases also retain the identical SQL/count plan.
Representative page-only medians in milliseconds:

| Fixture / request | Previous locations | Shared frames |
| --- | ---: | ---: |
| 10k in one document: all, first 50 | 2.53 | 2.57 |
| 10k: Alice, open, September, first 50 | 3.03 | 3.09 |
| 50k in 500 documents: all, first 50 | 4.28 | 4.28 |
| 50k: Alice, open, September, first 50 | 5.14 | 5.16 |
| Same request, due sorted | 74.13 | 74.59 |
| Same request, page 19 | 5.60 | 5.65 |
| Same request, first 500 | 29.06 | 29.46 |
| 50k: broad body text, first 50 | 317.95 | 319.50 |

The Alice request is the actual exported DSL:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

It matches 3,500/17,500 items respectively. Sort remains a separate request
contribution. Page timings exclude the independently available exact count;
the JSON also includes count-inclusive timings.

Vacuumed database size changes from **15,642,624 to 13,791,232 bytes** for the
10k fixture and **77,754,368 to 68,509,696 bytes** for the 50k fixture, about
12% smaller. This compares the frame representation alone, with all other
fact/target/text storage retained. No duplicate position-prototype tables exist
in these copies. WAL size and the vault's source/history storage are excluded.

### Comparison controls

The initial two-live-reader comparison produced large, order-dependent timing
differences in both directions. Standalone runs selected identical indexes,
executed identical SQLite VM instruction counts and had comparable latency.
The final comparisons use one live reader at a time with an explicit identical
warm-up; opening/setup/warm-up are outside timing. Both schemas use the default
cache settings. No production query changes or increased cache budgets were
used to remove the discrepancy.

These controls isolate the representation cost. The exact cause of interference
in the two-reader harness is not established; those diagnostic samples remain
available rather than being presented as a storage improvement or a proven
SQLite planner regression. The measurements do not promise identical latency
under every multi-handle workload.

### Complete application edits

Fresh runs through the real Kotlin plugins and packaged Windows DLL use
identical copies of the 10,000-task fixture. The
[application samples and fact audit](shared-revision-frames-application-2026-09-07.json)
retain all three samples per operation. Warm medians use samples 1 and 2:

| Complete reconciliation / index maintenance | Schema 11 | Schema 12 |
| --- | ---: | ---: |
| Same-length value edit | 397.53 ms | 316.18 ms |
| Longer first task | 695.41 ms | 622.05 ms |
| Prefix paragraph insertion | 785.59 ms | 712.20 ms |
| Task deletion | 742.33 ms | 698.15 ms |

The first current value-edit sample was already warmed by the initial schema
rebuild; it must not be compared with the baseline's cold first callback.
Other cold samples are retained without presenting them as steady-state costs.
Both plugins still receive two selected locations for a warm value/longer edit;
no extra domain logic moved into the kernel. Files are restored after each
sample, and the original benchmark vault is untouched.

The restored schema-11/schema-12 copies contain the same **63,505 facts**,
including source versions. There are zero missing/additional facts, unbound or
incorrect bindings, unused locations or unused frames. SQLite quick-check passes.

## Validation and remaining work

The complete release workspace passes **586 tests, zero failures, 26 ignored**;
release workspace/all-target Clippy passes with warnings denied. Two multicast
tests initially failed inside the network sandbox, then passed as part of the
complete run with network access. No network implementation was changed.

The new normal Windows DLL includes Whisper and is staged in the application.
The complete desktop suite passes **635 tests, zero failures, five opt-in skips**.
The application JSON records both compared DLL hashes. Both Android native ABIs
build with Whisper, bindings are generated from the Android binary, and app/test
APKs compile. No Android device is attached, so runtime device checks remain
unrun. [Validation and artifact hashes](shared-revision-frames-validation-2026-09-07.json)
record these checks. The final diff check is clean.

Absolute item/line coordinates and target order keys still move after length
changes. The next production step remains private ordering plus position-block
storage and migration of every location reader, including indexed prose. Full
fact assembly/comparison and source-map processing are also still present.
The revision-write saving is not a claim that the complete edit pipeline is
local or that its final latency target has been reached.
