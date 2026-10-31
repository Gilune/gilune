# ADR 0030: Speaker labels

Date: 2026-10-30
Status: **Accepted** (owner, 2026-10-30) — four choices ratified before the
build: a plugin of its own ("Speaker labels") whose screen says which
transcribers it works with, and a line on each transcriber's screen; bold
name paragraphs (`**Speaker 1:**`); automatic speaker count with an optional
fixed number; delivered in the same change as ADR 0029. Desktop only, the
phone later. Delivered the same day.

## Context

The owner asked whether transcription could tell speakers apart, then chose
"who spoke when" — Speaker 1, Speaker 2 — over identifying named people,
and labels added **when a recording is transcribed** over labels that appear
live. Live labelling exists (streaming diarization) but revises its labels
as more audio arrives and needs seconds of a voice before it can name it;
both clash with the caption's no-reflow rule and with dictation, where there
is one speaker anyway. The owner also asked that the settings say where this
is supported, since there are several transcribers.

Two facts shaped the design:

- **The library is already here.** sherpa-onnx, which runs the NeMo
  transcriber, ships offline speaker diarization in the same JVM jar and
  native library: a segmentation model (where speech is, where voices
  overlap) and a speaker-embedding model (what each voice sounds like),
  clustered. No new native dependency.
- **Both desktop transcribers can say when each word was said.** whisper.cpp
  gives token timestamps, and cut to one word per segment
  (`max_len` 1, split on word) gives timed words without changing what is
  decoded; sherpa-onnx results carry per-token start times (and durations,
  for TDT models).

## Decision

### 1. Labels are part of transcribing, not a pass after it

The transcriber that writes the note asks a **speaker labeller** for the
labelled text, inside its own job, and writes one revision. A separate
processor would need an order between processors the kernel does not have,
and would write a second revision over the first.

- **Whisper** (a kernel processor): `SpeakerLabeller` is a uniffi foreign
  trait, `label(wav, words) -> Option<String>`, set per handle with
  `Gilune::set_speaker_labeller`. With one set, whisper decodes in word mode
  and hands over the recording and its `TimedWord`s; `None`, an error or a
  blank answer leaves the plain transcript. With none set, whisper decodes
  exactly as before.
- **NeMo** (app-side): `NemoEngines.transcribeTimed` gives text and timed
  words (a streaming model hears the recording as one stream, no endpoint
  resets, so every time counts from the start), and the provider loop takes
  the labelled path when the speaker host is ready.
- **Attached captures are never labelled**: dictation into a note is one
  person at their cursor, and a label there would be noise.

The kernel stays ignorant of speakers: the trait carries bytes and words in
and text out.

### 2. Sentences, not words, go to a speaker

