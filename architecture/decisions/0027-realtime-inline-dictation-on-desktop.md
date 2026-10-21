# ADR 0027: Realtime inline dictation on the desktop

Date: 2026-10-21
Status: **Accepted** (owner, 2026-10-21) — the § 1 rule, engine B first, and
the engine-choice amendment (A and C as options in their own
plugin). S0–S2 delivered 2026-10-21 (see Delivery); S3 not started.

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

## Amendment (owner, 2026-10-21): engines are choices, live is a switch

Same day, after the ratification above. The owner: rather than one engine,
offer a couple as options — Nemotron suits more machines, whisper base suits
faster hardware. This supersedes point 1 of the Ratification record (B alone)
and restores option A, with C beside it.

**What the measurements say each option is for.** Whisper base live
re-decodes the recent audio every second, so it needs CPU headroom and uses
almost no extra memory or disk. Nemotron does little work per 160 ms chunk
and keeps up easily, but costs ~460 MiB to download and ~0.6 GB while
dictating. FastConformer is for weak machines: light and fast, less
accurate, no capitals or punctuation. Each model's settings carry one
sentence saying this.

### Plugins and names

Each engine family stays its own plugin (ADR 0018 § 3: provenance names the
engine; download, licence and consent belong to the engine). A plugin's
**id names its runtime** — ids are permanent and its model list may grow — and
its **display name follows one pattern, *Transcription (engine)***. "Live" or
"Realtime" is not in any name: live is a switch inside each plugin, not a
kind of plugin.

| Plugin id (permanent) | Display name | Processor id | Models | Shell |
|---|---|---|---|---|
| `org.memory.transcription` (exists) | **Transcription (Whisper)** — was "Local transcription" | `transcribe` | the existing whisper catalogue | desktop, Android |
| `org.memory.moonshine` (exists) | **Transcription (Moonshine)** — was "Realtime transcription (Moonshine)" | `moonshine-transcribe` | tiny streaming | Android |
| `org.memory.sherpa` (new) | **Transcription (NVIDIA NeMo)** | `sherpa-transcribe` | Nemotron speech streaming 0.6B (default), NeMo FastConformer 480 ms | desktop |

- The two renames change display names only. Ids, stored settings, grants and
  provenance are untouched, so no vault changes.
- `org.memory.sherpa` runs sherpa-onnx through its JVM jar (§ 4). If it later
  hosts a non-NVIDIA model family, only the display name changes.
- One active transcriber (ADR 0018 § 3) still holds: enabling one of these
  disables the others, and the confirmation says so.

### "Show words as I dictate"

- Every transcription plugin whose engine can stream gets a setting **Show
  words as I dictate** (default on). On: § 1's grey words, settled phrases
  inserted as they settle. Off: today's behaviour — the words arrive at the
  caret when the microphone stops.
- **Keeping up is measured, not promised.** During a live session the shell
  compares engine time against audio time. If the engine falls behind (over
  real time for several consecutive seconds), the session drops to settling
  at pauses only, and the status line says the engine could not keep up on
  this machine and suggests a lighter model. The recording is unaffected.

### Staging, revised

- **S0** shared fold (unchanged).
- **S1** editor grey text, anchor, settled insertion (unchanged).
- **S2** whisper base live in `org.memory.transcription`, plus the "Show words
  as I dictate" setting and the keeping-up fallback; the two renames.
- **S3** `org.memory.sherpa` — Nemotron and FastConformer, consented
  downloads with the NVIDIA Open Model License shown at the Nemotron consent
  point, idle unload, committed transcript via the external processor route.
- **S4** unchanged (optional).

## Delivery: S0–S2 (2026-10-21)

- **S0.** `Caption.kt` (`foldCaption`, `CaptionEvent`, `CaptionLine`,
  `captionDisplay`, `captionOffered`) moved unchanged from `androidMain` to
  `jvmShared`; its pure rows moved to `jvmSharedTest/CaptionFoldTest.kt`
  (13), so they now run on desktop too. The Moonshine-planning rows stay in
  `CaptionTest`.
