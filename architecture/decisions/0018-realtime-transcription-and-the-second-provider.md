# ADR 0018: Realtime Transcription and the Second Provider

Status: **Accepted** (owner ratification 2026-09-02; R0 gates R1, the
crash-matrix additions gate R2, MS2 sets the packaged default)  
Validation: gated on **R0**, the Moonshine spike (§ 6). Any packaged mobile
default is additionally gated on **MS2** (ADR 0017 § 8), which becomes the pass
where both engines are measured on the same hardware. Evidence base:
[`architecture/research/2026-09-02-moonshine.md`](../research/2026-09-02-moonshine.md);
every figure below is its, and its UNVERIFIED marks carry over.

## Context

The owner's direction: Moonshine as a **second transcription plugin** — "this
is why we have a plugin architecture" — with words appearing while you speak on
the phone as the prize, and Moonshine possibly the packaged mobile default in
the first release, ahead of any marketplace.

**One fact from the research governs everything else.** Realtime partials can
never come from the kernel job loop. A transcription job exists only because it
was inserted inside the capture transaction, after the blob was staged, fsynced
and promoted; the processor is then handed a complete, digest-verified file
(`processor.rs:38`). That is not a cost of one option — it is the shape of the
problem. **A live path exists in every design.** What the options differ on is
where the note's committed words come from, and what the live path may touch.

The rest of the ground, from the research:

- **The budget is the sharpest fact.** ONNX Runtime — the engine under every
  Moonshine binding — measures **30.6 MiB per ABI** on the official full Android
  build, against **9.3 MB of headroom** under ADR 0001's 15 MB-per-ABI kernel
  budget (arm64 `gilune_ffi.so` is 5.70 MB with whisper). That is roughly twice
  the whole budget before our own code.
- **A first-party Android AAR exists**: `ai.moonshine:moonshine-voice:0.1.5`,
  MIT, minSdk 26, both our ABIs, published ~24 Aug 2026, exposing
  `MicTranscriber` and its own model downloader.
- **There is no Rust Moonshine implementation anywhere**, and the 2026 streaming
  family is `.ort`-only with its state machine (frontend, adapter, cross-KV,
  dual decoders, Silero VAD, line lifecycle) inside `libmoonshine`.
- **Partials are revisable.** Only `LineCompleted` freezes a line; a UI must let
  its tail rewrite itself.
- **Two transcription plugins is an unmodelled state** (research § 5). A shared
  `processor_id` means one processor silently wins and disabling either disables
  both; distinct ids mean double transcription with the *slower* engine winning
  the note body.
- **The FFI has no write side**: no `register_processor`, no `submit_output`, no
  counterpart to the read-only `object_text`. ADR 0015's debt 7 names it, and
  ADR 0013 already rejected the workaround — laundering processor output through
  `reconcile()` as an external edit.

## Decision

### 1. What realtime is: a live caption, display only

- **During a recording the shell runs a streaming transcriber over the PCM it is
  already producing** and renders the words in the capture surface. On Android
  the recorder already streams 16 kHz mono s16 into the kernel chunk by chunk;
  the live path is a **tee off that buffer**, not a second microphone and not a
  second recording.
- **The audio commits exactly as it does today.** Staging, fsync, transaction,
  promotion and operations are untouched, and the live path may never fail the
  recording: if the transcriber throws, falls behind, or has no model, the
  caption goes quiet and the capture is unaffected. Rule 1 holds because nothing
  about it changed.
- **The caption is not an artifact.** Nothing is captured, projected, revised,
  operated on or synced. It is display state of exactly the class ADR 0017 § 4
  already mandates for *recorded · 0:42 · transcribing…* — "the UI fills that
  gap, the bytes do not". Rule 2 is untouched because no interpretation is
  stored.
- **The note's words come from the committed pipeline**, always: a processor
  reading the digest-verified blob and landing a revise-subject revision under
  ADR 0013. The caption is never promoted into the body, never on stop and never
  as a fallback. On stop the caption is **cleared and replaced by the note's
  pending state**, so nothing on screen implies the words are already kept.

**Presentation — frozen lines, one muted tail.**

- Lines that have received `LineCompleted` render in the settled text role and
  never change again. The line in flight renders in the muted role and may
  rewrite itself wholesale.
- The caption block is **anchored at its bottom edge and grows upward**, capped
  at about three lines, older lines scrolling off. A tail rewrite therefore
  re-wraps only itself; nothing above it moves.
