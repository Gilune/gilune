# ADR 0007: Markdown Vault Projection

Status: **Accepted for v0** (foundation review, owner ratification
2026-08-30)  
Validation: Spike S2 (extended) — acceptance gate met on Linux and
Windows: projection, delete-and-rebuild, bidirectional reconciliation
including injected crash points, and closed-vault restore on an
independent tree. The copy-versus-reference blob measurement is moot in
the ratified layout (blobs live once, in the vault); the human-readable
timestamp format remains an open vault-format detail.

## Context

The product principles name Markdown and standard audio formats as the
interchange formats to honour, and openness is a product goal rather than a
concession: a user should be able to open their data with Obsidian or any other
file-based tool, and third-party editors are supported use (constitution
rule 13).

A reinvestigation considered making a folder of Markdown files the canonical
store. That conflicts with the constitution and accepted decisions: the primary
capture is an audio blob plus envelope metadata, a filesystem offers no atomic
multi-record commit for the capture path, mutable files cannot represent
immutable revisions with concurrent heads, and replicating files reintroduces
the last-write-wins model ADR 0005 rejects. Logseq's move away from
Markdown-as-source-of-truth to a database core is a recorded precedent for
these failure modes.

The legitimate demand behind the idea — visible, greppable, third-party-editable
files — is a property of what the user can see, not of what the kernel commits.
The model is a git repository: the working tree is the open, editable
projection; `.git` holds the transactional truth; external edits are reconciled
in as new history. See [`../prior-art.md`](../prior-art.md) for the systems
this pattern is drawn from (Obsidian's disposable cache, Jujutsu's
working-copy snapshots, Fossil's rebuildable tables, Logseq's dual-truth
failure).

## Decision

- The user's data directory is a **vault**: a folder of Markdown files with
  YAML front matter, original blobs in standard formats, and attachments,
  openable by third-party tools.
- The kernel store — SQLite database, blob store, operations, plugin-private
  state — lives inside the vault as a `.gilune/` directory, as `.git` lives
  inside a working tree. A closed vault directory is therefore a complete,
  self-contained copy of the user's memory, including history.
- The Markdown layer is a **continuously maintained projection** of committed
  kernel state, produced by a first-party projection plugin under the
  `every-device` execution policy. It is rebuildable at any time; the kernel
  store remains the sole transactional authority.
- Front matter carries envelope identity (object ID, type, schema version),
  provenance references, and relationship data; wikilinks additionally express
  relationships in human-readable form. Files are matched by front-matter ID,
  never by filename, so renames and moves are metadata changes.
- Reconciliation is **bidirectional**: a scan on startup, plus a filesystem
  watcher where the platform makes one reliable, ingests external changes. An
  edited file becomes a new revision with external-edit provenance; a deleted
  file becomes a proposed tombstone requiring confirmation; an unrecognised
  file becomes an import candidate; malformed or stripped front matter
  quarantines the file for a user decision. Reconciliation is never
  destructive to kernel history.
- The baseline export becomes a copy of the vault taken with the store
  quiescent, plus an integrity manifest carrying whatever the Markdown layer
  cannot. The vault is the export, continuously proven rather than
  periodically generated.
- The on-disk vault layout and front-matter schema are a versioned public
  format with the same compatibility discipline as the other contracts.
  Folder organisation and file naming are UX policy layered on the format.

## Alternatives considered

### Markdown files as the canonical store

Rejected: no atomic multi-file commit for the P0 capture path; mutable files
versus immutable revisions and concurrent heads; file replication versus
operation sync; and the primary capture is audio, which a Markdown folder
cannot represent alone.

### Kernel database as an index over canonical files

Rejected as dual truth. Operations, jobs, tombstones, and provenance cannot
live sanely in front matter, so content truth and history truth diverge — the
failure mode the Logseq precedent records.

### JSON export only, without a live vault

Rejected: satisfies portability formally but fails the openness goal. Export
remains an artifact users must remember to produce, and third-party editors
have nothing to open.

## Consequences

- Editing through third-party tools is a primary desktop path, so
  reconciliation is load-bearing and must be spike-validated, not assumed.
- Text is stored twice (kernel payload and projection); whether blobs are
  stored once in the vault and referenced, or duplicated, is measured in S2.
- A closed vault directory is a complete cold backup, satisfying the rule that
  database and blob backups form one logical snapshot.
