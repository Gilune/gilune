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

## Amendments

**2026-10-19 (owner): Read becomes presentation mode.** Since E2/E3 the
editing view is a live preview, so a separate Read *mode* stopped earning
its place: editing is simply what a note is. The rendered view stays, as
**presentation mode** — a view someone asks for, never a preference notes
open in:

- Reached from the note's ⋯ menu or **Ctrl+E**; left with **Esc**, Ctrl+E
  or the header's *Presenting* label, which returns the caret to where it
  was. The always-visible Edit/Read switch and the "Notes open: editing /
  reading" setting are removed.
- **No caret, and a click acts** instead of starting an edit. A checkbox
  ticks through the same activation chain as the editor (so `done:` is
  stamped identically) and writes at once; a Markdown link opens a vault
  note in a tab or a web address in the browser; a `[[wiki link]]` opens
  the note of that name (a path names it exactly, a bare name finds it
  anywhere, shortest path first); a plugin reference — a person's
  `@mention` — opens what the plugin's own lookup answers. This replaces
  the earlier rule that the desktop reading pane's checkboxes are inert: a
  click on the box is a person asking.
- **The shell still knows no reference syntax.** `DocumentReference`
  gains `written`, the character ranges where the plugin recognised each
  reference; parsed spans carry their body offset (`Span.source`, outside
  equality), and the renderer makes the overlap clickable. A reference with
  no ranges is listed, as before, but not drawn as a link.
- `[[name]]` is no longer parsed as an undefined reference link (it had
  been drawn as a broken link in the rendered view), and bare or
  angle-bracketed web addresses render as links.
- Mobile Read is unchanged: it ticks already, and its links stay text until
  a mobile round wires the same `ReadLinks`.