- **The caption has no caret, no selection, no tap target and is not a text
  field.** It is reassurance that the microphone is hearing you, not a
  transcript view.

**The no-reflow law does not bind it, and this ADR says so deliberately.**
ADR 0017 § 2's law is **editor law**: it exists so that a caret does not jump
and a thumb does not miss its target while a person is editing a buffer. The
capture surface is ADR 0017 § 1's, and a caption with no caret in it costs
nobody a keystroke. Revisable partials and no-reflow do not collide; they govern
different surfaces. Recorded here so a later reader does not "fix" the caption
into obedience and lose the feature.

### 2. Who writes the final transcript: the app-side engine, through a new FFI processor route

**Recommended shape: (b).** Moonshine runs app-side for both halves — the live
caption and the committed transcript — and its output enters the kernel through
a **new FFI processor route** carrying the same at-least-once, derived-ID,
revise-subject semantics a Rust processor gets today.

The route, in contract terms (four calls; no code here):

- **`register_external_processor(plugin_id, processor_id)`** — declares that
  *this host process* carries an implementation for that id. It creates no
  subscription: `register_plugin` already does that
  (`plugins.rs` `apply_subscription`). It only stops jobs for that id from
  falling through `run_pending_jobs`' processor lookup and expiring their lease
  forever (`processor.rs:110`).
- **`claim_job(processor_id)`** — one job, under the existing lease, carrying
  what `ProcessorCtx` carries: the subject's head payload, and the subject's
  blob **streamed chunk by chunk**, digest-verified by the kernel before a byte
  crosses. A whole wav returned by value is the memory copy ADR 0001's boundary
  rules forbid; streamed capture already proves the shape in the other
  direction.
- **`submit_output(claim, outputs)`** — the same `ProposedOutput` values
  (`type_id`, `type_schema`, `payload`, `target`). The kernel does precisely
  what `commit_outputs` does today, in one transaction: the `processing_runs`
  row keyed `{processor_id}:{event_id}`, revision IDs derived from the same
  idempotency inputs, `add_revision` with origin `processor:{processor_id}`, the
  `create-revision` operation and the `revision-added` event.
- **`fail_job(claim, message)`** — the existing backoff-then-quarantine path, so
  an app-side engine that throws is quarantined like a Rust one rather than
  retried forever.

**Invariants the route must not weaken**, stated as law:

- **The caller proposes; the kernel commits.** Nothing across the boundary may
  supply a revision ID, an origin string, an operation or a provenance row.
- **A claim is honoured only for an Enabled plugin whose capabilities are
  granted** — the gate `run_pending_jobs` gets free through `s.enabled = 1`.
- **The bytes handed out are digest-verified.** `processor.rs:38`'s promise must
  survive the crossing.
- **This is not a write-side `object_text`.** The only thing submittable is an
  output for the subject of a job whose lease you hold. There is no route to
  revise an arbitrary object, which is exactly what keeps this from becoming the
  general write door ADR 0013 rejected.
- **The final transcript is a fresh decode of the committed blob**, never the
  concatenated caption. Interpretation derives from the preserved source, not
  from a buffer that no longer exists.

**Why this shape.**

- **One engine end to end.** The words watched and the words kept come from the
  same model, vocabulary and punctuation habit. The residual difference is a
  re-decode with full context — the tail tidying itself — not a second voice.
  That difference is stated in § 1's rule that the caption is cleared on stop.
- **It pays ADR 0015's debt 7 deliberately** rather than around it: a route for
  a non-Rust processor across the FFI, with the processor contract's semantics
  intact instead of laundered.
- **It makes "a second transcription plugin" true at the architecture level**,
  not a second engine hidden inside one plugin's settings JSON.
- **It is the marketplace's missing door** (ADR 0010's trajectory): *claim a
  job, submit an output* is the shape any out-of-kernel implementation needs.
  **It is not the marketplace itself** — an external processor over the FFI is
  in-process, trusted, host-shipped code. Downloadable still means the wasm
  sandbox (ADR 0003). Nobody may read R2 as store-ready plugins.

**What it costs, honestly.** Four calls on the FFI surface the research
inventoried; crash-matrix additions (§ 6, R2); a third kind of processor
implementation — bundled Rust, sandboxed wasm, **external over the FFI** — that
the WIT contract and its Rust mirror must acknowledge as the same contract in a
different transport; and **headlessness**. An app-side processor runs only where
the app runs. On the phone that is survivable because the transcriber lives
wherever the microphone does, so the widget's `RecordService` registers it too
(it already carries whisper on its own handle). On the desktop and in the future
sync agent it is not, which is one of the reasons § 4 keeps whisper there.

