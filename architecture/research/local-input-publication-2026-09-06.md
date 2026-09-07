# Local plugin inputs and coherent index publication

Date: 2026-09-06
Status: Implemented; native/desktop tests and Android builds validated on 2026-09-07.

The owner asked to reduce the work caused by one edit in a 10,000-task document,
including carrying changed parser inputs through plugin recognition. This phase
adds an opt-in local-input contract and reduces database write amplification.
Full Markdown parsing still establishes context; it no longer requires opted-in
plugins to recognise every task or reference again.

## Mechanism

The kernel fingerprints each location's permitted inputs: metadata, own source
bytes and shared parser context. It reuses successful output for unchanged inputs,
including shifted identical inputs, and calls a local producer only for changed
locations. Root, item and line dependency rules are explicit in the
[indexer contract](../../contracts/indexer/README.md#optional-local-scoped-input).
People and Tasks implement this independently. Plugins requiring whole-document
context keep the existing contract. This does not add domain logic to the kernel.

The registration cache retains one document within a 32 MiB estimated payload
limit. A cold registration, eviction or failed callback requires full recognition.
Changed headers invalidate dependent inputs, and a fence or other parser-context
change can invalidate locations far beyond the edited line. The assembled output
still goes through normal fact validation and commit checks.

For an aligned, complete feed page naming one path, producer outputs are prepared
before the write transaction. Versions/cursors are checked again after acquiring
the writer. Shared locations advance only if every owner has fresh output for
that source. Missing or failed owners keep their original evidence; successful
owners can still advance through the normal invalidation protocol. SQL failure
rolls back the publication. Query readers retain their previous WAL snapshot.

Within that transaction, the kernel retains and moves private coordinate slots,
then compares fact rows within each reused location. A new or removed item no
longer shifts fact comparison across all later items. First-line text matching is
only a slot-allocation preference; validated fresh outputs determine all values.
Source identity remains revision-qualified, and no permanent task ID is invented.
Schema 11 rebuilds derived indexes to install the new triggers; query SQL, result
identities and domain documents do not change.

## Complete application measurements

The existing Kotlin/FFI fixture uses both real plugins and a 10,000-task Markdown
file with portable connection frontmatter. It times `reconcile`, including index
maintenance, and restores the source after each edit. These are separate process
runs with three samples each and no competing builds. The first sample starts
without a warm local-input cache; the two later edits reuse that cache.

| Edit near the beginning | Cold sample (ms) | Warm samples (ms) | All-sample median (ms) |
| --- | ---: | --- | ---: |
| Same-length due-date change | 790.3 | 384.4, 380.3 | 384.4 |
| Longer line, including emoji | 1,012.3 | 704.0, 681.8 | 704.0 |
| Insert a prefix paragraph | 1,184.5 | 772.3, 764.7 | 772.3 |
| Delete one task line | 1,193.4 | 750.0, 749.3 | 750.0 |

The preceding implementation measured **1,651.5 ms median** for the same-length
edit in its earlier run. This phase's median is about **76.7% lower**. This is a
comparison of recorded runs, not a same-binary isolation of every optimisation.
These figures do not establish a latency budget or cover all managed-page save
paths; they measure the retained plain-text fixture described above.

A warm value/longer edit selects just two locations for each producer: the line
and its containing item. Tasks returns two facts and People four, versus 19,500
and 44,004 facts in the full callback. A warm prefix insertion selects two lines
and returns no new plugin facts. Pure deletion needs no callback when every
remaining input can be reused. All final sources are restored for equivalence
checking; the cache is not needed to reconstruct current facts after a rebuild.

An initial longer-line trial exposed roughly ten seconds of storage work despite
local callbacks. It used tuple `NOT IN` membership to remove shifted targets.
The final path uses a single non-null composite membership key and retains moved
coordinate slots, removing that expensive path. The initial trial is retained
as diagnostic evidence, not presented as the previous shipped baseline.

## Remaining costs

Coordinates are absolute full-source byte offsets; item keys are starts, and
line numbers are also stored. A length-changing edit shifts later starts/ends
and own ranges. We now preserve fact values, but still update those coordinate
records and per-location source revisions. Fact assembly/validation/comparison
also still visits the complete output. The reported callback time alone is not
the complete pre-database processing time.

Genuinely local storage work will require a separately validated representation
for relative positions and revision validity, rather than interpreting these
sub-second results as completion of incremental indexing. No block-position or
revision-frame redesign is implemented in this phase.

## Validation

Final native and desktop checks completed on 2026-09-07: **583 native tests
passed** (24 ignored/opt-in, including the subsequent position experiment),
**635 desktop tests passed** (five opt-in methods skipped), and release workspace
Clippy passed with warnings denied. The desktop suite used the normal schema-11
release DLL, SHA-256
`E466F6742E9AAD6D7BCE36FD54C90FCC962D35234896B05BDAA0466E9A02458A`.

The [duplicate-sensitive fact audit](local-input-fact-equivalence-2026-09-06.json)
checks all 63,505 restored facts against the frozen fixture, plus current source
versions and coordinates. It found no missing/additional facts, invalid/unbound
targets or unused locations.

The [enabled native timing run](local-input-profile-2026-09-06.json) records a
longer-line edit through the real Kotlin/FFI path. Its two warm samples average
695.24 ms (cold: 1,115.22 ms). Warm stage medians are:

| Stage | ms |
| --- | ---: |
| Snapshot parsing | 4.40 |
| Plugin invocation, adapter and reused-output assembly | 62.96 |
| Full fact validation | 10.89 |
| Coordinate-slot updates | 204.29 |
| Remaining source-map/target maintenance | 125.61 |
| Fact comparison, receipts and indexes | 154.36 |
| Text/FTS comparison | 10.59 |
| Durable commits | 60.65 |

The local-input comparison spans total 10.21 ms, nested inside plugin invocation;
do not add them to that parent. Other orchestration and publication work is not
fully represented by this table. Parser/callback speed alone therefore does not
describe the full edit path.

The subsequent [position-storage experiment](revision-position-storage-2026-09-06.md)
tests replacing coordinate publication with bounded geometry blocks. It remains
test-only; these complete-edit timings still describe production schema 11.
Both Android ABIs (`arm64-v8a`, `x86_64`) rebuilt with Whisper, and bindings were
regenerated from the Android binary. App and instrumentation APK builds passed.
No Android device was attached, so device execution was not run. Final counts
and binary hashes are in
[the validation record](local-input-validation-2026-09-06.json). The normal
Windows release DLL was restored after profiling and verified against its hash.
