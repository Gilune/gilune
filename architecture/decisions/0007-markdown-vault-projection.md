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