- Third-party file sync of vault content is tolerated by design: the
  reconciler treats sync artifacts such as conflicted-copy files and partially
  arrived edits as ordinary external changes, never destructively. The one
  thing that must never be replicated live is the device-local database and
  staging state, per constitution rule 8: file-syncing an open SQLite
  database and WAL is a corruption vector. The vault layout must separate and
  clearly mark device-local state so excluding it is automatic rather than
  user discipline, and that state must be rebuildable. Copying a closed vault
  remains a fully supported backup and migration path, and shared-folder
  stores are additionally a planned sync transport (ADR 0008).
- Front-matter tampering, partial writes by other tools, and reconciliation
  during capture become adversarial test cases.
- The export contract in the data model and the S2 artefact list change
  accordingly.

## Acceptance gate

S2 is extended to require: every committed object projected into the vault;
deleting the Markdown layer and rebuilding it to an equivalent vault; external
edit, rename, delete, and malformed-front-matter cases reconciling without loss
of kernel history, including termination injected during reconciliation; and a
vault copied while closed restoring completely on another machine.

## Amendments

- **2026-09-04 (ADR 0020 § 2.5, K2): reconciliation stops reading what it
  can stat.** Reconciliation read and SHA-256'd **every** projected file on
  every pass to find external changes. It now records each file's size and
  modification time beside `content_sha256`, and a file whose size and
  mtime both match its record is not read; a mismatch is read and hashed
  exactly as before. This is git's index rule, adopted with git's
  racy-file safeguard: a file whose mtime is not older than the moment its
  record was written is hashed regardless, because the write and the
  record may have landed inside the filesystem's timestamp resolution, and
  the record is re-armed with a later moment when the hash says nothing
  changed, so the entry settles rather than being re-hashed for ever.

  **Openness is unchanged**, which is the point that had to survive: rule
  13 makes third-party editing supported use, and reconciliation still
  *looks* at every file on every pass. Looking is not reading. A file any
  other tool touches has a different size or a different timestamp and is
  read.

  Stated residual, inherited rather than introduced: a rewrite that
  preserves a file's length and lands with a timestamp the filesystem
  reports as older is invisible to a stat. The full hash of every
  projected file is therefore an explicit action — the CLI's `verify` —
  rather than a cost every launch pays, and `verify` re-arms any record it
  finds stale so the next reconciliation reads that file properly.
  Reporting without re-arming would be a diagnosis with no treatment.

  A targeted entry point, `reconcile_path(rel)`, reconciles one file to
  the same decisions the full pass would reach about it, so a writer's own
  save is taken in without a pass over the vault.