### 3. Two providers, modelled: one active transcriber

- **Each provider is its own plugin with its own `plugin_id` and its own
  distinct `processor_id`.** `org.memory.transcription` keeps `transcribe`;
  Moonshine declares `moonshine-transcribe`. **Distinct, not shared** — a shared
  id makes both plugins share one subscription row, so disabling either disables
  the other, and both write origin `processor:transcribe`, leaving *which engine
  wrote these words* unanswerable from the vault. Provenance that cannot name
  the interpreter is a defect, not a saving.
- **Exactly one transcription plugin is Enabled at a time**, and the machinery
  for it already exists. Disabled means nothing new is queued and queued jobs are
  claimable by nobody (`jobs.rs` joins `s.enabled = 1`) — **parked, not
  destroyed**. That is the double-transcription problem solved with shipped
  behaviour.
- **The kernel needs no new concept.** No capability-slot registry, no
  arbitration. Which provider is active is host policy, and `plugins.rs` says in
  its own header that policy lives in the host. The rule is enforced by the
  shell, and plugin state is vault state, so the CLI and the agent read the same
  answer.
- **Where the setting lives**: the plugin switches already in Settings ▸ Plugins
  (ADR 0011, ADR 0017 § 7), made **mutually exclusive** — enabling Moonshine
  disables whisper, and the confirmation says so in words. No second concept, no
  second place that says the same thing.
- **The hole, named**: nothing in the kernel stops a CLI user or a second host
  enabling both. The result is not corruption — both transcripts exist as
  revisions, one is head, rule 6 is untroubled — but it is double battery for one
  note and a body decided by whichever engine finished last. § 7 Q4 offers the
  kernel fix.

**Queued jobs on a switch, and the trap in the catch-up.** Switching parks the
old provider's queued jobs and queues nothing retroactively for the new one:
enabling a plugin never silently re-runs the past. The shipped recovery is
backfill. **But backfill under a changed provider is not the same action it was**
— idempotency is keyed `{processor_id}:{event_id}`, so a *different* processor
id makes every past capture a fresh job. A blanket backfill after a switch would
re-decode the entire vault and rewrite every note body with the new engine's
words. Nothing is lost (each is a revision), but every note changes. **So the
catch-up offered on a provider switch must be scoped to captures that still have
no words**, and the shell must count and say how many before it runs. Either
`backfill_jobs` gains a scope or the shell computes the set; the blanket action
stays available only as the deliberate tool it already is.

### 4. Packaging and budgets

- **ADR 0001's budget, as worded, measures the kernel's contribution** —
  "kernel contribution to release package size: under 15 MB per ABI"
  (`architecture/spikes/README.md:36`, one of a list the same page calls
  "proposed starting budgets, to be adjusted with evidence"), restated by
  ADR 0017's consequences as the `.so`
  growing by the engine. Under this decision **`gilune_ffi.so` does not grow at
  all**: Moonshine is a Kotlin dependency and ONNX Runtime is its native
  library; neither links into the kernel. The 15 MB line is met by construction
  and the 9.3 MB headroom is preserved.
- **Say the bookkeeping out loud.** The APK grows by the AAR's per-ABI native
  libraries instead. That size is **UNVERIFIED** — the research could not get a
  figure out of Maven Central — proxied only by the same release's desktop
  native bundles at 13.3–26.1 MB compressed; if full-build ONNX Runtime figures
  carry, budget ~20–30 MiB uncompressed per ABI. The user pays those bytes
  either way. What changes is the line item, so R0 measures it before any
  promise, and § 7 Q5 asks whether ADR 0001 gains a companion app-package
  budget. Per-ABI delivery means a device downloads one ABI's natives, not both.
- **minSdk 26 is below our floor and the AAR covers both our ABIs. MIT.** No
  friction with ADR 0009.
- **The model download is the consent ask, and it grows.** Streaming-tiny is
  **77.7 MB** across seven files against today's pencilled phone default
  tiny-q5_1 at **31 MiB** — two and a half times, on a phone, possibly on mobile
  data. The flow does not change: `network.request` is declared, never
  auto-granted, and ADR 0017 § 4 makes the number part of the ask. Rules:
  Wi-Fi-only by default with an explicit override (the AAR's downloader has the
  option), resumable and idempotent as the whisper fetch already is, and nothing
  downloads because the app opened. Whether all seven files are always fetched —
  tiny may drop to ~45 MB if one of the two near-identical decoders suffices —
  is UNVERIFIED and is an R0 measurement.