- **Kernel.** `gilune-ffi/src/live.rs`: the LocalAgreement-2 session, pure
  over a `Decoder` trait. Settled words are found after the already-settled
  text by **spelling** (letters, not word count), so a re-decode that joins
  or splits a settled word ("ring main" / "ringmain") neither repeats nor
  drops one. Audio behind a fully settled segment is dropped; a window over
  20 s settles all but its last segment; near-silence (peak < 0.01) is never
  decoded. `whisper.rs` adds `WhisperDecoder` over the existing on-demand
  engine (same parameters, `no_context`). FFI: `Gilune.begin_live_transcription()`
  → `LiveTranscription { accept(pcm), update(), finish() }` and
  `LiveLine { id, text, complete }` — the caption fold's shape. The surface
  is identical with and without the `whisper` feature (a non-whisper build
  refuses), so bindings do not diverge.
- **Shell.** `LiveDictation.kt`: the anchor lives on the tab's `EditorState`
  and is carried through **every** buffer change by the `text` setter;
  settled lines land once each at the anchor (caret at the anchor moves with
  them, a caret elsewhere stays); the grey run is `DictationTransformation`
  wrapping `MarkdownEditTransformation`, inserting display-only characters
  with an offset table. The recorder only enqueues PCM (`AudioRecorder.listen`,
  a default no-op so Android is unchanged); a worker thread feeds the
  kernel, updates once per second of audio, and lands results on the EDT.
  Backlog over 3 s after an update ⇒ no more updates, one status-line
  sentence, every byte still fed so the stop settles everything.
  `GiluneState.dictate(audio, null)` keeps and transcribes the recording
  without inserting (no double words). Setting **Show words as I dictate**
  (`live` key, absent = on, carried by every settings write) on the
  Whisper plugin's screen. Renames: "Transcription (Whisper)" (kernel
  descriptor; the upsert updates existing vaults) and "Transcription
  (Moonshine)".

**Measured** (this laptop, base-q5_1, the 22.9 s clip played at real-time
pace through the real kernel into a real editor —
`DictationEndToEndTest.wordsAppearInTheNoteWhileTheyAreBeingSpoken`):
first grey words **2.8 s**, first settled phrase 5.9 s, all words settled
**2.0 s after the speech ended**; the note's words equal the whole-clip
transcript; no doubled word.

**Against the S2 gate.** First words ≤ 2 s: **missed (2.8 s).** The cost is
whisper encoding a fixed 30 s window per decode (~1 s on base, whatever the
audio). Shrinking it (`audio_ctx`) was tried at margins of 64/256/512 frames
and **rejected**: decodes got ~40% faster but words went wrong ("Ask
Marcus" → "markers") and phrases repeated ("send me to send me"). Settled
≤ 3.5 s after speaking: met at the stop (2.0 s); mid-speech a phrase settles
on the second agreeing decode, ~2–3 s after it is said. The recording is
unaffected when the session throws (it is a separate queue and thread).

**Deviations, recorded.** (1) Falling behind does not "settle at pauses":
whisper has no cheap pause signal, so the grey words stop and the rest
arrives at the stop. (2) The desktop still commits the attached capture at
the stop (as before), not chunk by chunk; the tee feeds only the engine.
(3) Settled insertions are programmatic changes to the legacy text field;
that each is one undo step is **not verified**.

**Tests.** Kernel: 10 `live::tests` rows (scripted decoder; the join/split
row falsified by counting words) + `tests/live_dictation.rs` (real model,
opt-in). Desktop: `LiveDictationTest` 15 (anchor mapping, landing, a person
typing above the dictation point between settles, selection replacement,
offset table incl. composition with checkbox glyphs, the queue never
blocking the microphone, fall-behind told once and asked no more, the stop
hearing every byte), `LiveDictationUiTest` (autosave writes settled words,
never grey ones; the grey words are in the field's displayed text),
`TranscriptionPluginTest` +1 (switch default and carried through writes),
`DictationEndToEndTest` +1 (opt-in, real time). Falsified: four rules broken
at once ⇒ 7 rows red. Suites: cargo workspace 593/1/24 — the one is
`gate_c`'s 5 s sync budget, failing alone on this laptop (3.2 s exchange vs
1.21 s on the old machine) in crates this change does not touch; desktop
798/0/7 skipped (--rerun-tasks). Not compiled: `androidMain` (no Android
SDK on this machine). Not done: a person speaking into a real microphone.
