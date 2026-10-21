# ADR 0027: Realtime inline dictation on the desktop

Date: 2026-10-21
Status: **Accepted in part** (owner, 2026-10-21) — engine B and the § 1 rule; see
Ratification record. Build not started.

## Context

The owner, testing the Windows app on 2026-10-21: dictation into an open note
should "put the transcription into the file in near real time. It can go back
and try and change the text if it needs to, it could show the text as grey
until it's happy with it. I think for the desktop app it can run a stronger
embedded model."

Today dictation (ADR 0013, M6 third pass) records an attached capture, and
only after the microphone stops does the transcription plugin's processor
transcribe it and the shell insert `object_text` at the caret. On the
desktop that processor is whisper base-q5_1 inside the kernel. The same day's
fix (commit 1d6db0e) made dictation refuse to start when no transcriber can
run; it did not make it live.

Android already has a realtime path (ADR 0018): Moonshine streaming, run
app-side, feeding a **display-only caption** folded by `foldCaption`, while
the committed transcript arrives through the external processor route (R2).
ADR 0018 § 1 says the caption "is never promoted into the body". That rule was
written for a **new note from speech**, where the committed pipeline writes
the body. Dictation is different in kind: the words go into a buffer the
person is editing, as input, and ADR 0013 already has the shell — not a
processor — put them there.

Measurements: `architecture/research/2026-10-21-desktop-streaming-asr.md`.

## Decision

### 1. Provisional words are never in the buffer

**Recommended rule:** while dictating, the words the engine may still revise
are drawn as a muted run at the dictation point, **outside the buffer**.
Words the engine has settled are inserted into the buffer as ordinary edits.

- The muted run is presentation: one more registration in the editor's
  existing offset-mapped transformation (the checkbox glyphs' seam, ADR
  0012/0015), inserting display-only characters at the anchor with an offset
  table, so the caret, selection and click mapping already account for it.
  Styling never changes bytes — here because the bytes never contain it.
- **Settled** = the engine has finished the phrase (an endpoint, a completed
  line, or a LocalAgreement commit, depending on engine). Each settled phrase
  is inserted at the anchor as **one edit**: it dirties the buffer, autosaves
  like typing, and is one undo step.
- **The anchor** is a buffer offset that starts at the caret (replacing any
  selection, with the existing spacing rule of `insertAtCaret`) and is mapped
  through edits made elsewhere while dictating. The person may keep typing or
  move the caret; the words keep landing at the anchor, not wherever the
  caret wandered.
