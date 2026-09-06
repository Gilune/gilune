# Source documents — v0.2

Status: implemented snapshot, Markdown-location, property forms and edit-preparation foundation.
Namespace: `memory:documents`. The Rust crate and UniFFI surface are the executable
contract for bundled consumers; a WIT package and journalled apply are deferred.

This contract supplies exact source coordinates to independent plugins. It does
not recognise tasks, people, connection keywords or any other domain vocabulary.
The pure `gilune-document` crate has no kernel, database, filesystem or shell
dependency. The kernel adds vault access and observed durable identity; the FFI
exposes that service and the same pure buffer parser to both shells.

## Content snapshots and coordinates

`TextSnapshot` owns immutable UTF-8 source text, its SHA-256 content fingerprint,
front-matter ranges, protected ranges and a versioned Markdown map. The FFI
representation is `SourceTextSnapshot`; `inspect_source_text` accepts an editor
buffer without opening a store or invoking a plugin.

All spans are half-open **full-source UTF-8 byte offsets**. No BOM, CRLF, whitespace
or Unicode normalisation occurs. `body_start` gives the explicit body offset.
Body-line numbers are one-based physical lines; a trailing newline leaves a final
empty line. `source_byte_to_utf16` and `source_utf16_to_byte` convert shell offsets
exactly and reject offsets inside a UTF-8 character or UTF-16 surrogate pair.

The map's `version` is 1. Item keys are their source start bytes, scoped to this
content version. They are not persistent object IDs and cannot establish identity
across arbitrary edits. Each item has a full span, optional parent key and disjoint
`own_ranges` excluding its children's complete spans. Continuation paragraphs and
lazy continuation lines belong to the item; nested items have separate ownership.
A source offset resolves to the deepest containing item, otherwise its body line;
front matter resolves to document root. This does not imply fact inheritance.

Rust snapshots bind lookup to their own source text. At the FFI boundary,
`source_location_at(text, offset)` is useful for a single caret. Consumers analysing
many tokens should use one snapshot's disjoint item ranges, rather than repeatedly
parsing the document. Rust's internal lookup is logarithmic in the mapped ranges.