Measured on the two-voice test recording: whisper's word times are loosest
at pauses — a sentence's last word is stretched across the silence after it
(`instead.` ran 1.3 s past its speaker's turn), and a sentence's first word
starts in the pause before it — and pauses are where speakers change. A
word-by-word rule put whole words on the wrong person.

`labelSpeakers` (shared, pure) gives each **sentence** to the voice it
overlaps most (or, if it overlaps none, the voice nearest its middle). Inside
a sentence, a run of **three or more** words that clearly overlap another
voice is an interruption and keeps that voice; a shorter run is loose timing
and is folded back. A transcript with no sentence punctuation at all (a model
that writes none, like FastConformer) is taken word by word — those models'
frame times are tight. Speakers are numbered by who speaks first. Fewer than
two speakers among the words means no labels: a one-voice recording reads
exactly as plain text. Recordings under 2 s are not split.

Format, the owner's choice: a paragraph per change of speaker, opening
`**Speaker N:**`.

### 3. A plugin of its own, opt-in

`org.memory.speakers`, "Speaker labels", registered by the desktop shell,
installed **switched off** on a vault that has never had it. Capabilities:
`blobs.read` (local, granted on install when newly declared) and
`network.request` (the model download; always waits for a yes). It has no
processor and subscribes to nothing: its effect is the labeller the shell
sets. Settings: `{"speakers": N}`, 1–10; absent means automatic.

Its screen says what it does, whether the models are here (off / waiting for
permission / downloading n% / ready / failed and why), **which transcribers
it works with** — every transcriber installed on this computer, each with ✓
and "every model" / "all three models", the active one marked — that the
phone does not have it yet, and the speaker count. Each transcriber's screen
gains a "Speaker labels" line: supported and on, supported and off, or not
supported, with a button to the plugin's screen.

### 4. The models

Chosen by running three embedding models on a 46 s, eight-turn, two-voice
recording (Windows text-to-speech voices; `SpeakerLabels` spike in the
round's scratch):

| Embedding model | Size | Turns right | Time for 46 s |
|---|---|---|---|
| 3D-Speaker CAM++ (English, VoxCeleb) | 29.6 MB | no — merged and split wrongly | 3.3 s |
| 3D-Speaker ERes2Net base (zh-cn) | 39.6 MB | all eight | 5.3 s |
| **WeSpeaker ResNet34-LM (English, VoxCeleb)** | **26.5 MB** | **all eight** | **4.0 s** |

Shipped: pyannote segmentation 3.0 (7.0 MB archive, MIT) and WeSpeaker
ResNet34-LM (26.5 MB, CC-BY-4.0), 34 MB together, into
`%LOCALAPPDATA%\Gilune\models\speakers`. **sherpa-onnx publishes no
checksums for these release assets**, unlike the ASR models; the SHA-256s
are the ones taken when this was written, pinned in `Speakers.kt`, so a
changed file is refused rather than trusted. Clustering threshold 0.5,
minimum speech 0.3 s, minimum gap 0.5 s (sherpa-onnx's own example values).
The engine loads on first use and is dropped after ten idle minutes, like
the NeMo engines.

## Consequences

- A labelled note's body is ordinary Markdown; editing it, renaming
  speakers by hand, or deleting the labels is the person's business. Nothing
  links a label to a People page yet — that is level 2 (enrolled voices),
  not built.
- Labels apply from when the plugin is ready. A recording transcribed
  before that is labelled by **Transcribe again** (ADR 0029).
- Whisper in word mode costs little (token timestamps), but the labeller
  call holds the job for the diarization time — about 0.1 s per second of
  audio on this machine.
- The whole recording crosses the FFI to the labeller once (as the provider
  route already streams it to NeMo): an hour is ~115 MB copied, once.
- Overlapping speech goes to whichever voice overlaps the sentence most, and
  similar voices can merge. Measured with the shipped models: one voice
  (46 s) stays one speaker; three synthetic voices, two of them alike, got
  7 of 8 turns right on automatic — one short turn of the third voice went
  to its look-alike — and **worse with the count fixed at 3** (the look-alikes
  merged throughout). So the count setting is offered for a person whose
  recordings keep coming out with the wrong number, not promised as a fix.
  A clustering threshold of 0.3 split the two-voice recording wrongly; 0.4
  to 0.7 matched 0.5 on it, and 0.6 and above lost the third voice entirely.
- All of this was measured on text-to-speech voices, which are cleaner and
  more distinct than people in a room; real recordings are the owner's to
  judge.
- whisper.cpp's text in word mode is the same text cut finer — pinned by a
  test that joins the words and compares them with the plain transcript.
- The phone has none of this: Moonshine reports no word times through its
  API as used, and the sherpa-onnx Android library is not in the app.

## Evidence

- Kernel `gilune-ffi/tests/speaker_labels.rs` (4, real whisper, real speech):
  the labeller is handed the exact recording and timed words that join to
  the plain transcript and its answer is the note; `None`, an error or a
  blank answer leave the plain transcript and the job completes; an attached
  capture is never offered; a labeller cleared after whisper was enabled is
  not asked.
- `SpeakerLabelsTest` (11, both suites): the measured whisper words and
  model turns for the test recording, including the loose `Go` and
  `instead.`; numbering by first speaker; one voice and no turns are no
  answer; a three-word interruption kept and a two-word one folded; word by
  word without punctuation; a sentence in silence to the nearest voice;
  sherpa tokens to words with and without durations; the count setting.
- `SpeakersEndToEndTest` (5): a download that is not the recorded file is
  refused and leaves nothing; the real files install; **real whisper plus the
  real speaker models write the two-voice recording as eight alternating
  paragraphs**; a count of one gives plain text; NeMo's loop labels a
  recording and never a dictation. The model rows skip without
  `GILUNE_TEST_MODEL`, `GILUNE_TEST_SPEAKER_MODELS` and
  `GILUNE_TEST_SPEAKER_WAV`, so CI runs only the other two.
- Falsified by disabling each: the sentence rule (word-by-word puts `Go`
  and `instead.` on the wrong speaker), the attached-capture guard, the
  interruption run, and the one-voice rule.