- **2026-10-22 (ratified by the owner as written, "Ratified as written.
  Build it."; built the same day): every file in the vault becomes
  something sync can carry, and a person can keep some local.** Found by the owner syncing a desktop and a notebook: notes
  made with New note, with Obsidian, or by copying a file in were
  "import candidates" on whichever device made them — recorded in the
  `imports` table, never an object, never an operation — so both
  devices said "up to date" while whole folders differed. The 2026-08-30
  decision left an import candidate waiting for "a user decision" that
  was never built; this amendment makes the decision automatic, and
  extends it to files that are not Markdown, with one rule for opting
  out. Three rules, each ratifiable on its own, then two consequences.

  **(a) Adoption of Markdown.** On reconcile, a `.md` file the kernel
  does not claim — no front matter, or front matter with no reserved
  `gilune-*` key — is adopted: an object of type `org.memory.note`
  (schema 1) whose document is the file's text, with any foreign front
  matter preserved as the document's custom metadata exactly as a page
  keeps its own; one `object-created` operation with origin
  `external-import`; and the file re-projected at the same path carrying
  `gilune-id` and the other `gilune-*` keys. The body bytes are kept
  byte-for-byte; only the header is added. Automatic, not asked: a
  candidate nobody is ever asked about is the state that lost notes
  between devices, and rule 13 (third-party editing is supported use)
  already means the kernel writes front matter into files other tools
  made and read. Unparseable front matter stays quarantined. A file
  carrying a `gilune-id` the kernel does not hold is adopted **under
  that id** when no object of that id exists — the crash window between
  writing the header and committing the rows, and a file copied from
  another device's vault, both land here, and both mean "this is that
  note"; if the real history later arrives, the two revisions become
  heads of one object, which is the conflict rule already in force. A
  file whose id names a *deleted* object is not adopted (the kept-edit
  notice covers it). The change feed announces an adoption as `Created`
  with cause `Reconcile`; the `Imported` kind is no longer raised for
  `.md` files, and an edit to an adopted note is the ordinary external
  edit.

  **(b) Attachments.** Any other file inside the vault — images, PDFs,
  audio that is not already a capture's blob — becomes an attachment
  object: type `org.memory.attachment` (schema 1), document `{media,
  blob_id, rel_path, sha256, bytes, attached: true}`, the bytes recorded
  as a content-addressed blob **in place** (state `ready`, origin
  `local`; nothing is copied and nothing is written into the file), and
  one `object-created` operation carrying the blob declaration. The
  receiving device takes it as it takes a recording — chunked, digest-
  verified, promoted to `rel_path` — and because the document says
  `attached`, no Markdown note is projected for it. Excluded: dot-folders
  (`.gilune/`, `.obsidian/` — never entered), dot-files that are not
  Markdown (the local marker below, `.DS_Store`), `.tmp-*`, files over
  `limits.max_blob_bytes` (512 MiB;
  announced once by a not-damage alert naming the file), and anything
  local-only. A file edited in place (digest changed) is a new blob and
  a new revision of the same object; the receiving device replaces its
  copy only when the bytes it holds are the ones the previous revision
  declared, otherwise keeps both (the arriving bytes at `<stem> (from
  <origin>).<ext>`) and says so. A file that disappears is a tombstone
  proposal, as a note is; rejecting the proposal cannot bring the bytes
  back from this device (they were never copied), only from a peer that
  holds them — stated as a gap.

  **(c) Local-only.** A Markdown file whose front matter says
  `gilune-sync: local`, and every file under a folder containing a
  `.gilune-local` marker file, is never adopted: it stays a candidate,
  indexed and searchable on this device, and produces no object and no
  operation, so nothing of it ever leaves. `gilune-sync` becomes the one
  reserved key a person may write (the document layer's reserved-key
  rule gains that exception; forging any other `gilune-*` key is refused
  as today). For a note that **already syncs**, entering local scope —
  the key added, or the file moved under a marker — stops it being
  sent: the object is recorded in a device-local `local_objects` set
  with the last revision peers were sent, revisions carry on locally
  with no operations, and other devices keep what they already had.
  Leaving local scope sends one `object-revised` that supersedes the
  last-sent head, so peers see one change rather than a history they
  cannot reference. The marker file itself is a non-Markdown dot-file
  and never travels; a folder's local-ness is inherited by its
  subfolders. (A dot-prefixed `.md` is a note as before: the first text
  of this amendment excluded every dot-file, which the Android widget
  count — ADR 0020 § 10, `.hidden.md` is in the inbox — caught on the
  v1.1.2 tag run; corrected the same day.)
  Evaluated when an operation would be created, never at exchange time,
  so an excluded file leaves no history for a later rule change to leak.

  **Same path on two devices.** Two devices each making
  `Notes/Untitled.md` make two objects. When the second arrives, its
  path is occupied by a different object's file: the arriving note is
  projected at `Notes/Untitled (from <origin device>).md` — the origin's
  short device id, because the kernel does not know device names — and
  the existing not-damage notice names both, so the person merges by
  hand. Today the arrival is held in history with the notice and no
  file, which is a note nobody can see. The owner's desktop shows five
  such notices for **plugin starting pages**: each machine's initialiser
  made its own `Tasks.md`, `People/Connections/*.md` and
  `Settings/Initialization/*.md` with device-dependent ids. Fixed at the
  root: a page's object id no longer includes the device (`page`,
  plugin id, request id — the request id is already a fresh UUID for a
  person's page), and an initialiser's seeded pages use a request id
  derived from (plugin id, initialiser id, page key), so two fresh vaults
  initialise to the same object and revision ids and converge with no
  conflict and no notice. Existing duplicates are not migrated; the
  notices they raised stay until the person deletes one copy.

  **Schema v10**: `blobs` gains `mtime_ns` and `recorded_ns` (the stat
  rule of ADR 0020 § 2.5 applied to attachments, so an unchanged file is
  not re-hashed); `local_objects(object_id PRIMARY KEY, last_sent_revision
  TEXT NOT NULL)`. Operation payloads are unchanged in shape; the
  attachment's `object-created` is the capture's shape with a different
  type id, so a device running an older build applies it as a capture
  with no note (the `attached` key) and loses nothing.

  **Deviations found in the build, recorded rather than smoothed.**
  (1) An arrival written beside the occupant raises no standing notice
  after all: the projection pass clears the occupied notice the moment
  the object projects cleanly, so a notice would have lasted one pass;
  the file's own name says where it came from. (2) A candidate kept
  local by its *key* is read on every pass (the key is inside the file;
  only reading learns it is still there); one kept local by its folder
  settles on the stat as before. (3) An initialiser's *receipt* page keeps
  a per-device id — it is each device's own evidence — while its seeded
  pages converge. (4) A file carrying a `gilune-type` of its own is
  adopted under that type, not as a note, so an exported definition is
  what it says it is. (5) An adopted note's document carries its path
  (`path`), the one thing it had no other way to tell the device that
  receives it. (6) The projection pass projects this device's own
  objects before arrivals with no file yet, so the beside rule never
  holds back the person's own note because the other id sorted first.

  **Stated costs.** The kernel now writes a header into every Markdown
  file a person or another tool puts in the vault; a vault opened by
  Gilune once is a vault of Gilune-headed files. Everything in the vault
  leaves the device unless marked local, which is what "sync" means and
  why rule (c) ships in the same change. A large attachment folder is a
  large first sync. The crash matrix gains adoption rows (abort between
  the rows and the header write must leave the file either untouched or
  adopted under the header's id). Gate: a New-note file and an image
  made on one device appear on the other after one sync; a local-only
  note and folder never do; moving a note out of a local folder sends
  it; the same filename made on both devices keeps both; two fresh
  vaults' plugin pages converge without a notice; `cargo test
  --workspace`, clippy and the desktop suite green.

- **2026-10-23 (renames travel).** Found by the owner the morning after
  the amendment above shipped: a note made with Ctrl+N reached the other
  device as `Untitled.md` and stayed so. The 2026-10-22 build adopts a new
  file on the next pass, so the peer received the object under its first
  name; the rename that followed was, as the decision above says,
  "a metadata change" — the record followed the file by id and nothing
  was sent. Notes that existed before sync had their final names when
  adopted, which is why only new notes showed it.

  **A rename is a revision.** When reconcile follows a moved file, it also
  writes a revision of the head document with `path` set to the new place
  (origin `rename`, silent: no words changed, so no processor is told),
  and so an `object-revised` operation carries the name to every peer the
  way an edit does. A note kept on this device records the revision and
  no operation, as every other revision of it. The next projection
  rewrites the header for the new revision and leaves the words alone.

  **The receiver follows it unless it renamed too.** The projection pass
  compares where a note is with where its head says it is. They differ in
  two shapes, and the record tells them apart. A record still on the
  head's own revision means the file was moved here after that head was
  projected and nobody was told — a rename made before this amendment —
  so it is written now as the revision it should have been, once; this is
  the catch-up, and it is what gives the owner's existing `Untitled`
  copies their names on the first sync after the update, with nobody
  touching them. A record on an older revision means the head is new and
  names a new place: a rename that arrived, and the file moves to it (the
  target has to be free; an unrelated file there is preserved and the
  note held where it is with the occupied notice). A conflicted head — a
  rename or edit made here concurrently is a second head — leaves this
  device's name standing; the edit that resolves the conflict names the
  path of the device that made it, and both devices converge on that.
  Pages are not asked (they name their paths another way); a capture
  never renamed names none and is untouched.

  **Consequences stated.** A rename made on one device before the update
  and a rename of the same note made on another after it: the later,
  recorded rename wins, because the earlier one was never recorded as a
  revision. The three-device wrong-order pull (the S4 lesson) is in the
  gate: a device meeting only the editor pulls the edit before the object
  and its rename, waits on the referent rather than landing the old name,
  and settles on the next pass. Gate rows: `adopt_tests` (rename is one
  `object-revised` carrying the path; a kept note's rename sends none; a
  pre-amendment rename is sent once by the projection pass), `gate_c`
  (renamed on one device → renamed on the other, incl. rename-and-edit in
  one pass and an edit back; renamed on both at once keeps each name
  until an edit resolves it; pre-amendment rename reaches the peer; third
  device out of order).
