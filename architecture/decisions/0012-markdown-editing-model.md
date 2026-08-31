# ADR 0012: Markdown Editing Model

Status: **Accepted** (owner ratification 2026-08-31, design session)  
Validation: the E3 stage below is gated on a spike proving the Compose
affordance before it is attempted. Save, reload and conflict logic is kept
pure and covered by the shared test suite.

## Context

The desktop app must support both rich-text and raw Markdown editing of the
same note. The vault is not the app's private storage: it is the
user-visible store (ADR 0007) and a contract that third-party tools read
and edit (constitution rule 13). Bytes the user did not change must not
change.

## Decision

- **The Markdown text is always the document.** Rich presentation is a
  rendering of that buffer. No editor holds a document model that is
  serialised back to Markdown.
- Rich and raw are per-note **view state**, never persisted into the
  document.
- Delivery is staged:
  - **E1** — rendered read mode plus raw editing.
  - **E2** — styled editing: the buffer renders with formatting applied
    while syntax markers stay visible, so no character offsets shift.
  - **E3** — markers hidden except on the caret's line. Requires offset
    mapping and caret handling, and is gated on a spike.
- Hard blocks — tables, images, embeds — render read-only in rich mode and
  are edited as raw text in place.
- Front matter is never dropped on save. It renders as a properties strip
  in rich mode and in full in raw mode; the bytes written back include it
  verbatim. Dropping it would break the ID link and re-import the note as
  a new object.
- Saving is automatic to disk on idle, so an unsaved buffer cannot be lost
  (preservation first). Reconciliation batches on blur, close or a longer
  idle, so revision history records edits rather than keystroke bursts.
- The editor records the file's hash when it opens it. A clean buffer
  reloads when the file changes underneath; a dirty buffer never overwrites
  a newer file without an explicit choice.
- Parsing and rendering live in shared code so mobile reading reuses them.

## Alternatives considered

### A rich-text AST as the edit model

Serialise the model to Markdown on save. Rejected: normalisation rewrites
whitespace, list markers and attribute order the user never touched. Each
rewrite is an external edit as far as the kernel is concerned, so it
records a revision and an operation, and sync propagates editor formatting
noise between devices. Round-trip fidelity is a contract, not a nicety.

### Split source and preview panes

Rejected as the primary mode — it halves the usable width and is not rich
editing — but it remains available as a view.

### Rendered preview only, raw for all editing

Insufficient: the requirement is editing in both modes.

## Consequences

- A Markdown parser is required in shared code; `org.jetbrains:markdown` is
  the candidate because it is Kotlin Multiplatform.
- Compose text fields degrade on very large documents. Measure before E3,
  and treat a size threshold for rich mode as an acceptable outcome.
- Projection can rewrite a file while it is open, so the hash guard above
  is mandatory behaviour, not polish. Without it, saving a stale buffer
  silently reverts canonicalised content.
- Reconcile must run before project in any cycle the app triggers. The
  reverse order re-creates a moved note at its old path and the move is
  then quarantined as a duplicate.
