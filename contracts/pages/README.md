# Typed pages — v0.4

Namespace: `memory:pages`. Rust/UniFFI and the bundled Kotlin SDK are the executable
contract. Bundled plugins can contribute standard collections and creation forms.
Downloadable plugin execution remains deferred.

## Declaration and ownership

A plugin contributes `pages.types` entries through its ordinary durable manifest.
The contribution ID is `<plugin-id>/<local-type>`, following the data model's type
identity form. Plugin IDs contain ASCII letters, digits, dots, underscores or
hyphens; local types contain letters, digits, underscores or hyphens. This makes
new page namespaces disjoint without consulting another domain plugin. The
contribution label is the display name. JSON config contains `version: 1`, a
positive integer `schema`, `default_folder` and literal Markdown `template`.
The [card fixture](fixtures/card-v1.json) is executed by native conformance tests.

`page_definitions(plugin_id)` validates that plugin's contributions when consumed.
Malformed/unknown versions, unsupported templates, duplicate IDs and foreign
namespace claims fail. Returned definitions include a fingerprint. Creation
checks it inside its transaction so a stale form cannot silently use changed
schema/defaults. Discovery requires an enabled plugin; creation additionally
requires a declared and granted `pages.create` capability.

The folder and template are initial defaults, not restrictions on user input.
There is no template expression language. Portable user overrides become explicit
source/path input as described below.

## Portable defaults

An owner can declare a second `pages.types` contribution with `defaults_for`
naming its target page type. Both types must be declared by that owner. A target
has at most one defaults type; self references, chains and foreign targets are
invalid. This is an opt-in framework convention, not a People schema. Defaults
use the normal `pages.create` authority and revision/publication protocol.

One live document of that companion type supplies the effective defaults. Its
string `folder` property is a portable relative folder (`""` means the vault
root). Its entire body is the literal full-source Markdown template, which can
itself start with frontmatter. Unknown configuration properties are preserved.
For example, before the kernel adds identity metadata:

```markdown
---
folder: Contacts
---
---
category: personal
---
# Conversation notes

```

Lookup uses current object revisions in the kernel database, independent of
derived search indexes, filenames and plugin callbacks. No second configuration
store or settings-file walker is introduced. Returned definitions include
`defaults_type_id`, an optional current `defaults_path`, and `defaults_problem`.
Their fingerprints include the companion declaration, selected revisions and
resolution state. A bad configuration blocks creation for its target; it does
not silently restore shipped defaults. The normal editor can still repair it.
Duplicate objects and multiple heads produce a diagnostic with candidate paths
or IDs; there is no first-match winner. Local duplicate creation is refused in
the creation transaction; offline duplicates remain preserved after sync.

The bound form service reconciles the particular known defaults documents before
loading a form. Creation compares the definition fingerprint in its transaction
and checks any published defaults file against its recorded projection digest.
Unreconciled external edits or changed defaults require reload/review. These are
configuration reads for creation, never query-time vault reads. As with other
file checks, this is not a lock against an external edit immediately after the
check. Unpublished defaults must be projected before creating dependent pages.
Unsaved editor content is not yet a saved default; moved files are discovered by
normal reconciliation. Unmanaged lookalikes are not automatically adopted.

Both shells offer **Page defaults** in collections. First use saves a companion
document under its declared folder; subsequent use opens it in the standard
Markdown editor. The file's folder property and body remain editable elsewhere.
Deletion requires normal tombstone resolution before shipped defaults resume.
Disabling/removing the owner retains documents/history; re-enabling resolves the
same saved defaults. Sync/reprojection preserves them without a search index.
Existing pages are never rewritten. An already committed creation retry still
returns its original receipt after defaults change.

The SDK `PageCreationService` is bound by the host to one plugin. Callbacks cannot
supply another owner, walk files, change registries or edit existing objects through
that service. Existing processor APIs are not a newly implemented global schema
ownership registry. Bundled Kotlin is trusted execution, not a security sandbox;
hosts must not supply a raw global kernel handle as the callback contract.

## Creation and publication

