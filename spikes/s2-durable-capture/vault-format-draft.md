# Vault Format — Draft 0 (spike artefact)

Status: **Draft**, produced by Spike S2. This is the versioned public format
ADR 0007 promises; nothing here is final until the spike review.

## Layout

```
<vault>/                        # openable as an Obsidian vault
  .gilune/
    local/                      # DEVICE-LOCAL: never replicate or export
      kernel.db                 # SQLite (WAL) — rebuildable bookkeeping + journal
      staging/                  # blob staging area
      lost+found/               # orphaned pre-commit stages, preserved
  Captures/
    capture-<id12>.md           # source envelope + text body or audio embed
    capture-<id12>.wav          # ORIGINAL bytes live once, visibly, in-vault
  Transcripts/transcript-<id12>.md
  Tasks/task-<id12>.md
  Proposals/tombstone-proposal-<id12>.md
  Objects/<type-slug>-<id12>.md # unknown types stay preservable
```

`<id12>` is the trailing 12 hex chars of the object ID (UUIDv7 leads with a
timestamp, so leading chars collide for near-simultaneous objects). Filenames
are display only: reconciliation matches files by front-matter object ID, so
users may rename and move files freely.

## Front matter

Everything Gilune owns sits under one `gilune:` key so user front matter can
coexist:

```markdown
---
gilune:
  object: c108c419a9cd6e44f7067dd734627557
  type: org.memory.transcription/transcript
  schema: 1
  class: artifact           # source | artifact | proposal
  revision: d4d0ea596b92…   # revision this file renders
  created: 1788027049757    # epoch ms (display only, never conflict authority)
  conflicted: true          # present only with concurrent heads
  heads: [reva…, revb…]     # present only with concurrent heads
---

<body: the payload's human-readable text>
```

## Rules proved in the spike

1. The Markdown layer is a projection: deletable and rebuildable
   byte-identically from the kernel store.
2. External edit → new revision (based on the projected revision; concurrent
   heads preserved). External delete → tombstone *proposal*; the file is not
   resurrected while the proposal is open. Rename/move → metadata only.
3. Sources are immutable: an externally edited source file is flagged for
   review, never ingested as source history.
4. Malformed or foreign files are quarantined untouched (import candidates).
5. Export = quiescent vault copy + `manifest.json` (path, sha256, bytes per
   file; schema versions; alert count). `.gilune/local/` never travels.

## Open for the next draft

- Human-readable timestamps alongside epoch ms.
- Relationship and provenance rendering (wikilinks) — not yet projected.
- Multi-head body rendering (currently first head plus a `conflicted` flag).
- Operations-as-immutable-files under `.gilune/` (the shared-folder sync
  question) and the exact device-local exclusion marker.
- Folder organisation policy (by date? by thread?) — display-level, layered
  on this format.