- **Stop** flushes: the remaining provisional words are taken as settled
  (the engine's final pass on stop) and inserted, then the muted run goes.
- **A crash or close mid-dictation** loses only the provisional words on
  screen — the recording is already committing (§ 2), and its transcript is
  in the kernel.

**Why not in the buffer, delimited?** (e.g. `⟦words⟧` or an HTML comment).
The buffer is the document (ADR 0012) and autosave writes it within ~1 s;
another tool reading the vault (rule 13), sync, the index and search would all
see half-heard words and delimiters; a crash would leave them in the file. A
delimiter also has to be escaped against a person typing the same characters.
Keeping uncertainty out of the bytes costs only the transformation work, which
the editor already does for checkboxes.

**Why not settle-only** (insert at each pause, show nothing provisional)?
That is the fallback if the transformation proves fragile, and it is what an
engine that cannot decode in-progress phrases gives anyway. The owner asked
for grey text, so it is not the recommendation.

**"Go back and change the text."** Revision happens while words are
provisional. Settled words are never rewritten by the engine — rewriting text
the person may already be reading or editing is the reflow ADR 0017 § 2
forbids on mobile, and it would make undo lie. A later stage (§ 5 S4) may
offer one explicit "replace with the final transcript" action for a dictated
span the person has not touched.

### 2. Preservation and provenance are unchanged

- The microphone tees one PCM stream: the capture writer (attached capture,
  streamed chunk by chunk as `RecordService` already does on Android) and the
  live engine. The capture writes first; the live path can throw, fall behind
  or have no model and the recording commits regardless (ADR 0018 § 1).
- The attached capture is still transcribed by the active transcription
  plugin's processor, so the kernel holds the recording and an interpreted
  transcript with provenance, exactly as now.
- The buffer's words are the person's input, like typing. They may differ
  from the committed transcript; that is acceptable because the committed
  transcript is an interpretation of the source, and the note is the
  person's document. The shell no longer inserts `object_text` when a live
  session already inserted the words (no double insertion).

### 3. The engine — owner decision

Measured on this machine (Core Ultra X7 358H), 22.9 s clip:

| Option | First words | Keeps up | Accuracy (real errors / 55 words) | Download / resident | Licence |
|---|---|---|---|---|---|
| **A. Nemotron speech streaming 0.6B int8 via sherpa-onnx (JVM jar)** | 1.2 s | yes, ~0.5 of real time on 4 threads | **2** (only sub-medium engine to hear "surveyor") | ~460 MiB / ~0.6 GB | engine Apache-2.0; weights NVIDIA Open Model License (commercial use allowed, not OSI) |
| B. whisper base-q5_1 with LocalAgreement, in the kernel | 1.2–1.8 s, settles 2–3 s | barely; CPU busy all the time | 1 (same as today) | 0 new / 59 MB | MIT |
| C. NeMo FastConformer 480 ms int8 via sherpa-onnx | 1.3 s | easily (0.05) | 9; no casing or punctuation | ~100 MiB | CC-BY-4.0 |
| D. Moonshine streaming via its Windows C library | 0.7–1.0 s | **no** on this machine in grey-text mode | ~4 | 45 MB (tiny) | MIT |

**Recommendation: A**, staged behind B. A is the only engine that is both
live and more accurate than what ships, and the desktop has the memory for it
if it unloads when idle (the memory round's rule). B costs nothing new and
gives the owner the feature immediately with today's accuracy, and it remains
the fallback when A's model is absent. Larger whisper models (medium, turbo:
13–21 s per 23 s of audio here) cannot stream on CPU; at most they could be a
final pass.

**Licence note for the owner.** The code is MIT-only (ADR 0009). A model is a
downloaded data file, as the whisper and Moonshine models are, not bundled
code, but the NVIDIA Open Model License is not an open-source licence and its
terms would be shown at the consent point. If that is unacceptable, C
(CC-BY-4.0) or B (MIT) are the alternatives.

### 4. Shape: a desktop provider plugin, one active transcriber

Following ADR 0018 rather than inventing a new route:

- A streaming engine is its **own plugin** (for A: `org.memory.nemotron`,
  processor id `nemotron-transcribe`), registered by the desktop shell,
  declaring `capture.actions` live-dictation support, `blobs.read`,
  `objects.write` and `network.request` for its model download (consent as
  today).
- **One active transcriber** (ADR 0018 § 3, `applyOneTranscriberRule`):
  enabling it disables whisper and says so. It writes the committed
  transcript of attached captures through the external processor route
  (`claim_job`/`submit_output`), as Moonshine does on Android, so provenance
  names the engine that heard the words.
- The live session is app-side (sherpa-onnx's JVM API), on its own thread,
  fed by the recorder tee. Display state is folded by the shared caption fold:
  `foldCaption`/`CaptionLine` move from `androidMain` to `jvmShared`, and a
  sherpa endpoint maps to `LineCompleted`, a changed partial to
  `LineTextChanged`. The fold's five rules (settled lines never change; blank
  text never opens a line; completion by flag or event) are exactly the
  settle rule § 1 needs.
- Option B instead lives in the transcription plugin itself (whisper is
  already there): the kernel gains a streaming session on the uniffi surface
  (`begin_live_transcription` → push PCM, foreign-trait callback with
  `{line_id, text, complete}`), the same event shape, so the editor code is
  engine-blind.
- Model residency follows the memory round: loaded at the first dictation,
  dropped after ten idle minutes.

### 5. Staging

- **S0 — shared fold.** Move `foldCaption`, `CaptionEvent`, `CaptionLine` to
  `jvmShared` unchanged; Android behaviour identical (its tests move with
  it).
- **S1 — editor rendering.** The anchor, the muted run as a transformation
  registration, settled insertion as one edit, stop-flush, no double insert.
  Driven by a scripted event source in tests (no engine), including the
  second-order rows: typing before the anchor while words arrive, undo of a
  settled phrase, closing the tab mid-dictation, autosave never writing
  provisional words.
- **S2 — engine B** (whisper LocalAgreement in the kernel). Gives the owner
  live dictation at today's accuracy; gate: first words ≤ 2 s and settled
  ≤ 3.5 s on this machine, recording unaffected when the session throws.
- **S3 — engine A** as a desktop provider plugin with consented download,
  one-active-transcriber switch, idle unload; gate: keeps up on this machine,
  measured resident size recorded, committed transcript lands through R2.
- **S4 (optional)** — "replace with the final transcript" for an untouched
  dictated span; keyterm biasing from People names if an engine supports it.

## Alternatives considered

- **Provisional text in the buffer, delimited** — rejected, § 1.
- **Engine rewrites settled text when the final pass disagrees** — rejected
  for now, § 1; offered as an explicit action in S4.
- **whisper medium/turbo as the live engine** — cannot keep up on CPU
  (measured).
- **Moonshine native on Windows** — the obvious reuse of Android's engine, but
  it fell behind in the mode that produces grey text; needs a profiling spike
  before it can be recommended.
- **A second, live-only engine beside the active transcriber** (live words
  from Nemotron, committed transcript from whisper) — breaks ADR 0018's
  one-engine rule (double CPU, two answers to "what was said"), and its
  hybrid was already rejected there.

## Consequences

- The editor gains a dictation anchor and a display-only run; the offset
  table's tests must cover an insertion that is not a glyph substitution.
- With A, the desktop download grows by ~460 MiB on consent and dictation
  holds ~0.6 GB while active.
- ADR 0018 § 1's "never promoted into the body" gains a stated exception for
  dictation (§ 2): the body gets words because the person dictated them, not
  because a caption was promoted.

## Questions for the owner

1. Engine: A (Nemotron, most accurate, large, NVIDIA licence), B (whisper
   base, no new download), or C (small and fast, less accurate, no
   punctuation)?
2. Provisional words: muted and outside the file until settled (recommended),
   or settle-only with nothing provisional shown?
3. After stop, should a final, more accurate pass ever replace dictated
   words automatically, never, or only on request (S4)?

## Ratification record (owner, 2026-10-21)

1. **Engine: B — whisper base, live** (LocalAgreement in the kernel's
   transcription plugin). No new download or licence. Option A (Nemotron)
   is not taken; the provider-plugin shape in § 4 stays recorded for a later
   round should accuracy need it. Staging therefore ends at S2 (S3 not
   scheduled).
2. **Provisional words: grey, outside the file** (§ 1 as recommended).
3. Question 3 (automatic final-pass replacement) was not asked; S4 stays
   optional and unscheduled.