`create_page(plugin_id, request)` accepts a type ID, definition fingerprint,
request UUID, explicit relative Markdown path and full editable source. The kernel
supplies identity, type/schema metadata and timestamps. Source cannot introduce
reserved `gilune-*` fields. Unknown properties and body use the
[preserved document envelope](../documents/README.md); there is no parallel editable
property map. Unsupported managed YAML source shapes fail before committing.

One database transaction validates authority, declaration, request identity and
path availability, then commits object, revision/head, ordinary `object-created`
operation and `org.memory.page-created` event. No processor job or fake derivation
represents user authorship. If the encoded operation exceeds the default sync
limit, the whole transaction rolls back. Peers with stricter limits may still
refuse normally.

A receipt means **durably committed**, not yet published. The normal projection
pass publishes the file and updates the change feed/index. A fully written,
fsynced temporary file is hard-linked to the destination to publish without
replacement. A file appearing after the initial check cannot be overwritten.
An occupied destination raises the object-specific `vault-projection-path-occupied`
alert; resolving the collision lets projection publish the same saved page and
clear the alert. First projections generally use this rule, including synced pages.

Kernel-owned `_gilune_page` revision data contains `version: 1`, requested `path`,
`owner` plugin ID and `request_fingerprint`. Device/plugin identity and a normalised
request UUID deterministically name the initial object/revision. An identical retry
returns its receipt; different input reusing that UUID is refused. Retrying after
edits/deletion never resets history or recreates the object. Retries still require
the enabled, granted plugin. Initial revisions are idempotency evidence that any
future history pruning must retain.

Unpublished committed paths are reserved against other local creations. Paths
reject traversal, kernel-private folders, device names, Windows-special characters,
symlinks and junctions. Inspection does not prevent a hostile process swapping
ancestor directories concurrently. The general existing-file replacement and
refactoring protocol remains P4; this contract covers first publication only.
Filesystems without hard links leave the durable page pending and return an I/O
error; supporting them requires an equivalent tested no-replace primitive. Orphan
temporary files after interruption are not silently deleted by this API.

## One-time initial page sets

`pages.initializers` is an opt-in declaration consumed by `initialize_pages` and
the bound SDK service's `initialize()`. Config version 1 contains `receipt_type`,
`receipt_file`, and `pages`. Each page specifies a stable `key`, owned `type_id`,
`relative_file`, and a `properties` map of YAML scalar source values. All page and
receipt types must be declared by the same plugin. Receipt types are dedicated
to one initializer and cannot be seeded by another. At most 32 pages and 64
properties per page are accepted. Unknown config fields/versions are rejected.
The SDK's `pageInitializerContribution` builds these declarations; People and an
unrelated Cards fixture consume the same public contract.

An enabled owner with declared/granted `pages.create` may initialize. Each seed
uses its type's effective folder and full-source template, with declared scalar
properties applied through the source-preserving document service. The receipt
is ordinary Markdown under its own declared folder, identifying the initializer,
result and created object IDs/paths. All seeds and the receipt commit together in
one database transaction using normal page creation, revisions and operations.
Failure rolls back that set; projection publishes afterward under the existing
no-replace protocol. Sets belonging to separate initializers commit independently.

Existing receipt-type object history, including deleted objects, returns
`recorded` without reading vault files. It intentionally does not infer a reset
from changed/deleted receipt text or a new plugin version. Receipt object/revision
history must remain available under any future pruning policy. This is setup
history, not an alternative interpretation of current connections.

Before first initialization, the kernel checks existing target-type history.
When history does not already prove that a catalogue exists, it reconciles source
changes and inspects known import/quarantine paths through
the generic source snapshot API, in bounded pages. This reads files during
initial setup, independently of search: legacy frontmatter facts retain raw YAML
spelling and cannot reliably resolve quoted or aliased type values. Unmanaged
documents are never adopted. Unreadable/malformed sources that prevent determining
the scope defer setup with a diagnostic. Later invocations with a receipt use no
source discovery. Normal search/pagination never invokes initialization.

Any prior target-type content, including deleted managed objects or exported
source claims, preserves the whole existing collection. Only a `preserved-existing`
receipt is created; missing defaults are not backfilled. A fresh collection returns
`initialized`. Both shells prepare enabled contributions on startup/enable and
before first collection display, outside query execution. Failures do not prevent
opening the vault or reading existing collections; the collection exposes retry.

