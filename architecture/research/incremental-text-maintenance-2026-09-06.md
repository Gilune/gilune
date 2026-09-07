# Incremental text-index maintenance

Date: 2026-09-06
Status: Implemented and validated; incremental fact maintenance remains next.

The first implementation step replaces the text index's delete-all/insert-all
update with a duplicate-sensitive comparison of the old and new text rows.
Plugin recognition and fact replacement still process the complete document.

## Change and boundaries

The kernel first retains exact field/line/text matches, then pairs remaining
identical field/text rows at new coordinates. Matching is bounded by the edited
document, with ordered maps rather than quadratic scans. Unchanged entries keep
their private FTS row IDs. Moved entries update only the line coordinate; changed
text uses the existing FTS insert/delete triggers. Text is folded only for new
entries. Field boundaries prevent a name/path entry being reused as body text.

This requires no edit history from the shell. Ordinary reconciliation supplies
the complete new snapshot, including changes made by an external editor. The
parser remains authoritative for Markdown context and target boundaries. These
private text-row matches do not assert persistent task identity or reuse plugin
facts across revisions.

Source-map replacement still invalidates old target bindings. An exactly
unchanged source map can retain its existing targets. Failure rolls back source
maps, bindings, text and FTS in the existing producer transaction; its cursor is
advanced only on successful commit. Deletion and full rebuild retain their
existing paths. No schema, plugin contract or FFI signature changes are required.

## Evidence

Focused conformance covers a 10,000-item single-line change, an inserted prefix,
duplicate lines, Unicode/CRLF, changed Markdown context, deleted content, FTS
external-content integrity, stale facts, failure rollback, path isolation and the
transaction requirement. A one-line edit retains 10,002 rows and replaces one;
the unchanged repeat performs no text-row writes.

The manual comparison alternates old/new execution order over five edit/restore
trials at 100, 1,000 and 10,000 items. It measures source parsing, source-map/target
replacement, text/FTS maintenance and durable WAL commit on separate databases.
It excludes plugin facts and application reconciliation. Full replacement remains
the comparison oracle. Complete application measurements are recorded separately.

### Isolated maintenance comparison

[All five trials per case](incremental-text-native-2026-09-06.json) are retained,
including slower samples. Medians in milliseconds:

| Items | Single-line edit, full | Single-line edit, differential | Prefix insertion, full | Prefix insertion, differential |
| --- | ---: | ---: | ---: | ---: |
| 100 | 6.49 | 3.14 | 6.09 | 3.40 |
| 1,000 | 83.20 | 15.52 | 81.47 | 16.87 |
| 10,000 | 1,018.24 | 202.19 | 1,057.20 | 236.45 |

For 10,000 items, a single-line edit replaces one FTS entry and retains 10,002.
Prefix insertion adds one entry and moves 10,001 existing physical lines,
including the trailing empty line. It does not rebuild those FTS entries.
These timings include the existing full source-map/target replacement; they
are not timings for the text-row comparison alone. Some small-fixture trials
show long pauses; all samples remain visible and no OS-cold budget is claimed.

### Complete application edits

The [real application run](incremental-text-updates-2026-09-06.json) uses both
People and Tasks through the rebuilt Windows DLL. Each trial changes one date,
reconciles and reindexes the document, then restores the source. Builds and other
large workloads were kept separate from benchmarks.

| Tasks in document | Previous full-text replacement median | Differential text median | Differential range |
| --- | ---: | ---: | ---: |
| 100 | 75.0 ms | 67.5 ms | 49.8–125.8 ms |
| 1,000 | 305.9 ms | 230.6 ms | 224.3–259.2 ms |
| 10,000 | 3.097 s | 2.100 s | 2.082–2.169 s |

The large-document median is **32.2% lower** than the preceding run. The previous
and current application medians are separate three-trial runs, with different
warm-up behavior visible in the callback timings. The alternating native control
independently demonstrates the avoided storage work; component and application
times must not be added together or treated as identical fixtures.

After the final restore, a [duplicate-sensitive fact audit](incremental-text-fact-equivalence-2026-09-06.json)
matches all **63,505 facts** against the frozen earlier fixture, with no missing
or additional values, invalid/unbound current targets or unused locations.
The control's synthetic source versions differ; actual version/location bindings
are checked separately against current source maps.

### Validation and delivery

- [570 native tests passed](incremental-text-validation-2026-09-06.json), with
  22 ignored/opt-in, in a complete serial release-workspace run.
- The manual six-case old/new maintenance benchmark passed separately.
- Release workspace/all-target Clippy passed with warnings denied.
- 633 desktop tests passed, five opt-in tests skipped. Android application and
  instrumented-test APK builds passed. No device was attached for runtime checks.
- Windows and both Android kernels were rebuilt/staged with Whisper. Bindings
  independently generated from each binary match the existing public bindings:
  `76006804D81C752D0862B7598BC3602F55DB91EA4D09E49AD46CF277DAA6427C`.
- The tested Windows DLL matches its packaged resource, SHA-256
  `569EC67264270CDA30242172BBF84C9C450368661C3786244A08EB72B1E0ED66`.

New profiling phases distinguish `diff_text_rows_fts` and
`replace_source_map_targets`; the profile summarizer preserves the old phases
for historical full-replacement runs.

## Next boundary

The remaining expensive work includes source-target replacement and complete
producer fact replacement. Reusing those values needs a source-version-safe
mapping that preserves lagging producers' old evidence; moving text entries alone
does not solve that problem. Plugin callbacks remain independent and generic.