- **Licences: the English path is clean.** Code MIT; all streaming models MIT;
  legacy English tiny/base MIT. The revenue-capped Moonshine AI Community
  Licence applies only to legacy **non-streaming, non-English** models — and the
  vendor's two documentation surfaces contradict each other about whether
  non-English *streaming* models exist. **Rule**: Gilune ships and offers only
  MIT models; the catalogue records each model's licence and the consent ask
  shows it; any non-English model gets a licence re-check at implementation
  time. We already ship a "Spoken language" setting, so this trap is live.
- **Desktop is not v1.** Whisper stays the desktop engine: it is measured
  (~4.2 s for a ~14 s clip, base-q5_1 default), it runs headless for the CLI and
  the future sync agent, and the AAR is Android-only — a desktop Moonshine means
  `libmoonshine` over its C ABI or `sherpa-onnx`, a second native dependency with
  its own budget. **The provider choice makes that a setting, not a fork**: the
  same registry and the same one-active rule, with one provider installed on
  desktop in v1, and a desktop Moonshine plugs into the same seam whenever it
  earns its way in.

### 5. Plugin identity, contributions and consent

- **`org.memory.moonshine`**, "Realtime transcription (Moonshine)".
  `class = provider`, `profile = bundled`, `required = false`, **bundled on
  mobile only in v1**. `processor_id = "moonshine-transcribe"`, subscribing to
  the source-ready event, emitting `revision-added`.
- **Capabilities**: `blobs.read` and `objects.write` — local, granted once on
  first install under the host consent policy — and `network.request`, which
  always waits for an explicit yes, is never auto-granted and never re-grants
  after a revoke. **The AAR is packaged, so the code needs no consent beyond the
  enable switch; the model download is the network ask.**
- **Contributions**: `settings.form` (its own screen: model, spoken language,
  Wi-Fi-only download, live caption on/off) and `note.actions` (dictation at the
  caret, so removing the plugin removes the button rather than leaving a dead
  key). **It does not contribute `capture.actions`** — recording is the voice
  capture adapter's, deliberately, so removing transcription leaves recording
  intact.
- **The caption slot is recorded, not declared.** In v1 the **shell owns the
  caption**. Declaring a slot now would give it one producer and one consumer
  both written in shell code — precisely how `settings.form` became decorative
  (ADR 0015 debt 1). The seam's name for when it is real: **`capture.live`** — a
  plugin-contributed view rendered inside the capture surface while a recording
  runs, with the streaming contract as its `config`.
- **The live half is a shell-owned adapter, not a kernel path.** A sandboxed
  plugin cannot produce a caption today, because seeing capture bytes *before*
  commit is not a capability anything grants. If it ever should be, its name is
  `capture.stream`. Recorded as future work, not designed here.

### 6. Staging and gates

Each stage ships alone, in order.

- **R0 — the Moonshine spike (gate).** The AAR on the x86_64 emulator: does
  streaming genuinely work there; the AAR's **actual per-ABI native size** (the
  one missing number in this route's cost); **what `AssetDownloader` actually
  fetches** for streaming-tiny; **time-to-first-token**, not time-to-finalise —
  every published latency figure is the latter and the owner's ask is governed
  by the former; and whether the frozen-lines/muted-tail caption reads calmly in
  Compose, which is a screenshot question an ADR cannot assume. **Latency and
  battery on an emulator are indicative only**: MS2's rule applies verbatim, no
  Android number in this repo has ever come off ARM64. Pass = streaming works,
  TTFT is under about a second, the caption reads calmly, and the APK cost is a
  number the owner accepts.
- **R1 — the live caption (ships alone, no kernel change).** Moonshine app-side
  for partials only; the body still comes from whisper in the kernel. This is
  shape (c), shipped honestly with its inconsistency designed out of sight by
  § 1's clear-on-stop rule. It delivers the prize — words while speaking — at
  the cost of two engines resident during a recording, and it proves the caption
  before any kernel surface is committed to.
- **R2 — the FFI processor route (gated kernel work, ships alone).** The four
  calls of § 2 with streamed subject bytes. **Crash-matrix additions**: kill
  between claim and submit (redelivery yields exactly one output); kill inside
  the submit transaction (the existing `output.commit.inside` crashpoint, now
  reached from across the boundary); lease expiry with a claimant still alive
  (two claimants, one output); a duplicate submit for a completed run (skipped);
  a submit for a job whose plugin was disabled mid-flight (refused). The
  boundary suite gains the route on both shells. With no external processor
  registered, nothing changes — which is what makes it shippable alone.
