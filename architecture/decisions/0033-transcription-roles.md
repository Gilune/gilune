# ADR 0033: Transcription roles — one engine while you speak, one for recordings

Date: 2026-11-02
Status: **Ratified** (owner, 2026-11-02: "OK lets do it", with the proposed
answer to each question below) — amends ADR 0018 § 3 (one active
transcriber) and ADR 0027's amendment of 2026-10-21 (engines are choices,
live is a switch). Accepted at its gate, after the measurement round in § 9
has set the defaults.

## Context

ADR 0018 § 3 made exactly one transcription plugin active at a time. That was
right while every transcriber did the same job: hear a finished recording and
write its words. Since ADR 0027 there are two jobs, and they want different
engines.

- **While you speak**, an engine must keep up with a voice and show words
  within a second or so. NVIDIA's Nemotron does that well: it hears 160 ms at a
  time, each slice building on the last.
- **After the fact** — a voice note, a loaded recording, *Transcribe again* —
  nothing has to keep pace with anyone; what matters is how long the whole
  recording takes and how accurate it is.

Measured on this machine (2026-11-02, a 12-core desktop; five minutes of a
two-voice text-to-speech recording through sherpa-onnx directly, two-second
pieces as the provider feeds them):

| Engine | Threads | Speed (× real time) | 50 minutes takes |
|---|---|---|---|
| Nemotron 0.6B, one stream, as shipped | 4 | 1.5 | ~34 min |
| Nemotron 0.6B, one stream | 8 | 1.9 | ~26 min |
| Nemotron 0.6B, ten 30 s pieces decoded together | 8 | 3.6 | ~14 min |
| whisper base-q5_1 (2026-09-01, a 21.5 s clip) | 8 | ~21 | ~2.5 min |

The time per minute of audio stayed flat across the recording: the slowness is
the model's design, not a fault in how it is fed. A streaming model cannot
spread an hour across twelve cores, because every slice waits on the one
before. A user who picked Nemotron for its live words now waits half an hour
for a meeting's transcript, and the one-transcriber rule forbids them the
whisper they already have, which would take minutes.

The owner (2026-11-02): support models for live and for after-the-fact
transcription, possibly from different providers, and let the person pick
between them for each task.

Found while preparing this ADR: **a recording that arrives by sync creates no
job on the receiving device.** The kernel queues jobs only for its own
captures (`capture.rs`, `reprocess.rs`; `backfill_jobs` is scoped to
`origin_device`), following ADR 0005: work belongs to the device that
captured it. So "which engine transcribes recordings" is naturally a question
each device answers for itself.

## Decision

### 1. Two roles

| Role | Shown as | What it does | Its words |
|---|---|---|---|
| `live` | **While you speak** | Words in the note as someone dictates; the phone's capture caption | What the person saw is what is kept (ADR 0027 § 1). |
| `recordings` | **Recordings** | Voice notes, loaded recordings, *Transcribe again*, the phone widget's recordings, and the kept interpretation of every dictation | The transcript the vault records, with provenance. |

Each role has **at most one engine on a device**. "One active transcriber"
becomes **one per role**, and the same plugin may fill both.

- `live` may be **Off**. Dictation then records and the Recordings engine's
  words arrive at the caret when the microphone stops, which is today's
  behaviour with *Show words as I dictate* turned off. That per-plugin switch
  is retired; the picker's Off replaces it.
- `recordings` is never Off while any transcription plugin is on. A device
  with none on has no engine for either role; recordings are kept and wait,
  as today.
- Dictation keeps both: the live engine's words are inserted, and the
  attached capture is still transcribed by the Recordings engine so the
  kernel holds the source and an interpretation (`GiluneState.dictate`,
  unchanged). They may differ; the note keeps what the person saw.

### 2. Plugins publish engines; the shell lists them

A new contribution slot, **`transcription.engines`**, carries one entry per
engine a plugin offers. The entry's `config` (manifest JSON, ADR 0015 T1 —
no migration) says:

```json
{
  "engine": "parakeet-tdt-0.6b-v2",
  "roles": ["recordings"],
  "wordTimes": true,
  "download": 482468385,
  "guidance": "Very accurate, with capitals and punctuation."
}
```