Sync uses ordinary operations. A seed arriving before its receipt is sufficient
to suppress another seed set. Concurrent fresh initialization on offline devices
creates distinct preserved objects: occupied paths raise existing projection
collision alerts, and no winner or invisible merge is invented. Thus this is not
a distributed exactly-once lock. Source inspection also does not lock external
editors; path checks and no-replace publication preserve competing files. Receipt
and target type IDs must remain stable; changing them to reset initialization is
a migration/product decision, not an automatic upgrade policy.

v0.4 adds the initialization result/method across FFI and the bundled SDK. No SQL
migration or new sync operation kind is required. Native binaries/bindings must
be regenerated together.

## Persistence and compatibility

Pages render on replicas without the declaring plugin. Local moves retain identity
through existing reconciliation; the recorded projection path wins over the initial
requested path. Cross-device move propagation is not added here. Offline creations
can compete for a path: both objects remain preserved and collisions are reported.
Processor revisions retain both kernel-owned page and front-matter envelopes.

No SQL migration is needed. FFI gains additive records/methods and bindings must be
regenerated from each platform's own binary. Legacy revisions keep their existing
renderer. Older projection binaries do not understand these envelopes; mixed-version
writer compatibility remains a release gate, not a supported downgrade promise.

## Evidence and remaining work

Native tests cover creation/restart/retry, edited-page retry, request reuse,
permissions/lifecycle, namespace conflicts, stale definitions, unsafe paths and
identity, path reservation, delayed publication, replica reconstruction, local
moves, BOM/CRLF, operation-size rollback and publication without replacement.
An unrelated Cards plugin fixture consumes the SDK in Kotlin tests without People,
Tasks or host imports. Platform checks are recorded in delivery-plan section 14.

Editing structured YAML through forms, general
snapshot apply/recovery/undo, and People reference/connection workflows remain
open. See delivery-plan section 15 for the initial collection UI evidence.

## Standard collections and forms (bundled SDK)

`pages.collections` contributions bind a collection ID to an owned page type,
registered search type, title, creation label, ordered text fields and optional
presets. Pure callbacks prepare a `PageDraft` from user values and the effective
full-source template, and render a
`PageRow` from indexed root facts plus a fallback filename. They receive no host,
filesystem or database. The host verifies the collection and page type belong to
the same enabled owner and rejects duplicate collection IDs or field keys.
Navigation selects collections through the ordinary contribution registry on
both desktop and Android; there is no domain-name dispatch in the renderer.

The generic form loads the current page definition/fingerprint, lets the user
choose a folder, prepares exact source and publishes through scoped creation.
Retries of an uncertain commit/publication retain the same UUID and input while
the form remains open. Durable kernel creation evidence survives form dismissal
and restart; the existing projection alert reports an occupied publication path.
This is not the later multi-document refactoring journal or a durable form draft.

An optional identity predicate/value enables an indexed duplicate preflight across
all collection pages, including other folders. It is advisory under concurrent
and offline creation, not a uniqueness lock. Each displayed page's identifier has
an exact indexed count over the full collection; duplicate counts are surfaced
and never resolved by selecting the first match. Counts and rows must share one
index generation. There is currently one count query per distinct identifier on
each result page; no filesystem reads are used, but large-directory latency still
needs measurement before the P6 performance gate.

Bundled installation grants declared local `pages.create` on first installation,
following the host's installation-consent policy. Re-registration does not restore
a revoked grant or resurrect a removed plugin. Collections disappear with their
owner; existing Markdown, revisions and generic editing stay available.

Creation fields are standard single-line text inputs in v0.3. List rows open the
existing Markdown editor. Both editors now use the shared source-preserving property form for scalars;
structured YAML remains read-only in that form. Folder selection in the creation
form is per creation; Page defaults persists future defaults. The SDK's pure
`pageTemplateWithProperties` helper applies form-owned fields to the effective
template using source-preserving scalar edits. Form values take precedence;
unknown properties, comments and body survive. Unsupported property replacement
fails visibly. People owns which fields it supplies; the host/kernel has no
People field dispatch. Plugins can instead implement their own pure preparation.

v0.3 extends the definition record and changes the bundled prepare callback to
accept the template. Regenerate all target bindings from matching native binaries.