- **R3 — Moonshine becomes the provider.** Its own `processor_id`, the final
  transcript from the same engine over the committed blob, one-active-provider
  in the shell with the scoped catch-up of § 3, and `RecordService` registering
  the external processor so widget captures still get words.
- **MS2 absorbs this round.** It already gates the transcription plugin's mobile
  enablement on ARM64 whisper numbers; it becomes the single pass where **both
  engines are measured on the same device over the same clips** — seconds,
  battery, thermals, and TTFT. **R1 may ship before MS2** (a caption is not an
  enablement claim). **R3 must not ship a packaged mobile default before MS2**,
  because that is the decision MS2 exists to inform.

## Alternatives considered

### (a) Moonshine in the kernel, in Rust

The option that would keep one implementation for both platforms and every
existing guarantee free — and it **cannot deliver partials at all** (§ Context),
so on its own it does not answer the ask. The numbers finish it for v1:
ONNX Runtime's full Android build is **30.6 MiB per ABI** against **9.3 MB** of
headroom, about **twice the entire 15 MB budget**; the only measured route to
**3.56 MiB** is `onnxruntime-mobile` 1.18.0, last released 2024-05-20 and
effectively a build-it-yourself path; `ort` cannot reach it at all, because pyke
ships full builds with NNAPI — an accelerator Google deprecated in API 35. `ort`
is also a two-year-old release candidate whose rc.13 manifest **fails to build
for `x86_64-linux-android`**, the ABI every instrumented run uses. `sherpa-onnx`
has the Moonshine work done first-party but costs **~24.9 MiB** on arm64,
**forces three `.so` files** (Android supports shared linking only), and targets
the legacy models, **not** the streaming family — so it buys no realtime either.
The streaming family is `.ort`-only with its state machine inside
`libmoonshine`, so in-kernel realistically means a second native C++ dependency
beside whisper.cpp with no Rust binding to inherit.

**Its one cheap version is kept as the fallback**: legacy Moonshine tiny int8 as
a straight whisper *replacement* — **28.2 MB**, smaller than today's 31 MiB
default, ~5× less compute per the paper, parity WER (the paper's own table says
parity, not a win; every headline comparison is vendor self-published). No
streaming whatsoever. If R0 fails or the phone cannot carry the streaming model,
this is where the round retreats to, and it needs only a generalisation of the
provider-specific `enable_whisper` door.

### (c) Hybrid as the end state — partials app-side, words from whisper

Constitutionally the cleanest, and it is **exactly what R1 ships** — so nothing
here is wasted. Rejected as the *end state* on three costs the research names:
two engines resident during every recording, against ADR 0017's own warning
about battery and thermal headroom; two model downloads; and the visible wart
that the words a person watched are not the words that land. That last one
disappears only by layering the hybrid on a single engine, which is what § 2
does.

### A shared `processor_id` for both plugins

Cheapest by accident: one subscription, one job, no double work. Rejected — the
first processor in the vector silently wins, disabling either plugin disables
the other's jobs, and both write origin `processor:transcribe`, so the vault
cannot say which engine wrote a note.

### An `engine` key inside the existing transcription plugin's settings

The research's honest observation that this is the only mechanism available
today that is both correct and cheap: zero kernel change, one plugin, one
subscription, one job. Rejected because it makes the owner's sentence false —
Moonshine stops being a second plugin and becomes a branch inside the first —
and it leaves debt 7 unpaid and ADR 0010's door unbuilt, which is the actual
prize of this round.

### Teaching the kernel a replaceable capability slot now

A `provides` field of opaque capability names on the descriptor, with "at most
one Enabled plugin per name" enforced in `set_plugin_enabled` — arbitration
without the kernel learning any domain word, and the thing
`05-plugin-system.md` already promises. Genuinely attractive, and **deferred,
not refused**: this round is already paying for one kernel surface, and adding a
schema migration and a new invariant beside it widens the blast radius for a
hole whose failure mode is wasted battery, not lost data. § 7 Q4 puts it to the
owner.

### Partials from whisper, the engine we already ship

whisper.cpp has a streaming example, and it works by re-running the whole model
over a moving window, with a reported practical floor of 1–1.5 s chunks before
punctuation and capitalisation degrade for want of right context. That cost is
precisely what Moonshine's variable-length encoder removes — the one claim in
the research that is unambiguous, primary and central.

