# Document refactoring — v0.1

The kernel owns reviewed document changes and recovery. Domain plugins own
recognition, candidate predicates, proposed edits and definition policy. People
is one consumer; `plugins:contractFixture` supplies an independent label rename.
Neither contract contains a person, task, project or relationship type.

## Provider and host boundary

`RefactoringRegistration` contributes actions, validates an intent, supplies
bounded indexed candidate queries and proposes `SourceTextEdit` values against
immutable snapshots. It receives no filesystem walker, writable store or global
kernel handle. Only the host can invoke the journal/apply APIs.

The host reconciles external changes, pages indexed candidates, and checks all
Markdown files within the reviewed folder scope. Inaccessible, unsupported,
quarantined, dirty and missing indexed sources produce explicit issues. No
display-result cap limits discovery. Planning reads source files; search does
not. Cancelling discovery leaves an incomplete preview with an issue and requires
a new discovery before the defining document can complete.

The host checks enabled provider/version/settings and original/destination
definition uniqueness before applying. People applies the defining page last,
after every other reviewed entry succeeds. A folder scope includes its defining
page; completion describes that scope. Excluded documents remain pending and
block definition completion until included or a new scope is reviewed.

## Immutable review and durable execution

`create_refactoring_plan` starts discovery. `add_refactoring_entry` validates full
UTF-8 byte ranges, expected source version and protected metadata, and records
exact before/proposed/published bytes. `add_refactoring_issue` records an explicit
unreviewable path. `seal_refactoring_plan` freezes entries and returns a review
token binding the provider/configuration, sources and changes. Entries page in
batches of 1–100; the application uses 20 for previews and application.

`apply_refactoring_entry` requires the exact plan token and entry ownership. The
source version binds current bytes and managed head identity. Applied entries
are idempotent: retry refreshes the index without creating another revision.
Cancellation stops future writes and retains committed progress. A changed
source is a conflict; preparing a new preview is the resolution path.

Managed edits create normal revisions, operations, projection receipts and
change-feed notifications. Original capture blobs and prior revisions remain
unchanged. Metadata-free Markdown remains unmanaged: changes have a local journal
and change-feed receipt, but no invented object identity or sync operation.
Such files continue to use whatever file transfer mechanism already transports
them. This API does not silently adopt them to provide revision sync.

The shared editor gate serialises host writes with autosave. Dirty open buffers
are rejected until saved and reviewed again. Clean editors reload through the
existing change feed; stale autosaves retain dirty content instead of replacing
the refactored file. Reload uses each editor's existing history reset behaviour;
the durable bulk undo is the history for the committed refactoring.

## Filesystem protocol and concurrency limit

The local journal commits before any file replacement. A process-wide filesystem
lease prevents two refactoring publishers. The kernel fsyncs prepared bytes,
records `applying`, verifies the source inside a database write transaction,
displaces the original inode to recovery storage, compares its actual bytes,
publishes without replacing an intervening file, and checks both inodes again.
The revision/operation receipt and `applied` entry commit in one transaction.
Index maintenance follows that commit; retry/reopen repairs derived state.

Recovery never calls a plugin or automatically replays a proposed change. An
uncommitted publication is rolled back only when it is still safe; newer files
and displaced bytes are retained. Recovery copies can be inspected and restored
through another version-checked preview, including after plugin removal.

No protocol can serialise an arbitrary external editor that ignores locks. In
particular, a process retaining an old file handle can write to the displaced
inode after commit. That inode remains in recovery storage; it is not silently
merged into the current document. The Refactor history exposes retained copies
and explicit restoration. Ordinary permission bits are preserved on publication;
platform-specific extended metadata is not a byte-preservation guarantee.

## Undo, sync and retention

Undo creates a new reviewed plan. It preserves current kernel metadata and
requires a unique anchored inverse within the corresponding current editable
region. An inverse property edit cannot bind to identical body prose. Later edits
outside the affected evidence survive. Changes inside that evidence, duplicate
anchors, or modified metadata structures become explicit issues. The inverse is
conservative: it can require manual resolution even for logically independent
edits inside a large changed region.

Journals, leases and retained copies are device-local. Only ordinary managed
document operations sync; another device does not execute the plan. Offline
revisions and out-of-order operation delivery retain normal sync/conflict
semantics. Origin-device undo can run after provider removal. A new device needs
the origin device's local evidence to perform that particular bulk undo.

Evidence is retained until the vault's local application data is removed. This
version has no automatic expiry or per-plan purge. Disk use grows with full
before/after documents and retained inodes; it is intentionally not a compact
long-term backup format. Exported current Markdown remains authoritative without
the journal, plugins or derived index.

## Compatibility and evidence

Durable database schema 9 adds local plan/entry tables; schema 8 migrates through
the normal append-only migration. The derived index schema is unchanged. Native
bindings must be generated from their matching binary. The bundled Kotlin source
contract is executable; downloadable/WIT plugin execution remains deferred.

`refactoring::tests` exercises revision idempotence, unmanaged files, exact review
tokens, external-write windows, five subprocess crash windows, protected metadata
undo, retained-byte restoration and two-device offline/out-of-order sync.
`DocumentRefactoringTest` exercises People and an unrelated consumer, YAML/body
renames, merge/retirement, dirty buffers, selection, cancellation, late references,
removal, full enumeration and export/rebuild. Shared UI tests cover preview,
apply and undo on desktop and Android.