- `engine` is the plugin's own key for the model, the one its settings
  already store; unique within the plugin, not globally.
- `roles` is what the engine can do. A streaming model lists both, a
  whole-recording model only `recordings`.
- `wordTimes` says the engine reports when each word was said, which speaker
  labels need (ADR 0030). Both shipped desktop providers do.
- `download` is the size in bytes, or absent when nothing needs fetching.
- The contribution's `label` is the engine's name in the picker.

The shell reads declarations only, as it already does for `tasks.fields` and
`navigation`. The catalogues in `Plugins.kt` (`WHISPER_MODELS`,
`NEMO_MODELS`) stay the plugins' own data; they are how each plugin builds its
declaration, not something the shell reads.

Engines planned at the start (the measurement round in § 9 may change which
are offered):

| Plugin | Engine | Roles |
|---|---|---|
| Transcription (Whisper) | each whisper model | both |
| Transcription (NVIDIA NeMo) | Nemotron 0.6B, FastConformer | both |
| Transcription (NVIDIA NeMo) | Parakeet TDT 0.6B; Nemotron 1,120 ms if measured worthwhile | `recordings` |
| Transcription (Moonshine), phone | tiny streaming | both |

### 3. Where each choice lives

- **Recordings: in the kernel**, so the CLI and an agent read the same answer
  as the window (ADR 0018 § 3's reason, kept). It is two facts already in
  kernel state: which processor takes jobs (§ 4) and which engine that plugin
  uses for recordings, a key in its settings.
- **While you speak: in the plugin's settings too**, under its own key. Only a
  window does live transcription, but one home for both answers keeps them
  together and lets the CLI show them.
- A plugin's settings therefore hold **`live` and `recordings`** where they
  held one `model` (whisper) or `model` + `live` (NeMo). On first open the
  old key fills whichever roles that plugin was serving, so nobody's choice
  changes under them.
- The kernel database is per replica, so both choices are **per device**
  without anything new. A synced phone keeps its own.

### 4. Kernel: a plugin can be on without taking jobs

Today a plugin that is switched on and subscribes to `source-ready` gets a
job for every capture (`apply_subscription` sets the subscription's
`enabled` from the plugin's state). That is why two transcribers could not
both be on. Two generic additions, with no word for "transcription" in the
kernel:

- **`set_takes_jobs(plugin_id, bool)`** — persisted per plugin. The
  subscription is enabled only when the plugin is on **and** takes jobs. A
  plugin that is on but takes none behaves exactly like a switched-off
  plugin as far as jobs go: nothing queued, anything waiting parked.
  Everything else a switched-on plugin does (settings, contributions, its
  live engine) is unaffected. Defaults to true, so every existing plugin is
  unchanged.
- **`hand_over_jobs(from_processor, to_processor) -> count`** — every
  **pending** job of one processor becomes a pending job of the other for the
  same event, in one transaction. Only jobs that have never produced output
  move: the idempotency trap of ADR 0018 § 3 (a new processor id makes old
  events fresh work) cannot fire, because nothing that already has words is
  touched.

The host drives both from the Recordings picker: choosing an engine from
another plugin sets the new plugin to take jobs, sets the old one to take
none, and hands over what was waiting. The picker says how many recordings
moved.

**The hole, still named**: the CLI can set two processors to take jobs. The
result is ADR 0018's: two revisions, not corruption. A newly registered
transcriber registers **taking no jobs** when another already takes them, as
`registerPluginDisabled` does for opt-in plugins today.

### 5. Live engines are found by name

The editor asks the host for "the engine picked for while you speak" and gets
a `LiveTranscriptionInterface` (ADR 0027), or none when the role is Off.
Bundled plugins register a factory per engine with the host; the desktop's
current hard-wiring (`DesktopTranscription.kt`: NeMo's live session first,
whisper's second) goes. A sandboxed plugin (ADR 0010) would offer the same
through its runtime; that half waits for the first such plugin.

### 6. The settings page

One page, **Settings ▸ Transcription**, above the plugin list (the mock-up of
2026-11-02 is the reference):

- **While you speak** — one row per `live` engine from every switched-on
  plugin, plus **Off**. Each row: name, the plugin as a small tag, the
  guidance sentence, and a state: *Ready*, *Download N MB*, or *Allow
  network access*.
- **Recordings** — one row per `recordings` engine, same shape. A switch
  that moves waiting recordings says so before it moves them.
- **Speaker labels** — one line: on or off, and "works with" the Recordings
  engine when that engine reports word times, or a sentence saying it
  doesn't.
- Choosing an engine that needs a download opens that plugin's page, where
  consent (`network.request`), the licence, the checksum and the progress
  already live. Nothing downloads from this page.
- Each plugin's own page keeps its downloads and its other settings (spoken
  language, threads) and loses its model picker and live switch, which this
  page now owns. It says which roles it currently fills.
- Switching a plugin off that fills a role leaves that role empty, and the
  page says so ("Recordings: nothing chosen — recordings are kept and wait").

### 7. Speed, separately from roles

Choosing an engine per role is the large gain. Two smaller ones apply to any
engine heard after the fact and ship with the Recordings work:

- **Pieces heard together.** A streaming engine used for recordings hears the
  recording as pieces cut at quiet moments (`QuietSegments`, from the
  long-recordings memory fix of 2026-11-02), decoded as one batch. Nemotron: 1.9× → 3.6× real time.
  Word times are offset by each piece's start.
- **Threads for background work.** An engine working on recordings may use
  up to half the machine's logical processors (live stays at four, so
  dictation never starves the editor).

### 8. The phone

The same two roles, on the phone's own settings page:

- While you speak: Moonshine's caption on the capture screen (ADR 0018 § 1),
  or Off.
- Recordings: whisper or Moonshine.

This makes reachable the combination ADR 0018's arc left unreachable: the
live caption from Moonshine with whisper's words in the note.

### 9. Measurement round before defaults

Before the defaults are fixed, on this machine and one long two-voice
recording: whisper base-q5_1 and base, Parakeet TDT 0.6B v2, Nemotron 160 ms
batched, Nemotron 1,120 ms — speed, and words against a reference text.
About 1 GB of downloads, once. Expected, not decided: Nemotron while you
speak, whisper or Parakeet for recordings.

## Alternatives considered

### One "recordings" processor that dispatches to the chosen engine

A host-owned processor id takes every recording job and calls whichever
engine is picked. Simpler in the kernel, but every transcript would carry the
same origin, and the vault could no longer say which engine wrote a note's
words — the provenance defect ADR 0018 § 3 rejected a shared id for. Each
engine keeps its plugin's processor id.

### Keep one transcriber, give each plugin two models

Each plugin would pick a live model and a recordings model of its own. It
solves Nemotron-plus-Parakeet but not Nemotron-plus-whisper — the case the
measurements make for — because they are different plugins.

### Teach the kernel roles

A `roles` table naming `live` and `recordings`. The kernel would learn a
domain word (constitution: the kernel owns invariants, not features), and
`live` is not kernel business at all: nothing about it touches jobs.
`set_takes_jobs` is the generic shape of the one thing the kernel must know.

### Keep the choice in the shell's preferences

Desktop `Prefs` and phone DataStore hold the choice. The CLI and an agent
could then not tell which engine a device uses for recordings, and the job
routing would live in two places. Rejected for Recordings; for While you
speak it would work, but one home for both is simpler.

## Consequences

- `applyOneTranscriberRule`, `providerSwitch` and `providerSwitchWarning` go,
  and with them parked debts 3 and 7. A switch between transcription plugins
  is a choice of engine, not a plugin being switched off.
- Two engines may be resident at once, each only while working and dropped
  after ten idle minutes (ADR 0018's amendment of 2026-09-04): Nemotron while
  dictating and whisper while a recording is heard is ~0.6 GB + ~60 MB, at
  the same moment only if both happen together.
- The *Show words as I dictate* setting is retired into the picker's Off. The
  keeping-up fallback (ADR 0027) stays with the live engine.
- *Transcribe again* (ADR 0029) uses the Recordings engine, so changing the
  engine and transcribing again is how a person re-hears an old recording
  with a better model. Its idempotency is per event, so this already works.
- Speaker labels follow Recordings, and say so when an engine has no word
  times.
- Plugin docs and the user guide's transcription page change: one page, two
  questions.
- Bindings change (two kernel methods).

## Staging

1. **Kernel**: `set_takes_jobs`, `hand_over_jobs`, a schema step for the
   flag, the registration default, tests including the hand-over of jobs
   that already have output (must not move) and a disabled-then-enabled
   plugin keeping its flag.
2. **Declarations and settings keys**: `transcription.engines` from both
   desktop plugins and Moonshine, `live`/`recordings` keys with the one-time
   carry-over from `model`/`live`.
3. **Desktop page and live registry**: the page, the factory registry, the
   retired switches.
4. **Recordings speed**: pieces heard together, background threads.
5. **Phone page.**
6. **Measurement round** (may run alongside 1–3) and the defaults.

## Gate

- Nemotron while you speak and whisper for recordings, both on: a dictation
  shows Nemotron's words live; a voice note is transcribed by whisper; the
  voice note's provenance names whisper's processor.
- Switching Recordings with recordings waiting moves exactly the waiting
  ones, says how many, and leaves transcribed ones untouched.
- Switching off the plugin that fills Recordings leaves recordings kept and
  waiting, and the page says so.
- The CLI reports both choices for a vault.
- An existing install opens with the same engine doing the same job as
  before (carry-over), shown in the new page.
- A 50-minute recording through the chosen Recordings engine, with speaker
  labels, finishes within the measured time and the memory budget of
  `architecture/09` § Desktop footprint.

## Questions for the owner

1. **Who chooses for the phone?** Per device as proposed (the phone picks its
   own), or should a desktop's choice be offered to the phone as a
   suggestion? Proposed: per device, nothing synced.
2. **A recording made on the phone** is transcribed by the phone (ADR 0005).
   Should a desktop be able to offer to re-hear phone recordings with its
   faster engine? That is *Transcribe again* across devices — a new
   question, not part of this ADR unless you want it.
3. **Defaults before the measurement round**: keep today's engine in both
   roles until the measurements land, then change them in a later release —
   or wait for the measurements before shipping roles at all? Proposed: ship
   with today's engine in both roles; nothing changes for anyone until they
   choose.

## Ratification record (owner, 2026-11-02)

Ratified as written, taking the proposed answer to each question:

1. The choice is **per device**; nothing about it syncs.
2. Re-hearing another device's recordings is **not** part of this ADR.
3. Roles ship with **today's engine in both roles**; nobody's transcription
   changes until they choose. The measurement round sets the defaults for
   new installs.

## Delivery: stages 2–3 (2026-11-02, desktop)

**Shipped.**

- Slot `Slots.TRANSCRIPTION_ENGINES`. NeMo publishes one engine per
  catalogue model (Nemotron and FastConformer both roles, Parakeet
  recordings only), Moonshine its one streaming model (both roles, no word
  times), each built from the plugin's own catalogue
  (`transcriptionEngineContribution`). Whisper's come from `whisper.rs`
  (stage 1), pinned to `WHISPER_MODELS` by `WhisperEnginesCouplingTest`,
  which reads the Rust source as `AlertKindCouplingTest` reads alerts.rs.
- Shared model `TranscriptionRoles.kt`: engines parsed from declarations
  (switched-off providers listed, removed ones not, unreadable entries
  dropped); `recordingsChoice` = first enabled provider taking jobs, its
  `recordingsEngine`, else its old `model`, else its first recordings
  engine; `liveChoice` = the enabled provider holding a non-empty
  `liveEngine`; never chosen (no provider has the key) = the old
  transcriber's `model` if its old `live` switch was on and the model can
  stream, else Off. **Keys are `liveEngine`/`recordingsEngine`, not § 3's
  bare `live`/`recordings`**: `live` is already the boolean in two shipped
  documents. Every write is read-modify-write (`settingsWith`, org.json),
  so the spoken language and the old keys survive.
- **Pinning.** Every role write and every plugin switch first writes both
  current answers down (`pinTranscriptionChoices`), because the
  never-chosen derivation reads which providers are on: without it,
  switching whisper on beside NeMo would have moved the live engine.
- Kernel routing (`takeRecordings`): a switched-off provider is switched on
  taking no jobs, then given the jobs, then every other provider is
  withheld and its waiting jobs handed over. The order leaves a moment with
  two takers (§ 4's named hole: two revisions) rather than one with none (a
  capture with no job at all).
- Plugin manager: switching a transcriber on never switches another off —
  beside a taker it takes no jobs; with none, it takes them. Switching off
  the taker leaves Recordings empty, kept and waiting, and the line says so.
  `keepOneRecordingsTaker` runs at every desktop open: more than one
  enabled taker (CLI) keeps the precedence-first and withholds the rest.
- Desktop bring-up per role: whisper enabled when either role is whisper
  (one model, § 6 sentence on the page; choosing a different whisper model
  in one role rewrites the other); `NemoHost` holds a live and a recordings
  engine (one instance when both name one model); only the live engine is
  warmed. Two runtimes — `LocalTranscription` (Recordings, the top-bar mic)
  and `LocalLiveTranscription`; the dictation mic needs the live engine
  ready or the Recordings one (`dictationUnreadyReason`). `runNemoJobs` and
  speaker labels use the Recordings engine.
- Settings ▸ Transcription (`TranscriptionPage.kt`), above Plugins: While
  you speak (with Off) and Recordings, radio rows with plugin tag, guidance
  and Ready / Download N MB / Allow network access / Switched off; a row
  whose model is not here also opens its plugin's screen; nothing
  downloads from the page. A speaker-labels line follows the Recordings
  engine's word times. Plugin screens lose the model pickers and *Show
  words as I dictate*, keep language, models with on-disk state and
  licences, and say what they are used for.
- Guide: capture.html (two questions, one page), plugins.html one phrase.

**Tests.** New: `TranscriptionRolesTest` 14 (shared, pure),
`TranscriptionRolesKernelTest` 9 (real kernel), `WhisperEnginesCouplingTest`
2, `TranscriptionPageUiTest` 1; NemoTest's `switchingNemoOnSwitchesWhisperOff`
removed (the behaviour is retired). Full desktop suite (`--rerun-tasks`,
debug dll, no whisper): 1,109 tests, 0 failed, 11 skipped (model-gated).
`compileDebugKotlinAndroid` and `compileDebugAndroidTestKotlinAndroid` green.

**Falsified** by reverting each behaviour and running the kernel and UI
classes: no hand-over → the two hand-over rows fail; others not withheld →
five rows fail (incl. the UI row); a provider switched on beside the taker
also taking jobs → three rows fail; `keepOneRecordingsTaker` a no-op → its
row fails.

**Deviations, and why.**

- A fresh provider beside a taker still registers **switched off**
  (`registerProviderRespectingTheRule`), not on-taking-no-jobs as § 4
  says: nobody's setup changes until they choose, and Android's tests pin
  that shape.
- `applyOneTranscriberRule`, `providerSwitch` and `providerSwitchWarning`
  are **kept** for the phone, which still calls them until stage 5; the
  desktop no longer does. The desktop's open-time rule is a new function
  that withholds instead of disabling.
- The hand-over takes waiting jobs from **every** other provider, not only
  the previous taker: a recording parked under a switched-off transcriber
  is one the person now wants the chosen engine to hear.
- § 6's "says so before it moves them" is a standing sentence above the
  Recordings rows; the count comes after, in the status line (no per-
  processor pending count crosses the FFI).
- § 5's factory registry is **not built**: `liveEngineFor` dispatches on the
  chosen plugin (whisper, NeMo). A registry waits for a third live engine.

**Honest gaps.** Not screenshot-validated in a running app; the live-NeMo +
whisper-recordings gate row (§ Gate 1) is unexercised with real models;
the CLI shows the keys only once something has been chosen (the
never-chosen answer is derived in Kotlin); the phone's settings still write
whisper's document with the old fixed-keys writer (harmless today — the
phone reads no role keys — but stage 5 must switch it to `settingsWith`);
parked jobs that already ran once stay with their old provider (the
kernel's rule).