## Consequences

- **Amendments made on acceptance** (2026-09-02, all six): **ADR 0015**'s debt 7
  records that R2 takes its second half on — a debt until R2 ships, discharging
  nothing for the wasm profile, with one subscription per plugin left standing.
  **ADR 0013** records that an output submitted through the FFI route **is** a
  processor output — same origin, provenance and idempotency — while its
  rejection of the external-edit path stands unchanged, and that a live caption
  is neither capture nor stored interpretation. **ADR 0017** § 4 gains the
  caption and the second provider, § 2's law is confirmed as editor law with the
  caption out of scope, and MS2 widens to both engines. **ADR 0011**'s slot list
  **stays at nine**, with `capture.live` reserved rather than declared while the
  shell owns the caption. **ADR 0001** gains the companion app-package budget,
  its number set at R0. **`05-plugin-system.md`** § Provider plugins gains the
  one-active-provider rule.
- **The provider-specific `enable_whisper` door becomes an asymmetry**: under
  this decision Moonshine never needs an `enable_*` call, because it registers
  as an external processor. Whisper's hard-coded door stays, still naming its
  plugin id literally. Generalising it is the fallback path's cost (Alternative
  a), not this one's.
- **`backfill_jobs` needs a scope**, or the shell needs to compute one, before a
  provider switch may offer a catch-up (§ 3).
- **The pending-state vocabulary grows a fourth state.** *Listening* (caption
  live) is not *transcribing…*; the caption's presence must never be read as the
  note having words.
- **Blank output gets one rule across both engines.** Whisper's `[BLANK_AUDIO]`
  marker landing as a note body is an open wart; Moonshine's empty case is
  simply no completed line. A blank transcript writes nothing — already the
  dictation rule — and should be the transcription rule.
- **A second provider makes the settings hard-coding bite** (debt 1): the
  per-plugin settings screen is selected by plugin id in shell code, so a second
  provider without that fix has no settings screen at all.
- Nothing in this ADR touches capture, projection or sync semantics. The
  convergence harness is unaffected; the crash matrix grows by R2's points.

## Debts touched

1. **ADR 0015 debt 7 — discharged in part** at R2: a route for a non-Rust
   processor across the FFI. One subscription per plugin remains.
2. **ADR 0015 debt 1** — `settings.form` is declared but the screen is chosen by
   hard-coded plugin id; a second provider forces the fix (§ Consequences).
3. **ADR 0015 debt 6** — plugin settings remain an opaque JSON string read back
   by a regex; Moonshine adds `{model, language, caption, wifiOnly}` to it.
4. **ADR 0015 debt 10** — no registry of who provides what, which is why
   one-active-provider is a shell rule a second host would re-implement.
5. **ADR 0010** — the FFI route is the *shape* a downloadable plugin needs and
   the first door towards it. It is **not** the sandbox and confers nothing on
   store builds.
6. **New**: the live path is shell-owned, so a sandboxed plugin cannot produce a
   caption. The capability would be `capture.stream`; it is named, not designed.

## Ratification record

Owner review, 2026-09-02 — "0018 is fine as recommended". The five open
questions, answered:

| # | Question | Answer | Date |
|---|---|---|---|
| 1 | Packaged mobile default | **Streaming-tiny** (77.7 MB), as recommended — realtime is the point; legacy tiny-int8 stays in the catalogue for constrained devices, and MS2's numbers, not this ADR, make the final call | 2026-09-02 |
| 2 | Order of the stages | **R1 → R2 → R3**, as recommended: the caption ships first with no kernel risk, the FFI route second, Moonshine as provider last | 2026-09-02 |
| 3 | Caption on the widget notification | **App-only in v1**, as recommended; the notification keeps *Stop* and the duration | 2026-09-02 |
| 4 | `provides` in the kernel | **Shell rule for v1**, as recommended; the kernel field waits until a second capability gets a second provider (§ 3's hole stays named) | 2026-09-02 |
| 5 | Companion app-package budget | **Yes**, as recommended — ADR 0001 gains it, with the number **set at R0** | 2026-09-02 |

The ADR is Accepted, and the six amendments it listed were made the same day
(ADR 0013, 0015, 0017, 0011, 0001 and `05-plugin-system.md`). **R0 starts on
the owner's word and gates R1**; R2 ships only with its crash-matrix points
green; R3 waits on MS2 for the packaged default.
