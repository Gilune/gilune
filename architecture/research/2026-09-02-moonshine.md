# Moonshine as a second transcription plugin — research note

Researcher's report for the Moonshine round, 2026-09-02. Primary sources
cited inline. Every figure I could not put against a primary source is
marked **UNVERIFIED**; vendor self-measurement is called out as such.
Repo facts carry file and line references. **No recommendation is made
here** — § 7 states the fork for the design agent to argue.

Repo note up front: **Moonshine appears nowhere in the tree.** A
repo-wide search returns no match, so
`architecture/research/2026-09-01-mobile-prior-art.md` § 6 does *not*
mention it. What § 6 does establish is the precedent frame: § 6.2 names
**Google Recorder** as the realtime-on-device model — realtime
transcription on-device, summarisation in the cloud, "an explicit
contract about which capability is always available and which is
conditional" — and Otter as the honest-pending-state model. § 6.3
records that Gilune deliberately inverts "audio is primary", which
"imports an obligation: the note must never look *empty* while
transcription is pending".

---

## 1. The models

**There are two generations, packaged completely differently. Conflating
them is the main way to get this decision wrong.**

### 1.1 Legacy family (Oct 2024) — non-streaming, many formats

`UsefulSensors/moonshine-{tiny,base}`, the models of the paper
([arXiv:2410.15608](https://arxiv.org/abs/2410.15608)).

Parameter counts from the paper's own table: **Moonshine Tiny 27.1M**
(vs Whisper tiny.en 37.8M), **Moonshine Base 61.5M** (vs base.en 72.6M).
The docs quote 26M / 58M for the same models — a rounding or a
frontend-exclusion difference, immaterial here.

**Download sizes, from the HuggingFace tree API** (I pulled these
myself; a second researcher pulled them independently and the byte
counts agree exactly) —
[`api/models/UsefulSensors/moonshine/tree/main?recursive=true`](https://huggingface.co/api/models/UsefulSensors/moonshine/tree/main?recursive=true):

| Format | tiny | base |
| --- | --- | --- |
| ONNX float (encoder + merged decoder) | 109.1 MB | 247.0 MB |
| **ONNX int8 quantised** | **28.2 MB** (7.94 + 20.24) | **63.0 MB** (20.5 + 42.5) |
| ONNX 4-bit | 33.2 MB | 73.5 MB |
| CTranslate2 / safetensors | 108.4 MB | 246.1 MB |
| TFLite float | ~182 MB | ~401 MB |

Note the 4-bit encoder is *larger* than the int8 encoder (13.0 MB vs
7.9 MB) — int8 is the size-optimal choice, not 4-bit. A `tokenizer.bin`
(0.24 MB) is required alongside.

### 1.2 Streaming family (2026) — `.ort` only

`moonshine-ai/moonshine-streaming-{tiny,small,medium}`. Different
architecture: "a lightweight 50~Hz audio frontend with a sliding-window
Transformer encoder", bounded local attention, no positional
embeddings, an adapter injecting position before a standard
autoregressive decoder
([model card](https://huggingface.co/moonshine-ai/moonshine-streaming-tiny)).
The medium card states an **80 ms lookahead window**.

| Model | Params | Shipped quantised `.ort` set |
| --- | --- | --- |
| streaming-tiny | 34M | **77.7 MB** |
| streaming-small | 123M | 224.1 MB |
| streaming-medium | 245M | 415.9 MB |

Sizes from the asset repo's manifest directory
([`moonshine-voice-assets` tree API](https://huggingface.co/api/models/moonshine-ai/moonshine-voice-assets/tree/main/model/tiny-streaming-en?recursive=true)).
A set is **seven files**, not one: encoder, `decoder_kv`,
`decoder_kv_with_attention`, `frontend.weights`, `adapter`, `cross_kv`,
tokenizer. Two near-identical decoders ship per model; tiny drops to
~45 MB if only one is actually needed, but *whether both are always
fetched is* **UNVERIFIED**.

**ONNX float for the streaming family could not be located** — only
`.ort`. Treat the streaming family as **ORT-format-only in practice**,
which matters: `.ort` is ONNX Runtime's own memory-mappable flatbuffer,
so it presupposes ONNX Runtime rather than any ONNX loader.

### 1.3 Licences — the English path is clean

- **Code: MIT** ([LICENSE](https://raw.githubusercontent.com/moonshine-ai/moonshine/main/LICENSE)),
  excluding `core/third-party`.
- **All streaming models: MIT.** **Legacy English tiny/base: MIT.**
  Verified on the model cards themselves.
- **Moonshine AI Community License** applies *only* to **legacy
  non-streaming non-English** models (Arabic, Japanese, Korean,
  Mandarin, Ukrainian, Vietnamese, Spanish). It is **not classically
  non-commercial** — it is revenue-capped: *"If at any time You or Your
  Affiliate(s) generate more than USD $1,000,000 in annual revenue, any
  licenses granted shall terminate."*

The README states the rule directly: "The models are MIT by default too,
in every language and at every size — the only exceptions are the legacy
non-streaming models for languages other than English, which stay under
the non-commercial Moonshine Community License."

**Consequence for us**: nothing in the English path is encumbered, and
ADR 0009 (MIT) is untroubled. **But per-model licence must be re-checked
at implementation time for any non-English model** — that is exactly
where the encumbrance lives, and Gilune already ships a "Spoken
language" setting that invites non-English use.

### 1.4 Accuracy — all self-published

From [moonshine-vs-whisper](https://moonshine-voice.readthedocs.io/en/stable/moonshine-vs-whisper/):

| Model | Params | WER |
| --- | --- | --- |
| Moonshine Medium Streaming | 245M | 6.65% |
| Whisper Large v3 | 1.5B | 7.44% |
| Moonshine Small Streaming | 123M | 7.84% |
| Whisper Small | 244M | 8.59% |
| Moonshine Tiny Streaming | 34M | 12.00% |
| Whisper Tiny | 39M | 12.81% |

**Every comparison is self-published by Moonshine; no independent
evaluator is named.** The paper's own table is more conservative and
more useful: Moonshine Tiny 12.66 average vs Whisper tiny.en 12.81 —
i.e. **parity, not a win**, with the gain being compute, not accuracy.

Quantisation cost is published and small (LibriSpeech test-clean, float
→ shipped-quantised): tiny 4.52→4.83, small 2.55→2.61, medium
2.16→2.17.

### 1.5 The 30-second padding point — CONFIRMED, and it is the whole story

The paper: *"The standard inference code provided by OpenAI zero-pads
the input audio to an exact 30-second segment before passing it down to
the model."* Moonshine supports variable-length encoder inputs, giving
processing demands **proportional to audio length**.

The docs put it plainly: *"You can supply any length of audio and the
model will only spend compute on that input, no zero-padding
required."*

The headline claim, from the abstract: **"Moonshine Tiny demonstrates a
5x reduction in compute requirements for transcribing a 10-second speech
segment while incurring no increase in word error rates"** against
Whisper tiny-en.

This is the architectural reason Moonshine can stream and Whisper
cannot, and it is the one claim that is unambiguous, primary and
central.

### 1.6 Multilingual

Not English-only as a family, though **every streaming model card I read
says English-only for that model**. The two documentation surfaces
disagree: the mintlify catalogue lists non-English models as
**non-streaming** Base/Tiny (Arabic, Japanese, Korean, Mandarin,
Spanish, Ukrainian, Vietnamese — 13 models, 8 languages), while the
readthedocs "stable" docs describe **streaming** models for Arabic,
German, Spanish, Japanese, Mandarin, Vietnamese and Tagalog under MIT.
**Flagged as a live contradiction between vendor doc surfaces — resolve
at implementation time, because the licence answer differs between the
two readings.**

---

## 2. The runtime

**Engine: a portable C++ core library on top of ONNX Runtime.** Not
TFLite, not a bespoke engine. Confirmed three ways: the Android build
config expects `libonnxruntime.so` under
`core/third-party/onnxruntime/lib/android/<abi>/`; the Linux
distribution ships "headers and `libmoonshine.so` with ONNX Runtime
dependency"; and the C API docs describe `.ort` files as "quantized-
activation ONNX models converted to ORT format".

**Bindings that officially exist**: `language-bindings/` contains
exactly **android, python, swift, wasm**
([GitHub contents API](https://api.github.com/repos/moonshine-ai/moonshine/contents/language-bindings)).
**There is no Rust binding, official or announced.**

**There is, however, a stable C ABI** — `core/moonshine-c-api.h`:
`moonshine_load_transcriber_from_files`, `..._from_memory_files`,
`moonshine_create_stream` / `_free_stream`, `_start_stream` /
`_stop_stream`, `moonshine_transcribe_add_audio_to_stream`,
`moonshine_transcribe_stream`, `moonshine_transcribe_without_streaming`.
**That is the natural FFI target from Rust**, and it is the whole
streaming state machine rather than a bare tensor interface.

### 2.1 Android

- **Maven Central: `ai.moonshine:moonshine-voice:0.1.5`**, packaged
  **AAR**, **MIT**, published ~24 Aug 2026
  ([Maven Central](https://central.sonatype.com/artifact/ai.moonshine/moonshine-voice)).
  A first-party Android artefact exists and is two weeks old.
- **minSdk 26**, compileSdk 35, NDK 28.2.13676358, Java 11
  ([build.gradle.kts](https://raw.githubusercontent.com/moonshine-ai/moonshine/main/language-bindings/android/build.gradle.kts)).
  Our app targets well above minSdk 26, so no floor problem.
- **ABIs: arm64-v8a, armeabi-v7a, x86_64** — covers both ABIs MB0
  builds.
- Java API exposes `MicTranscriber`; models fetch at runtime via
  `AssetDownloader.ensureModelPresent()` with a Wi-Fi-only option.
  A sample app ships in release v0.1.5.
- **AAR and native `.so` size: UNVERIFIED** — not exposed on the Maven
  Central page, and repo1.maven.org returns 403 to this tool. Proxy from
  the same release's desktop native bundles (library + ONNX Runtime,
  compressed): linux-x86_64 14.6 MB, linux-arm64 13.3 MB, windows-x86_64
  26.1 MB.

### 2.2 The Rust story — two sub-routes, and they are not equal

#### `ort` (pykeio)

- Version **2.0.0-rc.13** (28 July 2026), licence **MIT OR Apache-2.0**
  — no friction with ADR 0009. Binds the ONNX Runtime **C API** v1.28.0.
- **Still a release candidate, and has been since 2024** — thirteen RCs,
  `max_stable_version: null` on crates.io. Widely used (17.2M downloads)
  and actively maintained, but a two-year-old RC is a maturity fact the
  design agent should weigh.
- **Android prebuilts DO exist** — this corrects the brief's premise.
  `ort`'s platform matrix lists Android arm64 and Android x64 in both
  `PLATFORMS_WITH_BINARIES` and `PLATFORMS_SUPPORTED`, and
  `download-binaries` (on by default) pulls **statically-linked** builds
  from **pyke's own CDN**, not Microsoft's.
- **But there is a specific, load-bearing gap that would bite us on day
  one.** Verified three ways:

  | Target | in rc.13's `dist.tsv` | on the CDN | built by CI |
  | --- | --- | --- | --- |
  | `aarch64-linux-android` +nnapi | yes | 10,498,669 B | yes |
  | `x86_64-linux-android` +nnapi | **absent** | 10,668,844 B | yes |

  CI builds both and the x86_64 archive is live, but rc.13's shipped
  manifest lists only aarch64, and `resolve.rs` reads that manifest —
  so **building for `x86_64-linux-android` on rc.13 fails** with "no
  prebuilt binaries available for target". **We ship x86_64 for the
  emulator** (MB0, and every instrumented suite run), so this is a real
  blocker on the current release. Worked around by `ORT_LIB_PATH` at a
  manually-downloaded CDN archive, or by waiting for rc.14.
- **armeabi-v7a is not supported on Android at all** by `ort`; minimum
  **API 24**. Neither hurts us (we ship two 64-bit ABIs; ADR 0017's
  floor is well above 24).
- **Static linking on Android pulls in `c++_shared`**, so
  `libc++_shared.so` must ship in the APK. **We already ship it** —
  whisper links the NDK STL (MB0 record) — so this costs nothing new.
- **The killer for this sub-route is not the runtime, it is the model
  code**: **no Rust Moonshine implementation exists**, in `ort` examples
  or anywhere else. Moonshine's own tooling is Python/C++. Taking `ort`
  means **writing the encoder/decoder/tokeniser/streaming loop
  ourselves** against raw tensors — and for the streaming family that
  means reimplementing the frontend, adapter, cross-KV cache, dual
  decoders, Silero VAD and line lifecycle that today live inside
  `libmoonshine`.

#### `sherpa-onnx` (k2-fsa) — the sub-route that already has the work done

- Crate **`sherpa-onnx` v1.13.7**, published **2026-09-01**, by
  `csukuangfj` — the k2-fsa maintainer, i.e. **first-party bindings,
  not a third-party wrapper**. Licence **Apache-2.0** (permissive,
  compatible with our MIT distribution). 318k downloads.
- **First-party Moonshine support**: a dedicated offline model config, a
  `moonshine_v2.rs` example, a C API example, and **real-time/streaming
  Android APKs**. Models: v1 `moonshine-tiny-en-int8`,
  `moonshine-base-en-int8`; v2 quantised tiny/base across en, ar, zh,
  ja, ko, es, uk, vi.
- Android prebuilts are auto-downloaded from GitHub releases.
- **Architectural caveat**: `sherpa-onnx-sys/build.rs` states **"Android
  and iOS only support shared linking"**, emitting
  `dylib=sherpa-onnx-c-api` and `dylib=onnxruntime`. On Android we would
  ship **three `.so` files, not one** — a real departure from Gilune's
  single-cdylib shape and a packaging change in the Compose Android
  build.
- ⚠️ **`thewh1teagle/sherpa-rs`** — the crate most search results
  surface — was **archived and deprecated on 2026-06-06**, redirecting
  to these official bindings. Do not build on it.
- Note it targets the **legacy/v2 ONNX** models, **not** the 2026
  streaming family.

#### Other Rust claims

`whisper-apr` claims Moonshine support with GGUF loading but is
WASM-first and very low-traction — not a foundation. `transcribe-rs`
and `voice-stt` surfaced in search: **UNVERIFIED**, family and format
unconfirmed.

### 2.3 ONNX Runtime's size on Android — the decisive number

Measured by decompressing the actual artefacts, not quoted.

**Official `com.microsoft.onnxruntime:onnxruntime-android` 1.29.0**
(published 2026-08-12; AAR = 49.5 MiB), uncompressed `libonnxruntime.so`
per ABI:

| ABI | full build |
| --- | --- |
| **arm64-v8a** | **30.6 MiB** |
| **x86_64** | 36.7 MiB |
| armeabi-v7a | 21.7 MiB |

**Official `onnxruntime-mobile` 1.18.0** (the minimal build; AAR =
6.9 MiB):

| ABI | mobile/minimal build |
| --- | --- |
| **arm64-v8a** | **3.56 MiB** |
| x86_64 | 3.95 MiB |

**An 8.6× reduction, measured.** But `onnxruntime-mobile` is
**effectively discontinued**: its last release was 1.18.0 on
2024-05-20, while `onnxruntime-android` advanced 1.18 → 1.29 in the same
period. *(No explicit Microsoft deprecation notice was found; the
"discontinued" reading is inferred from the release timeline —
**UNVERIFIED** as an official statement.)* **Minimal builds are now a
build-it-yourself path, not a Maven artefact.**

Microsoft documents the reduction mechanisms — `--minimal_build`,
`--include_ops_by_config`, `--enable_type_reduction`, `--disable_ml_ops`,
`MinSizeRel` — but **publishes no current size figures at all**; the only
official number found is a stale 2020 blog figure. The measurements
above are the honest current numbers.

**A second, lower measurement exists and I cannot reconcile it**: a
mirror of official builds
([csukuangfj/android-onnxruntime-libs](https://huggingface.co/api/models/csukuangfj/android-onnxruntime-libs/tree/main?recursive=true))
lists arm64-v8a `libonnxruntime.so` at 13.0 MB (1.14) rising to 18.5 MB
(1.23.2) — roughly *half* the official AAR's 30.6 MiB. Likely stripped
versus unstripped, or a narrower build configuration. **Treat 30.6 MiB
as the budgeting number and ~18 MB as a best case, and measure before
committing.**

**`ort`'s own static archive** for `aarch64-linux-android` unpacks to
**119 MiB** of object files; `--gc-sections` discards unreferenced ones
at link time. **UNVERIFIED what it links down to** — nobody measured a
real NDK link. Because ONNX Runtime registers kernels statically, the
result should land near the full-build figure, so **budget ~30 MiB per
ABI, not 3.5 MiB**. Note that pyke ships **full builds with NNAPI**, so
the 3.56 MiB mobile figure is **not reachable through `ort`** at all.

**`sherpa-onnx`'s measured Android cost** (from its v1.13.7 Android
archive):

| ABI | `libonnxruntime.so` | `libsherpa-onnx-c-api.so` | **total** |
| --- | --- | --- | --- |
| **arm64-v8a** | 20.7 MiB | 4.25 MiB | **~24.9 MiB** |
| x86_64 | 23.8 MiB | 4.69 MiB | ~28.5 MiB |

**Execution providers.** `ort`'s Android prebuilts ship **NNAPI**, which
**Google deprecated in Android 15 (API 35)** — the NDK docs warn that
most future devices are expected to use the CPU backend and recommend
migrating performance-critical workloads elsewhere. ONNX Runtime's own
NNAPI page carries no deprecation notice, so **`ort`'s Android builds
bundle a deprecated accelerator path**. **XNNPACK** is the realistic CPU
accelerator for a Moonshine-sized model and is in the Maven
`onnxruntime-android` builds; whether pyke's prebuilts enable it is
**UNVERIFIED** (`dist.tsv` labels the feature set `nnapi` only).

---

## 3. Streaming semantics — what a words-while-speaking UI actually receives

From the API docs, options reference and C API reference.

- **Input**: `add_audio()` takes 32-bit float mono PCM in [-1.0, 1.0].
  **Chunk size is free-form** — "the size and sample rate of the audio
  should be whatever's natural for your source"; resampling is internal.
  Our recorders emit 16 kHz mono s16
  (`AndroidAudioRecorder.kt:22,28,58`), a trivial conversion away.
- **Decode is caller-driven**: `add_audio` only buffers.
  `moonshine_transcribe_stream()` runs the analysis but **no-ops unless
  200 ms of new audio has accumulated**, unless
  `MOONSHINE_FLAG_FORCE_UPDATE` is passed. `transcription_interval`
  defaults to **0.5 s**.
- **Partials exist and are REVISABLE — not stable prefixes.** Events
  `LineUpdated` / `LineTextChanged` fire during a segment;
  `decode_incomplete_lines` defaults **true**. The only guarantee is at
  the end: *"Once `LineCompleted` has been called, the library will
  never alter that line's text, timing, or audio data again."*
  **A UI must therefore be built to let the tail rewrite itself** — which
  is precisely the interaction ADR 0017's **no-reflow law** was written
  to constrain. That collision is a design question, not an engineering
  one, and it belongs in the ADR.
- **VAD is Silero**, credited to snakers4/silero-vad on the
  acknowledgements page — not in-house. Options: `vad_threshold` 0.5
  (0 disables), `vad_window_duration` 0.5 s, `vad_hop_size` 512,
  `vad_look_behind_sample_count` 8192, **`vad_max_segment_duration` 15 s
  forced cut**.
- Speculative decoding is available (`use_speculative_decoding`).

**Latency**, from the vendor's own
[benchmarks](https://moonshine-voice.readthedocs.io/en/stable/using/benchmarks/).
Read the definition carefully: this is **time from VAD marking a phrase
complete to text returned — time-to-finalise, NOT time-to-first-token.**
**Time-to-first-token is not published: UNVERIFIED.** Medians of three
runs, device cooled, self-measured, no third party.

| Model | MacBook Pro | Linux x86 | RPi 5 | **Pixel 10a** | iPad A16 |
| --- | --- | --- | --- | --- | --- |
| Medium | 59 ms | 269 | 802 | **420 ms** | 174 |
| Small | 38 ms | 165 | 527 | **234 ms** | 95 |
| Tiny | 18 ms | 69 | 237 | **92 ms** | 37 |
| Whisper Tiny | 277 ms | 1,141 | 5,863 | — | — |

The **Pixel 10a column is the one that matters** — a real Android phone,
92 ms to finalise with streaming-tiny. Against our measured whisper
desktop figure of ~4.2 s for a ~14 s clip, these are different
categories of experience.

**Could we get partials from the engine we already ship?** whisper.cpp
has a `stream` example with sliding-window and VAD modes, and
`whisper-cpp-plus` exposes chunked realtime from Rust. But it works by
**re-running the whole model over a moving window**, and the reported
practical floor is 1–1.5 s chunks — below ~500 ms, punctuation and
capitalisation degrade for want of right context. *(Read via search
summaries of the whisper.cpp stream README and discussions, not the
source — treat as indicative.)* So partials from whisper are possible
and expensive; that cost is exactly what Moonshine's variable-length
encoder removes.

---

## 4. Integration paths against our tree

### 4.0 What a transcription provider is today, and the one timing fact that dominates

- **Declaration.** `kernel/crates/gilune-ffi/src/whisper.rs:16` returns
  a `PluginDescriptor`: `plugin_id = "org.memory.transcription"`,
  `processor_id = Some("transcribe")`,
  `subscribes = Some(SOURCE_READY_EVENT)`, capabilities `blobs.read`,
  `objects.write`, `network.request`, one `settings.form` contribution.
- **Registration.** `lib.rs:292` `register_builtins` runs on every
  `Gilune::open` behind `#[cfg(feature = "whisper")]`, granting the two
  local capabilities once on first install and never `network.request`.
- **Loading.** `lib.rs:331` `enable_whisper(model_path)` refuses unless
  the plugin is Enabled, reads `language` from the plugin settings, and
  pushes a `WhisperTranscriber` into the handle's `processors` vector.
  **Per-handle** — the widget's `RecordService` runs its own.
- **Execution.** `processor.rs:102` `run_pending_jobs` claims a job,
  matches a processor by `p.id() == job.subscription_id`, checks
  idempotency key `{processor_id}:{event_id}` against `processing_runs`,
  and commits outputs in one transaction.
- **Input contract.** `whisper.rs:190` accepts **only 16 kHz mono s16**
  and fails the job visibly otherwise.
- **Output.** `ProposedOutput { target: ReviseSubject }` (ADR 0013),
  routed at `processor.rs:182` to `add_revision`, origin
  `processor:transcribe`.

**The timing fact.** The processor runs **post-commit**. Its job exists
only because `capture.rs:186` inserted it inside the capture
transaction, after the blob was staged, fsynced and promoted; the
processor is then handed `subject_blob()` — a **complete,
digest-verified file on disk** (`processor.rs:38`).

**Therefore: realtime partials cannot come from the kernel job loop at
all, under any option below.** Words-while-speaking requires a second,
live path alongside the committed one. That is not a cost of any one
option — it is the shape of the problem, and the ADR should say so in
its first paragraph.

### 4.1 (a) Rust-in-kernel

**What it buys**: the entire existing contract, free — crash-safety,
at-least-once delivery, `processing_runs` idempotency, derived output
IDs, revise-subject landing, provenance, plugin registry, consent,
settings, backfill. One implementation serves desktop and Android, as
whisper does now. ADR 0017 § 4's "not a second pipeline — the same one,
on a smaller CPU" stays literally true.

**What it costs**:

- **No partials.** See § 4.0. This path answers "a better/faster
  transcription engine", not "words while speaking".
- **Three sub-routes, and they are not equally hard.**
  - *`ort` + legacy ONNX int8*: plain ONNX, 28.2 MB (tiny) or 63.0 MB
    (base). Preserves our single-static-cdylib shape — but **we would
    write the encoder/decoder/tokeniser loop ourselves** (no Rust
    Moonshine implementation exists anywhere), and it is
    **non-streaming**, so it is a whisper *replacement*, nothing more.
    Blocked today on rc.13 for `x86_64-linux-android` (§ 2.2).
  - *`sherpa-onnx` official Rust bindings*: **the Moonshine work is
    already done** — first-party model config, examples, v1 and v2
    models, actively released (v1.13.7, 2026-09-01). Costs ~24.9 MiB
    arm64 and **forces three `.so` files on Android** (shared linking
    only), changing our packaging shape. Apache-2.0. Also
    legacy/v2 models, **not** the streaming family.
  - *Streaming family in-kernel*: `.ort`-only, seven files, and the
    streaming state machine (frontend, adapter, cross-KV, dual decoders,
    Silero VAD, line lifecycle) lives **inside `libmoonshine`**.
    Reimplementing it over raw tensors is a research project;
    realistically it means FFI to `libmoonshine` — a second native C++
    dependency beside whisper.cpp, cross-compiled for both ABIs, with no
    Rust binding to inherit and no C-ABI crate to lean on.
- **ONNX Runtime's Android size is the binding constraint** (§ 6) —
  ~30 MiB per ABI on the official full build, versus 9.3 MiB of
  headroom.
- **A small, honest FFI change is needed regardless**: `enable_whisper`
  is a hard-coded, provider-specific door naming `whisper::PLUGIN_ID`
  literally (`lib.rs:331`). A second Rust provider needs a sibling
  `enable_moonshine(model_path)` or a generalised
  `enable_transcription(plugin_id, model_path)`. This is where "a second
  provider" stops being an implementation detail and becomes a contract
  question.

### 4.2 (b) Android-side Kotlin/ORT with the official AAR

Realtime partials come free and first-party: `ai.moonshine:moonshine-voice:0.1.5`,
minSdk 26, both our ABIs, `MicTranscriber`, its own model downloader.
This is by far the cheapest route to the owner's stated goal.

**The cost is that the final transcript has no honest way back into the
kernel.**

ADR 0015's debt 7 states it exactly
(`architecture/decisions/0015-tasks-plugin.md:565`):

> One subscription per plugin, and no route for a non-Rust processor
> across the FFI.

**Inventory of the actual FFI surface** (`gilune-ffi/src/lib.rs`, lines
317–593), which confirms it: `open`, `enable_whisper`, `run_processing`,
`schema_version`, `device_id`, `vault_path`, `capture_text`,
`begin_capture`, `begin_attached_capture` (+ `write_chunk`, `finish`),
`object_text`, `project`, `reconcile`, `status`, `recent_sources`,
`open_proposals`, `resolve_tombstone`, `register_plugin`, `plugins`,
`set_plugin_enabled`, `remove_plugin`, `reinstall_plugin`,
`grant_capability`, `revoke_capability`, `is_granted`,
`set_plugin_settings`, `backfill_jobs`, `kernel_version`.

Read it for what is **absent**: no `register_processor`, no
`submit_output`, no `revise_object`, no `add_revision` — **no write-side
counterpart to `object_text` at all** (`object_text` is read-only,
`lib.rs:432`). `register_plugin` registers a *declaration*; it cannot
carry an implementation. Kotlin may declare a Moonshine plugin, the
kernel will dutifully queue jobs for its `processor_id`, and **nothing
will ever claim them** — `run_pending_jobs` matches only against Rust
processors registered in-process (`processor.rs:110`); an unmatched job
simply lets its lease expire and is retried forever.

**The door that does exist**: write the note file and call `reconcile()`.
That is the shipped mechanism — `BoundaryTest.kt:54-55` is literally
`Files.write(path, edited)` then `g.reconcile()`, landing in
`vault.rs:380` `ingest_external_edit`, which merges `text` into the head
document (preserving `rel_path`) via `add_revision` with origin
**`"external-edit"`**.

What that route loses, precisely:

| Through the processor contract | Through `reconcile()` |
| --- | --- |
| origin `processor:transcribe` | origin `external-edit` |
| a `processing_runs` row | nothing |
| idempotency key `{id}:{event_id}` | none — a redelivery rewrites |
| derived, collision-proof revision ID | `ids::new_id()`, fresh each time |
| provenance to the audio | none |
| runs headless (CLI, widget service, future agent) | needs a live shell |

**And ADR 0013 already ruled on exactly this shape**, in *Alternatives
considered* (`0013-transcription-lands-in-the-note.md:60`):

> **App-side merge (transcribe, then edit the note from the shell)** —
> Rejected for the capture path: it makes the words' arrival depend on a
> shell being open and racing reconciliation, and it launders processor
> output through the external-edit path, blurring provenance. (The
> *dictation insertion* is deliberately an app edit — the user is
> present and the audio is already safe.)

ADR 0015 restates the rule for future work (`0015:420`): "If a later
stage parses dates out of speech, that is a processor output under
ADR 0013 and must not be laundered through the external-edit path."

So (b) is not merely awkward — **for the capture path it is a
previously-rejected shape**. Taking it means either arguing ADR 0013's
alternative was decided wrongly, or building the missing route (a
`register_processor`-shaped seam, i.e. paying debt 7 — and note the
widget's `RecordService` transcribes with **no Activity alive**, so any
Kotlin-side engine must run there too, or home-screen captures silently
stop being transcribed).

**The asymmetry the ADR itself draws is the escape hatch**:
**dictation-at-the-cursor is already sanctioned as an app edit**,
because the person is present and the audio is already durable. **A
Moonshine path scoped to dictation only sits inside existing doctrine;
the same path scoped to capture does not.** That distinction is probably
the most useful thing in this report.

### 4.3 (c) Hybrid — partials are UI, the note's words come from the pipeline

Kotlin/Moonshine runs during recording purely for display; the text is
never written anywhere; on stop, the committed pipeline produces the
note's actual words.

**Why it fits cleanly**: the live text is **not an artifact**. Nothing
is captured, projected, synced or revised. It is the same class of thing
as the *recorded · 0:42 · transcribing…* row ADR 0017 § 4 already
mandates — "the UI fills that gap, the bytes do not" (`0017:262`), and
it is precisely Google Recorder's split from prior art § 6.2. Rule 1 is
untouched (recording path unchanged); rule 2 is untouched (no stored
interpretation).

**Its honest costs**:

- **Two engines resident during a recording** — Moonshine in an ORT
  session plus whisper's model loaded for the follow-up job — against
  ADR 0017's own warning that "transcription now competes for battery
  and thermal headroom" (`0017:509`). On the widget path the service
  already pays a model load per recording.
- **Consistency**: the words a person watched appear are not
  byte-identical to the words that land in the note. Two engines, two
  vocabularies, two punctuation habits. That is a visible product wart
  needing an owner ruling, not an implementation trick. **It disappears
  only if the final transcript is also Moonshine** — i.e. (c) layered on
  (a), which is the only configuration where live and final agree by
  construction.
- **Two model downloads**, unless (a) is taken and whisper retires on
  mobile.
- The revisable-tail behaviour (§ 3) meets ADR 0017's no-reflow law
  head-on.

---

## 5. Coexistence: two transcription plugins in today's registry

Verified against `plugins.rs`, `jobs.rs`, `capture.rs`, `processor.rs`
and `revisions.rs`. There are two outcomes, decided by one field, and
**neither is good**.

Job creation (`capture.rs:186`):

```sql
INSERT INTO jobs (job_id, subscription_id, event_id, state)
SELECT ?1 || subscription_id, subscription_id, ?2, 'pending'
FROM subscriptions WHERE event_type = ?3 AND enabled = 1
```

**One job per enabled subscription row**, and subscriptions are keyed by
`subscription_id`, which `plugins.rs:396` `apply_subscription` sets to
the plugin's `processor_id`.

**Case A — Moonshine declares `processor_id = "transcribe"` (the same
id).** The two plugins **share one subscription row** (upsert
`ON CONFLICT(subscription_id) DO UPDATE`), so whichever registered last
owns its `enabled` flag.

- One job per capture — no double work, by accident rather than design.
- `run_pending_jobs` does `processors.iter().find(…)`
  (`processor.rs:110`): **the first processor in the vector silently
  wins**, and the vector's order is the order `enable_*` was called.
  Which engine transcribed a note is **unknowable from the vault** —
  both write origin `processor:transcribe`.
- **Disabling either plugin disables the other's jobs**: the shared row
  goes `enabled = 0`, and `jobs.rs:76-78` joins on `s.enabled = 1`.
- The idempotency key `{processor_id}:{event_id}` is identical for both,
  so even if the second ran, `run_exists` (`processor.rs:117`) would
  skip it as a duplicate.

**Case B — Moonshine declares a distinct `processor_id`.** Two
subscription rows, so **two jobs per capture, both claimable, both
run**.

- The audio is decoded and transcribed **twice, every time** — on a
  phone, double battery for one note.
- Both emit `ReviseSubject`. `revisions.rs:51` `add_revision` does
  `DELETE FROM heads WHERE object_id = ?1` then inserts one head, so
  **the second to finish wins the note body** and the first engine's
  transcript survives only as a superseded revision. Not a conflict, not
  two heads (rule 6 does not save us here): **last-writer-wins, decided
  by whichever engine happened to be slower.**
- Two `create-revision` operations are recorded, so **both cross to
  other devices at M5**.

**What the current registry can already express, without kernel
change:**

1. **Enable one at a time.** `set_plugin_enabled` is per plugin and
   parks jobs rather than dropping them; it is vault state, so the CLI,
   app and agent agree. Correct **only in Case B** — in Case A the
   shared subscription makes the switch cross-talk.
2. **A provider setting inside one plugin.** `set_plugin_settings` takes
   an opaque JSON document already carrying `{language, model}`; adding
   `engine` is **zero kernel change** and keeps one plugin, one
   subscription, one job. The FFI `enable_*` call then constructs the
   chosen processor. **This is the only mechanism today that is both
   correct and cheap** — at the price that "Moonshine is a second
   plugin" stops being true; it becomes a second engine inside the
   transcription plugin.
3. **Per-device default.** Not expressible as vault state — plugin
   enablement *is* vault state by deliberate design (`plugins.rs:3-5`).
   A per-device choice must live in device-local prefs (DataStore on
   Android, `Prefs` on desktop, as the vault path and theme already do)
   and be applied at `enable_*` time. That contradicts nothing, but
   nothing in the kernel enforces it either.

**What does not exist**: any notion of a *replaceable capability slot*.
`architecture/05-plugin-system.md:15-25` calls provider plugins
"replaceable capabilities such as transcription", but there is no
registry of who provides what, no arbitration and no selection.
**Two providers for one capability is an unmodelled state**, and the
kernel's behaviour in it is the accident described above, not a policy.

---

## 6. Sizes and budgets — where this decision is likely to be won or lost

**The budget**: `architecture/spikes/README.md:36` — "kernel
contribution to release package size: **under 15 MB per ABI**".
ADR 0017 § Consequences (`0017:506`) restates it and made MB0 re-measure
rather than assume.

**Where we stand** (MB0 record, owner-verified build):

| Item | Size |
| --- | --- |
| `gilune_ffi.so` arm64-v8a, with whisper | **5.70 MB** |
| same, x86_64 | 5.73 MB |
| same, before whisper (S1) | 2.96 MiB |
| ⇒ whisper.cpp's contribution | **≈ 2.7 MB** |
| **Headroom to the budget** | **≈ 9.3 MB per ABI** |

**What ONNX Runtime costs against that headroom:**

| Route | arm64 native cost | Verdict against 9.3 MB headroom |
| --- | --- | --- |
| whisper.cpp today | ≈ 2.7 MB | fits — 9.3 MB spare |
| **ONNX Runtime full build, official AAR 1.29.0** | **30.6 MiB** | **~2× the entire 15 MB budget on its own; ~3.3× the headroom** |
| ONNX Runtime, mirror build 1.23.2 (best case) | ~18.5 MB | still exceeds the whole budget |
| `sherpa-onnx` route (ORT + c-api `.so`s) | **~24.9 MiB** | exceeds the budget; also 3 `.so` files |
| ONNX Runtime minimal build (`onnxruntime-mobile` 1.18.0) | **3.56 MiB** | **would fit** — but the artefact is discontinued; build-it-yourself |
| Moonshine AAR (Kotlin side, path b) | UNVERIFIED; desktop bundles 13.3–26.1 MB compressed | outside the kernel `.so`, inside the APK |

**This is the sharpest engineering fact in the report.** A full ONNX
Runtime cannot go into `gilune_ffi.so` and stay inside ADR 0001's
budget — it is roughly **twice the whole budget** before the kernel's
own code, and `ort` cannot reach the minimal build at all (pyke ships
full builds with NNAPI). Path (a) therefore requires **one of**:

1. a **self-built reduced-operator ONNX Runtime** — the only measured
   route to ~3.56 MiB, needing an NDK build, an op config generated from
   the Moonshine model, and `.ort` conversion, and abandoning
   `download-binaries` entirely;
2. an **amendment to ADR 0001's budget**, argued on its merits;
3. **shipping ONNX Runtime as a separate `.so`** (what `sherpa-onnx`
   forces anyway) — which keeps the *kernel's* line-item honest while
   growing the APK by the same bytes, and is arguably budget-gaming.

**The design agent should not treat the budget as satisfied by any of
these without a measurement.** Two numbers nobody has: what `ort`'s
119 MiB static archive actually links down to in a real NDK build, and
whether a minimal ORT can hold Moonshine's op set near 3.56 MiB. Those
are the two questions a spike exists to answer.

Note that path (b) sidesteps the budget question entirely — the AAR's
native libraries are the app's weight, not the kernel's — which is a
genuine (if slightly bookkeeping-flavoured) advantage.

**Model side**, against `tiny-q5_1` at **31 MiB**, the pencilled phone
default (catalogue pinned by test at
`TranscriptionPluginTest.kt:58-62`: tiny-q5_1 31 / tiny 75 / base-q5_1
57 / base 142 MiB):

| Model | Download | vs tiny-q5_1 |
| --- | --- | --- |
| whisper tiny-q5_1 (today) | 31 MiB | — |
| Moonshine legacy tiny, ONNX int8 | 28.2 MB | **−3 MB, and ~5× less compute per the paper** |
| Moonshine legacy base, ONNX int8 | 63.0 MB | +32 MB |
| **Moonshine streaming-tiny (`.ort` set)** | **77.7 MB** | **+46.7 MB** |
| Moonshine streaming-small | 224.1 MB | +193 MB |

**Download-consent framing.** The model fetch is already a declared
`network.request` that "always waits for an explicit yes" and is never
auto-granted (`whisper.rs:14`, `lib.rs:299`). ADR 0017 § 4 makes the
number part of the ask. So the honest sentence a person would be shown
changes from *~31 MiB* to *~78 MB* for streaming-tiny — **two and a half
times**, on a phone, possibly on mobile data. Conversely **legacy
Moonshine tiny int8 is the only option here that makes the consent ask
smaller** (28.2 MB) while claiming parity WER and 5× less compute — but
it buys no streaming at all.

---

## 7. The fork, stated for the owner

The prior question, before any option: **realtime partials can never
come from the kernel job loop** (§ 4.0). A live path is required in
every case. What the three options really differ on is *where the note's
committed words come from*, and *what the live path is allowed to
touch*.

**(a) Rust-in-kernel.**
One pipeline, every existing guarantee, desktop and Android from one
implementation — and **no partials at all**, so on its own it does not
answer the owner's actual ask. Costs: ONNX Runtime is **30.6 MiB per
ABI** against 9.3 MB of headroom, twice the whole budget (§ 6); `ort` is
a two-year-old release candidate that **fails to build for
`x86_64-linux-android` on rc.13**, which is the ABI our emulator suite
runs; **no Rust Moonshine implementation exists**, so via `ort` we write
the inference loop ourselves; `sherpa-onnx` hands us that work
first-party but costs ~24.9 MiB and **forces three `.so` files** on
Android; and the streaming family — the only one that would actually
deliver low latency — is `.ort`-only with its state machine inside
`libmoonshine`, so it realistically means a second native C++
dependency. The genuinely cheap version of this option is **legacy
Moonshine tiny int8 as a whisper replacement**: smaller download than
today (28.2 MB vs 31 MiB), ~5× less compute per the paper, parity WER —
and no streaming whatsoever. Needs a small FFI generalisation of
`enable_whisper`.

**(b) Android-side Kotlin with the official AAR.**
Realtime partials, first-party, `ai.moonshine:moonshine-voice:0.1.5`,
minSdk 26, both our ABIs, MIT, out of the kernel's size budget
entirely — and the transcript **has no honest route home**: there is no
`register_processor` and no write-side FFI door, so it must either go
through `reconcile()` as an `external-edit` (losing origin, provenance,
idempotency and headless operation) — **a shape ADR 0013 explicitly
rejected for the capture path** — or the ADR must build the missing
seam and pay debt 7. Also loses the widget path unless the engine runs
in `RecordService` too. **Sanctioned today only for dictation**, where
ADR 0013 already permits an app edit.

**(c) Hybrid — live partials UI-side, committed words from the
pipeline.**
Constitutionally the cleanest (nothing is stored, so nothing is
laundered) and it matches Google Recorder's split and ADR 0017's own
pending-state doctrine. Costs: two engines resident during a recording,
with the battery and thermal bill ADR 0017 already names; a visible
inconsistency between the words watched and the words kept, which
resolves **only** by layering (c) on (a); two model downloads; and a
revisable partial tail that runs straight into the no-reflow law.

**Cross-cutting, whichever is chosen:** two transcription plugins in
today's registry is an **unmodelled state** — same `processor_id` means
one silently wins and disabling either disables both; different
`processor_id` means double transcription and the *slower* engine wins
the note body (§ 5). The cheapest correct mechanism today is an
`engine` key in the existing plugin-settings JSON — at the cost of
Moonshine not really being a second plugin.

---

## 8. What a spike would have to measure

Nobody has these numbers, and each one can move the decision:

1. **What `ort`'s 119 MiB static archive links down to** in a real NDK
   build with `--gc-sections`. This single number decides whether (a) is
   budget-feasible at all.
2. **Whether a self-built minimal ONNX Runtime can hold Moonshine's op
   set** anywhere near the measured 3.56 MiB.
3. **The Moonshine AAR's actual per-ABI native size** — the only missing
   number in path (b)'s cost.
4. **Time-to-first-token**, not time-to-finalise. Every published
   latency figure is the latter; the owner's ask is words appearing
   *while* speaking, which is governed by the former.
5. **Whether the revisable partial tail can live under ADR 0017's
   no-reflow law** — a UI question that a Compose spike answers and an
   ADR cannot assume.
6. Which of the two contradictory vendor doc surfaces is right about
   **non-English streaming models and their licence** (§ 1.6).
