# ADR 0013: Transcription Lands in the Capture Note

Status: **Accepted** (owner decision 2026-08-31)  
Validation: kernel tests for revise-subject idempotency and crash
recovery, the attached-capture projection rule, and the one-time merge
migration; end-to-end by dictation in the app.

## Context

The M4 pipeline made a transcript a separate object projecting into a
`Transcripts/` folder, so one spoken thought became two notes: a capture
holding an audio embed, and a transcript holding the words. The owner's
direction is the opposite: the words belong in the capture's own note,
and dictation should be able to land text at the cursor of whatever note
is open.

## Decision

- **A processor output may revise its subject** instead of creating a
  new object. `ProposedOutput` gains a target — `new-object` (unchanged
  semantics) or `revise-subject`: a new revision of the subject's
  document, head replaced, origin `processor:{id}`, recorded as a
  `create-revision` operation and a `revision-added` event — the same
  shapes external edits already use, so sync semantics do not change.
  Idempotency holds as before: derived revision IDs from the documented
  idempotency inputs, one processing run per key.
- **Transcription revises its subject.** The transcript text becomes
  `text` in the capture object's document. Projection already prefers
  `text` as the note body, so a voice capture becomes one note whose
  body is the transcript — **text only** (owner choice): the audio stays
  linked through `gilune-blob` metadata, not an embed in the body.
- **Constitutional reading**: rule 2 protects source *bytes* — the audio
  blob is immutable and untouched. The capture object's document may
  carry interpretation in later revisions because every revision records
  its origin and the original document survives in history. What rule 2
  forbids is losing the source or disguising interpretation as capture;
  neither happens here.
- **Attached captures.** A capture may be marked *attached*: durably
  committed like any capture (rule 1 — staging, fsync, transaction,
  promotion, operations), but it projects no note of its own. This is
  the dictation substrate: audio recorded mid-note is preserved and
  transcribed in the kernel, and the app inserts the resulting text at
  the cursor — an ordinary edit of the open note. A crash between
  transcript and insertion loses nothing: the audio and its transcript
  remain in the kernel.
- **Migration** (owner choice): a one-time, idempotent merge guarded by
  a meta flag. Each existing transcript object's text becomes a revision
  of the source it derived from; the transcript object is tombstoned and
  its projected file removed. Operations are recorded, so a future sync
  peer converges.

## Alternatives considered

### Keep transcripts as separate objects, hide the folder in the UI

Rejected: the data model would keep insisting one thought is two things
and every future feature (search, sync conflict display, export) would
have to re-stitch them.

### App-side merge (transcribe, then edit the note from the shell)

Rejected for the capture path: it makes the words' arrival depend on a
shell being open and racing reconciliation, and it launders processor
output through the external-edit path, blurring provenance. (The
*dictation insertion* is deliberately an app edit — the user is present
and the audio is already safe.)

## Consequences

- The processor contract (WIT and Rust mirror) gains the output target;
  bundled and sandboxed implementations must stay interchangeable.
- `Transcripts/` stops existing for new captures; old vaults are merged
  once on open.
- The tree hides attachments; audio is reachable through the note, not
  beside it.
- A conflict between a transcription revision and a concurrent edit is
  handled like any other: both heads preserved (rule 6).