The parser uses pinned [pulldown-cmark 0.13.4 source-offset events](https://docs.rs/pulldown-cmark/0.13.4/pulldown_cmark/struct.OffsetIter.html),
with table, strikethrough and task-list extensions enabled. Task-list syntax here
is Markdown structure, without any dependency on the Tasks plugin. `text_ranges`
contains raw ordinary-text event spans: fenced/indented code, inline code, HTML
blocks/tags, autolinks, email autolinks and link destinations are absent. Text
between inline HTML tags and explicit link labels can remain ordinary text.
Plugins must still apply their lexical rules to the raw source, including escaping,
bare URLs/email addresses and token boundaries; decoded event text is not an edit
coordinate system. Changes to structural semantics require a map-version change
and eventual index invalidation for consumers that persist the map.

## Metadata preservation

A front-matter envelope starts at byte zero (optionally after a UTF-8 BOM) with an
unindented `---` line and ends at the next unindented `---` line. Trailing whitespace
is allowed on delimiters; indented delimiters inside block scalars are content.
Malformed/unclosed envelopes or non-mapping YAML produce a typed error.

[serde_yaml_ng 0.10.0](https://docs.rs/serde_yaml_ng/0.10.0/serde_yaml_ng/) validates
YAML and resolves values for protected identity checks, including aliases and
merges. It never serialises the document. Unknown nested fields, spelling,
comments, ordering and body bytes remain intact unless an edit names them.
Plugins retain ownership of the meaning of their properties.

Opening/closing delimiters and root keys prefixed `gilune-` (case-insensitive) are
protected. Simple block-map entries have conservative source spans including
continuation bytes and following comments. Flow mappings or inherited reserved
keys without an independent source span protect the whole header; body edits
remain possible. Both the raw reserved entries and their resolved values must
remain unchanged, and the original delimiter locations must survive the edit.
This rejects inserting reserved keys, moving a closing delimiter through inserted
text, or changing an alias that would alter an untouched identity field.

This source mapper is not a general YAML concrete-syntax editor. Property forms
without a safe independent range should receive a whole-header/manual editing
flow in future typed-page tooling, not a guessed substring replacement.

The projection now retains a versioned raw front-matter envelope in each externally
edited revision, alongside its existing body and domain payload. It preserves
unknown properties, nested YAML, comments, quoting, BOM and physical newlines.
Only the kernel's seven generated metadata fields are updated from authoritative
object/head state. Those fields require independent single-line scalars; managed
flow mappings, top-level merges, anchored/aliased or multiline kernel fields are
held back rather than normalised. Unknown nested properties may use those YAML
forms without being interpreted as domain concepts by the kernel.

Valid foreign YAML with no reserved identity fields is an unmanaged import,
without adding metadata or creating an object. Existing quarantine records are
not cleared automatically. The source API continues to report them explicitly.
All-Markdown refactoring still requires recovery for those existing records.

The revision payload field `_gilune_front_matter` is kernel-owned and contains
`{"version":1,"source":"<exact header including delimiters>"}`. The body stays in
`text`; there is no second editable property map. Processor revisions inherit the
current envelope inside their commit transaction, so replacing a domain payload
cannot erase or forge it. Normal revision operations carry it to peers. Unknown
versions, invalid envelopes and envelopes containing body text are held back.
Old revisions without the field use the existing canonical renderer unchanged.

This is a pre-1.0 additive payload extension, with no SQL or FFI layout change.
Same-version replication is tested. Older projection implementations do not read
this envelope and can lose visible properties when rewriting files; a mixed-version
writer gate remains a release requirement before claiming that compatibility.
Typed-page creation/ownership, structured property editing and refactoring apply
are still undelivered. This preservation work does not grant a plugin write access.

## Vault snapshots and provenance

`Gilune.document_snapshot(rel_path)` reads a vault Markdown file without
reconciliation, projection, adoption, revision creation or index maintenance.
Read failures and malformed text are explicit errors. Search does not invoke this
service: these file reads prepare editing/navigation evidence, not query matching.

`DocumentSource` contains a document key, source-version token, portable relative
path, optional verified object/revision IDs and an explicit state:

| State | Meaning |
| --- | --- |
| Managed | Exact bytes match the recorded projection, its identity/revision markers and its single current head |
| Unmanaged | No tracked projection or reserved identity metadata; readable without adoption |
| NeedsReconciliation | Known object, but bytes or current head differ from the recorded projection |
| UnverifiedIdentity | Tracked identity is missing/changed, or untracked text carries reserved metadata |
| Quarantined | The path has an unresolved quarantine record |
| Conflicted | The tracked object has concurrent heads |
| Deleted | The tracked object is deleted |
| PendingDeletion | An open deletion proposal needs resolution |

Only `Managed` carries a verified revision ID. An externally edited file can keep
its old visible revision marker without falsely claiming those new bytes are that
revision. Object identity requires a tracked path with matching visible identity;
a copied file does not inherit identity merely by repeating another page's ID.
After normal reconciliation of a move, managed document keys retain object
identity. Unmanaged keys are path-based and make no move-stability promise.

The source version binds the local database epoch, exact content, path, identity,
projection record, current heads and relevant state in one database read
transaction. A proposal from another vault or a rebuilt database cannot be reused
merely because the same path contains identical text. It differs from the
content fingerprint: advancing a head invalidates the version even before the
file is projected. Treat both tokens as opaque; neither is a lease or a lock.

Paths must name Markdown inside the vault, outside `.gilune`. Absolute paths,
traversal, alternate data streams, ambiguous trailing spaces/dots, symbolic links and junctions
are rejected. Canonical confinement is checked before reading. These read-time
checks do not serialize a concurrent external editor or hostile filesystem
changes. The future apply protocol must preserve recoverable bytes and describe
its actual replacement guarantees separately.

## Validated proposals, without writes

`Gilune.prepare_document_change(path, expected_source_version, edits)` re-reads a
snapshot, checks its source version, and permits preparation only for managed or
unmanaged states. It returns the exact `before` snapshot and proposed `after`
content. The latter has a content fingerprint, **not a committed revision or new
source-version token**. The pure Rust `TextSnapshot.propose` performs the same
range/metadata validation against an immutable content snapshot.

Edits name exact ranges and replacement text. Invalid/out-of-bounds or non-character
boundaries, overlaps, equal-start ambiguous edits and protected metadata changes
are rejected before preparing any output. Adjacent edits are allowed. Edits are
applied in descending offset order and bytes outside their ranges are preserved.

Boundary errors distinguish `DocumentChanged` (re-preview), `DocumentInvalid`
(invalid source/ranges/metadata) and `DocumentUnavailable` (resolve document state).
Unreadable files remain storage errors with path details. Callers must not infer
success from a missing result.

**There is no apply or undo API in v0.1.** Preparation does not write a file, acquire
a write lease, coordinate editor buffers, create revisions or persist a journal.
P4 must supply those guarantees before refactoring uses this as a write path.
Unmanaged Markdown is included in preparation without inserting metadata, but its
journal/recovery/sync behaviour is still outstanding. Item locations are not yet
persisted by the index; existing fact/search line semantics remain unchanged.

## Conformance evidence

- `kernel/crates/gilune-document/src/tests.rs`: full-source Markdown scopes,
  code/link exclusions, 10,001 list items, CRLF/BOM/Unicode, nested YAML/aliases,
  protected metadata, exact proposals and offset conversion.
- `kernel/crates/gilune-kernel/src/documents/tests.rs`: managed/unmanaged identity,
  unchanged-file/head changes, external edits after preview, moves, copied IDs,
  quarantine/conflict/deletion states, invalid paths and no adoption/writes.
- `kernel/crates/gilune-ffi/src/documents.rs`: independent native-boundary consumer.
- `app/composeApp/src/jvmSharedTest/kotlin/org/gilune/app/DocumentSourceTest.kt`:
  the same public contract through generated bindings and the packaged kernel.

## Pure scalar properties and normalization

`read_source_properties(text, keys)` returns one result per requested key, retaining
order and duplicates. Kinds are `missing`, `string`, `boolean`, `number`, and `other`
(null, collections, or unsupported scalar tags). Only the middle three have a
value. YAML aliases/merges use the same parser as editable source snapshots;
malformed or duplicate-key mappings fail with `DocumentInvalid`. This helper does
not hash the body, build Markdown item locations, open files or lock a Gilune
handle. It is safe to call from a pure indexer callback; calling methods on a
Gilune handle from that callback remains forbidden.

`casefold_nfc` is an explicitly versioned equivalence primitive, not automatic
kernel identity semantics. It applies NFC, full non-Turkic case folding, then NFC.
The pinned implementation uses Unicode 9.0.0 case tables (`unicode-casefold 0.2.0`)
and Unicode 16.0.0 NFC tables (`unicode-normalization 0.1.24`). Plugins choose where
to use it. Table upgrades require explicit compatibility review and reindexing;
newer Unicode case pairs are not promised by these pinned tables. Display/source
spelling remains unchanged. Both target binaries provide the same pure function.

## Source-preserving property forms

`inspect_source_properties(text)` returns a content-version token and ordered
fields. Each field has its raw YAML scalar spelling, optional interpreted string
value and an `editable` flag. Ordinary string inputs can display their text and
encode a changed value as a quoted string, preserving its type (for example a
person named `true`). Read-only values are display representations, not text to
serialize back into the document.

`change_source_properties(text, expected_version, changes)` prepares a new source
snapshot. Each change names a property and either a single-line YAML scalar or
null to remove that property. It writes no file and commits no revision. Existing
properties are changed at their independently located value token; removal drops
the property line and its inline comment, retaining standalone comments and other
entries. New properties append before the closing delimiter, or create an envelope
when absent. New keys use a letter/underscore followed by letters, digits, dots,
underscores or hyphens. Removing the last property retains the empty envelope.

Kernel metadata, aliases, merges, flow mappings, collections, multiline values,
anchors and tags are not rewritten through these fields. Unsupported shapes are
read-only or refused. Unknown content stays in the original source; the API never
flattens the mapping and serializes it wholesale. Scalar changes preserve adjacent
comments, spacing, existing delimiters, BOM, line endings and body bytes. Header
additions follow its newline style; a new envelope defaults to LF while retaining
the body's bytes. Final parsing verifies each requested value has its intended
YAML interpretation and that protected metadata and body remain intact.

The existing snapshot proposal validator checks source versions, protected ranges,
reserved keys/values and exact identity bytes. Its identity signature excludes
trailing standalone comment/blank lines so deleting a neighbouring property can
leave those bytes intact without treating their new adjacency as an identity
change. The original protected ranges still prevent changing those existing bytes.

Both shells share the same property form. Editable fields appear first, with
identity/structured values available under read-only details. The form captures
full source, prepares changes on a worker, then applies only the reviewed header
to the current buffer. A header that changed while the form was open requires a
new review; later body edits and selection are retained. Android now includes
header changes in its dirty/save state. Editor splitting keeps an optional BOM in
the header/prelude so adding a header does not move it into the body.

Saving continues through each editor's existing guarded save and reconciliation
path, creating ordinary revisions and index updates. This API does not supply
atomic filesystem replacement, journalled apply, refactoring undo or an external
editor lock; those remain separate delivery work. Existing scalar properties can
be edited or removed; renaming references across documents remains the refactoring
workflow. Editing a handle/keyword property alone does not rewrite its occurrences.

## Generic metadata and source-map consumers

`SourceTextSnapshot.metadata` exposes parsed frontmatter as an arena of
`SourceValueNode` values with root at index zero. Kinds are `mapping`, `sequence`,
`string`, `number`, `boolean`, `null` and `tagged`. Scalar values retain their
types. Sequence children name value nodes; mapping children alternate key/value
nodes, retaining non-string keys. Tagged nodes carry a tag and one child. YAML
aliases and merges follow the same parser interpretation as other source APIs.
Numbers are textual values so the boundary does not truncate their precision.

The SDK's `SourceValue` provides typed traversal and string-key lookup, and
`SourceView` maps UTF-16 editor positions to full-source UTF-8 positions and shared
Markdown locations. Both operate only on supplied values. This is available to
every plugin; the kernel attaches no meaning to property names. The value tree
does not replace raw YAML or supply rewrite offsets for aliased/structured values.
