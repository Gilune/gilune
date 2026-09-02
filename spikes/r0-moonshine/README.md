# Spike R0 — Moonshine streaming on Android

Status: **Complete** — verdict **PASS WITH CONDITIONS** (§ Verdict). Streaming
genuinely works, the AAR is roughly half the weight the research feared, and
the model download is **45.23 MB, not 77.7 MB**. Time-to-first-token misses
ADR 0018's "under about a second" on the library's default configuration and
beats it on one tunable — which is the substance of the conditions.

Gate for **ADR 0018 § 6 R0**. The tests stay in the tree as evidence and skip
themselves when the model is absent:

```text
app/composeApp/src/androidInstrumentedTest/kotlin/org/gilune/app/
  R0Moonshine.kt            the rig: model lookup, WAV reader, the paced feeder
  R0MoonshineSpikeTest.kt   ten measurements, a01–a10
```

One dependency line was added, scoped to `androidInstrumentedTest`, in
`app/composeApp/build.gradle.kts`. **No repository addition was needed** —
`mavenCentral()` is already declared in `settings.gradle.kts`. Nothing else in
the tree was touched: no product file, no manifest, no ADR.

## What was asked

ADR 0018 § 6, verbatim: "The AAR on the x86_64 emulator: does streaming
genuinely work there; the AAR's **actual per-ABI native size**; **what
`AssetDownloader` actually fetches** for streaming-tiny; **time-to-first-token**,
not time-to-finalise; and whether the frozen-lines/muted-tail caption reads
calmly in Compose. Pass = streaming works, TTFT is under about a second, the
caption reads calmly, and the APK cost is a number the owner accepts."

Four of those five are answered with numbers below. **The fifth — does the
caption read calmly in Compose — is NOT answered here**, deliberately and by
instruction: it is a screenshot question that requires shell code, and this
spike was chartered to touch no product surface. It is carried forward as R1's
first obligation and is listed under Honest gaps.

## Ran on

- Emulator AVD `gilune-s1`, headless (`-no-window -gpu swiftshader_indirect
  -no-audio`), booted from snapshot.
- **Android 16, API 36, x86_64.**
- `ai.moonshine:moonshine-voice:0.1.5` (MIT, published 2026-08-24), resolved
  from Maven Central. Native header version reported by the library: `30000`.
- AGP 8.10.1, Kotlin 2.1.20, minSdk 29 / targetSdk 36 / compileSdk 36.
- Windows 11 host; SDK at `%LOCALAPPDATA%\Android\Sdk` with the stale
  `ANDROID_HOME` overridden per process, per CLAUDE.md.

Tallies are in § Suites.

### A note on how the suite was driven

`connectedDebugAndroidTest` **uninstalls the app when it finishes**, which
deletes app-private storage and therefore the downloaded model. The
measurement runs are consequently driven through `adb shell am instrument`
against manually installed APKs, so the model survives between runs. This is a
harness detail, not a finding, but it is the reason a naive
`connectedAndroidTest` will always show the R0 model tests skipping — which is
the designed behaviour anyway.

## Question 1 — does streaming genuinely work on the x86_64 emulator?

**Yes, unreservedly.** `System.loadLibrary("moonshine-jni")` resolves and pulls
`libmoonshine.so` and `libonnxruntime.so` after it in 35–87 ms; the streaming
tiny model loads; PCM fed through `createStream`/`addAudioToStream` produces
revisable partials and completed lines; the transcript is correct.

This is not a formality. The research's in-kernel route (`ort`) **cannot build
for `x86_64-linux-android` at all** on its current release, so "works on the
ABI every instrumented run uses" was a live question, and the AAR answers it
where `ort` does not.

The clip is a host-generated TTS wav (Windows `System.Speech`, 16 kHz mono
s16, 13.57 s), pushed by adb — the same trick M4b used to prove whisper end to
end. Full transcript returned, all three sentences:

> "The quick brown fox jumps over the lazy dog. Jai Yun keeps every recording
> exactly as it was made. Words should appear while I am still speaking, not
> after I stop."

**"Gilune" came back as "Jai Yun" (and "Jiu-jun" mid-flight).** The product's
own name is out of vocabulary for streaming-tiny. `Transcriber.setKeyterms(List)`
exists and was **not** exercised; it is the obvious remedy and R1 should test
it rather than assume it.

### The seam this used, and why it is the right one

The rig drives `Transcriber` directly with its own PCM rather than using
`MicTranscriber`, which opens its own recorder. That is exactly ADR 0018 § 1's
"a tee off that buffer, not a second microphone", so what was measured is the
path R1 would actually ship.

Decompiling settles the threading, which is what makes TTFT measurable at all:
`addAudioToStream` buffers, then — **on the calling thread**, when `isUpdateDue`
says enough new audio has landed — runs `moonshineTranscribeStream` and
notifies listeners inline. No worker thread, no queue. Every timestamp below is
therefore the feeder's own clock with nothing hidden behind a handoff.

## Question 2 — the per-ABI size truth

Measured by unpacking the artefacts, not quoted.

**`libonnxruntime.so` inside the Moonshine AAR is 6.29 MB on arm64 — not the
30.6 MiB the research measured from Microsoft's official AAR.** Moonshine ships
a reduced ONNX Runtime build. That is the single largest correction this spike
makes to the research's numbers, and it changes the shape of the argument in
§ 6 of the research note.

Uncompressed, per ABI, three `.so` files each:

| ABI | `libmoonshine.so` | `libonnxruntime.so` | `libmoonshine-jni.so` | **total** |
| --- | --- | --- | --- | --- |
| **arm64-v8a** | 11,112,456 | 6,291,928 | 104,520 | **17,508,904 B = 16.70 MiB** |
| **x86_64** | 11,427,872 | 6,673,840 | 107,352 | **18,209,064 B = 17.37 MiB** |
| armeabi-v7a | 9,413,624 | 3,865,652 | 57,848 | 13,337,124 B = 12.72 MiB |

**These are the APK numbers too, and that is not obvious.** AGP packages native
libraries **`Stored`** — uncompressed and page-aligned, so the platform can
mmap them straight out of the APK. Verified against the built test APK: every
Moonshine entry's in-zip size equals its uncompressed size. **There is no
compression saving on the way into the package.** (The AAR's own zip does
compress them, to 7.82 MiB arm64 / 8.08 MiB x86_64, which is what Play's
transfer compression would see — but the installed footprint is the full
figure.)

### Total APK delta, measured against a control build

A control test APK was built with the dependency and both spike sources
removed, everything else identical:

| Build | Size |
| --- | --- |
| test APK **with** Moonshine (all three ABIs) | 60,499,509 B |
| test APK **without** | 3,030,276 B |
| **delta** | **57,469,233 B = 54.81 MiB** |
| of which native `.so`, three ABIs | 49,055,092 B |
| **of which NOT native** | **8,414,141 B = 8.02 MiB** |

**That 8.02 MiB is the finding nobody had.** It is dex and merged resources
from the four transitive dependencies the POM declares at runtime scope —
`androidx.appcompat:1.6.1`, **`com.google.android.material:1.10.0`**,
`com.squareup.okhttp3:4.12.0`, `androidx.work:work-runtime:2.9.1`. Our app is
Compose-only and ships none of them today, so appcompat and Material arrive as
new weight and a new theming surface. (The control also lacked the two spike
`.kt` files, so 8.02 MiB is a few kilobytes generous.)

**Nearly all of it is avoidable — see § The transitive dependencies below.**

**What a device actually downloads**, per-ABI delivery, native + the shared
non-native part paid once:

| ABI | native | + non-native | **per-device delta** |
| --- | --- | --- | --- |
| **arm64-v8a** | 16.70 MiB | 8.02 MiB | **≈ 24.72 MiB** |
| x86_64 | 17.37 MiB | 8.02 MiB | ≈ 25.39 MiB |

Two packaging notes for R1. The AAR ships **armeabi-v7a**, which Gilune does
not; without an `abiFilters` narrowing, a universal build carries 12.72 MiB it
can never run. And **three `.so` files per ABI** is the same departure from our
single-cdylib shape that the research flagged against `sherpa-onnx` — it simply
costs less here.

**ADR 0001's 15 MB-per-ABI kernel budget is untouched and untouchable by this
route**: `gilune_ffi.so` does not grow by a byte, because Moonshine links into
nothing of ours. The number above is the one ADR 0018 § 7 Q5's proposed
companion app-package budget would be set from.

### The transitive dependencies — and how much of them R1 can refuse

The 8.02 MiB above is not a fixed cost. Every class in the AAR was decompiled
and checked for references to the four libraries. The result is unusually
clean:

| Dependency | Referenced by | Does R1 need it? |
| --- | --- | --- |
| `com.google.android.material:1.10.0` | **nothing at all** | **No.** Zero bytecode references anywhere in the AAR. Declared and never used. |
| `com.squareup.okhttp3:4.12.0` | `AssetDownloader` only | **No** — see below. |
| `androidx.work:work-runtime:2.9.1` | `MoonshineDownloadWorker` only | **No.** Background-download plumbing we do not use. |
| `androidx.appcompat:1.6.1` | `Transcriber` only | **Almost certainly not** — the single reference is the parameter type of `loadFromAssets(AppCompatActivity, …)`, a method R1 never calls. R1 uses `loadFromFiles`. |

None of `JNI`, `ModelSpec`, `ModelCache`, `Transcript`, `TranscriptLine`,
`TranscriptEvent` or `TranscriberOption` references any of the four.

**The okhttp exclusion is not a sacrifice — it is what ADR 0018 § 4 already
requires.** The model fetch must go through the plugin's `network.request`
consent, Wi-Fi-only by default, which means our own downloader rather than
`AssetDownloader`; and the kernel already fetches whisper models. Dropping
okhttp and `work-runtime` follows from a decision the ADR has taken, not from
a size argument.

#### They are not only weight — two of them inject a ContentProvider that crashes

Found the hard way, in this spike's own suite. `androidx.work` and
`appcompat` (through `emoji2`) each contribute an
**`androidx.startup.InitializationProvider`** to the merged manifest. With the
dependency added, the test APK's manifest gained one, authority
`org.gilune.app.test.androidx-startup`:

```text
with the transitive deps:  provider androidx.startup.InitializationProvider
                           + 7 uses-permission
with them excluded:        no provider
                           + 4 uses-permission
```

AGP then **dedupes the `androidx.startup` classes out of the test APK**,
because the app APK already carries them. Any process started for the test
package *alone* — which is exactly what Espresso's
`InstrumentationActivityInvoker$EmptyActivity` does — therefore dies before a
test runs:

```text
FATAL EXCEPTION: main
Process: org.gilune.app.test
java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider
Caused by: java.lang.ClassNotFoundException: Didn't find class
  "androidx.startup.InitializationProvider" on path: DexPathList[[zip file
  ".../org.gilune.app.test-....apk"]]
```

It repeated on every UI-test process launch. **No test reported a failure** —
the invoker recovers — but it is a process crash per activity launch, and on a
Compose-only app that ships none of these libraries it is pure imported risk.
Excluding the two removes the provider outright; **verified: zero occurrences
after the change**.

**The generalisable warning for R1**: a bundled AAR's transitive dependencies
can inject manifest components into the consuming app, and dependency exclusion
is the only thing that removes them. This is the same class of problem as the
merged permissions in § "The manifest merges permissions" above, and it is why
those two sections should be read together before the AAR is promoted to
`androidMain`.

**Measured**, same control method, all four excluded at the Gradle level:

| Build | Size | Moonshine delta (3 ABIs) |
| --- | --- | --- |
| with the transitive deps | 60,499,509 B | 54.81 MiB |
| **with all four excluded** | **51,972,633 B** | **46.68 MiB** |
| **saving** | **8,526,876 B = 8.13 MiB** | |

The remaining delta is the native libraries (46.78 MiB across three ABIs) plus
Moonshine's own ~104 KB of classes; the non-native contribution falls to
**essentially zero**, within zip-alignment noise.

So the per-device figure R1 should budget against, arm64 with the exclusions
and `armeabi-v7a` filtered out, is **≈ 16.8 MiB** rather than 24.72 MiB — a
third off, for four lines of Gradle:

```kotlin
implementation("ai.moonshine:moonshine-voice:0.1.5") {
    exclude(group = "androidx.appcompat")
    exclude(group = "com.google.android.material")
    exclude(group = "com.squareup.okhttp3")
    exclude(group = "androidx.work")
}
```

**What this spike's tree actually ships**, and why it is the middle option:

```kotlin
implementation("ai.moonshine:moonshine-voice:0.1.5") {
    exclude(group = "androidx.appcompat")
    exclude(group = "com.google.android.material")
    exclude(group = "androidx.work")
}
```

`okhttp` is **kept**, because `a03` measures the vendor's own downloader and
genuinely needs it. The other three are excluded because they buy nothing and
cost the ContentProvider crash above.

| Configuration | Test APK | vs full |
| --- | --- | --- |
| full transitive set (the vendor's default) | 60,499,509 B | — |
| **as this tree ships (okhttp kept)** | **54,847,789 B** | **−5.39 MiB** |
| all four excluded (R1's target) | 51,972,633 B | −8.13 MiB |

So okhttp alone is 2.74 MiB, and R1 drops it by fetching models itself.

**Verification**: the shipped configuration **compiles, packages, and runs** —
R0 is **10/10 green** on it, `a03` included, with **zero** provider crashes.
The `appcompat` exclusion is the one that needed proving, because `Transcriber`
names `AppCompatActivity` in the signature of `loadFromAssets`; ART resolves
that lazily and the uncalled method does not fault. That is now measured rather
than assumed, but R1 should keep a smoke test on it.

### The AAR carries far more than transcription

`classes.jar` and `libmoonshine.so` contain text-to-speech, a Kokoro vocoder,
grapheme-to-phoneme for several languages, a Gemma-300M embedding model, voice
cloning, speaker diarization and an "AgentFlow" dialogue interpreter. **We
would use the speech-to-text slice and pay for all of it**, and there is no
published slimming switch. That is a meaningful fraction of the 11.1 MB
`libmoonshine.so`.

### The manifest merges permissions into the consuming app

The AAR's own manifest declares `INTERNET`, `ACCESS_NETWORK_STATE` and
`RECORD_AUDIO`, and an activity, `ai.moonshine.voice.MicrophonePermissionActivity`.
Gilune already declares `INTERNET` and `RECORD_AUDIO`; **`ACCESS_NETWORK_STATE`
would be new**, and so would the activity. Bundling the AAR therefore changes
the app's declared permissions without anyone writing a line of manifest —
which matters directly to ADR 0018 § 5's consent story and should be an R1
review point (the AAR's comments state `tools:node="remove"` is supported).

## Question 3 — what the downloader actually fetches

`Transcriber.getSttDependencies(language, options)` is a **native** call: the
file list lives inside `libmoonshine.so`. That is why the research could not
reach it, and why the answer could only ever come off a device. It returns the
same manifest `AssetDownloader` resolves, so `a02` reads it **without
downloading anything**, and `a03` then downloads and measures what landed.
**The two agree byte for byte.**

Base URL — the vendor's own CDN, not HuggingFace:
`https://download.moonshine.ai/model/tiny-streaming-en/quantized_26_08_21`

| File | Bytes |
| --- | --- |
| `decoder_kv.ort` | 32,583,720 |
| `encoder.ort` | 7,675,440 |
| `frontend.weights.ort` | 2,093,464 |
| `adapter.ort` | 1,319,664 |
| `cross_kv.ort` | 1,287,544 |
| `tokenizer.bin` | 249,974 |
| `frontend.model.ort` | 23,344 |
| `streaming_config.json` | 509 |
| **total, 8 files** | **45,233,659 B = 45.23 MB = 43.14 MiB** |

**The research's 77.7 MB is wrong for this path, and its own UNVERIFIED guess
was right.** It counted the HuggingFace asset tree, which carries **both**
near-identical decoders; the downloader fetches **one** (`decoder_kv.ort`).
Research § 1.2 said "tiny drops to ~45 MB if only one is actually needed" —
measured: **45.23 MB**.

Consequences for ADR 0018 § 4, which should be amended on acceptance:

- The consent ask is **~45 MB**, not ~78 MB. Against today's pencilled phone
  default of whisper tiny-q5_1 at 31 MiB (32.5 MB) that is **+39%**, not the
  "two and a half times" the ADR states.
- Every file carries a **crc32c checksum** in the manifest, and the downloader
  writes through a `.part` file with HTTP `Range` resume and a disk-space
  precheck. Resumable and idempotent are properties it already has, not ones
  R1 must build.
- Download took **21.2 s** over the emulator's NAT'd connection. Indicative
  only; it is a network measurement, not a device one.

Two API traps worth recording, both of which cost a build cycle:

1. **`loadFromFiles(path, int)`'s bare `int` is the MODEL ARCHITECTURE**, not
   flags — `MicTranscriber.load` passes its `arch` field into that slot.
2. **`model_arch` is a manifest option, not a transcriber option.**
   `getSttDependencies` requires it; the native loader rejects it outright
   ("Unknown transcriber option: 'model_arch'").

Both surface as the same message — `Failed to load transcriber from files:
'<path>'` — which **names the path**, so an architecture mismatch reads like a
missing file. A third: `AssetDownloader.ensureModelPresent(root, …)` writes
files **straight into the root it is handed**, while `ModelCache.directoryFor`
appends a `stt-en-2` key. The two halves of the AAR's own cache API disagree
about where a model lives; R1 must pick one convention.

## Question 4 — time to FIRST token

The distinction ADR 0018 insists on is real and this is the measurement it
asked for. Audio is fed in 100 ms chunks **paced to real time**, so the clock
runs the way it would with a live microphone.

| `transcription_interval` | **TTFT** | audio fed at first text | lag behind speaker | first text |
| --- | --- | --- | --- | --- |
| **0.5 s (library default)** | **1,149–1,188 ms** | 900 ms | 249–288 ms | "The quick, quick, quick," |
| **0.25 s** | **649 ms** | 500 ms | 149 ms | "10." |
| 0.1 s | 1,160 ms | 900 ms | 260 ms | "The quick, quick, quick," |

Read that table carefully, because it is the whole of the condition on this
gate.

- **On the library's default the answer is NO: 1.15–1.19 s, just the wrong
  side of ADR 0018's "under about a second".**
- **On a one-line change it is YES: 649 ms**, and the caption sits ~150 ms
  behind the speaker rather than ~290 ms.
- **0.1 s is no better than the default**, so the relationship is not monotone
  and R1 must not assume "smaller is faster". `isUpdateDue` clamps its
  threshold against the *previous pass's* cost, so a very short interval is
  absorbed by decode time.
- **Prompter is not the same as better.** At 0.25 s the first thing on screen
  is `"10."` — a token with no relation to the speech, revised away moments
  later. A caption that must be watched will show that. It is exactly the
  revisable tail ADR 0018 § 1 designs for, and it is an argument for the muted
  role rather than against the setting, but the owner should see it before
  choosing.
- Decode cost did **not** rise as the interval shrank (rtf 0.358 → 0.335 →
  0.370). The expected battery penalty for a prompter caption did not appear
  in this range. On an emulator, with ~20% run-to-run variance, that is weak
  evidence and should be re-measured on hardware before anyone relies on it.

**The floor underneath all of this**: fed flat out, the first partial arrives
**308 ms** of wall clock after the engine has seen **900 ms of audio**. So TTFT
decomposes into an audio-accumulation term the configuration controls and a
decode term the hardware controls. Paced TTFT can never beat the accumulation
term, whatever the CPU.

**Time-to-finalise, for comparison with the vendor's published figures**: the
whole 13.57 s clip decoded in 4.5–6.3 s of CPU, an rtf of **0.335–0.461**.
Self-reported per-pass latency (`TranscriptLine.lastTranscriptionLatencyMs`)
ran 96–416 ms. Against our measured whisper desktop figure of ~4.2 s for a
~14 s clip these are, as the research put it, different categories of
experience — but this is x86_64 under swiftshader and it is **indicative only**.

## Question 5 — the caption feed, as actually received

The 13.57 s clip, three sentences, default configuration. **47 events**:

| Event | Count |
| --- | --- |
| `LineStarted` | 3 |
| `LineTextChanged` | 23 |
| `LineUpdated` | 18 |
| `LineCompleted` | 3 |
| `LineSpeakersChanged` / `Error` | 0 |

Cadence between consecutive callbacks: **min 0 ms, median 334 ms, max 890 ms.**

The lifecycle as observed, which differs in useful ways from a naive reading:

- **`LineStarted` and `LineTextChanged` fire together**, same instant, same
  text. A UI can listen to `LineTextChanged` alone and miss nothing.
- **`LineUpdated` and `LineTextChanged` also co-fire** on every revision. The
  pair is redundant for our purposes; the zero-length gaps in the cadence
  figure above are these pairs.
- **The last `LineTextChanged` before `LineCompleted` already carries
  `isComplete = true` and the final text.** `LineCompleted` adds no new text —
  it is a punctuation mark on a transition already visible.
- **One line per sentence.** The VAD segmented the three sentences into three
  lines with no tuning.
- **`LineCompleted` really does freeze.** Asserted in `a07` for every line: no
  event after the completion index changed that line's text. The vendor's one
  hard guarantee holds here.

**Partials are revisable wholesale, and far more violently than "the tail
rewrites itself" suggests.** One line's actual trajectory:

```text
+1185ms  "The quick, quick, quick,"
+1581ms  "The quick brown"
+2084ms  "The quick brown fox can"
+2696ms  "The quick brown fox jumps up so"
+3309ms  "The quick brown fox jumps over the lace of the"
+3690ms  "The quick brown fox jumps over the lazy dog."   (complete)
```

"lace of the" becomes "lazy dog."; "fox can" becomes "fox jumps up so". These
are not tail refinements — mid-phrase words change. **ADR 0018 § 1's decision
to exempt the caption from the no-reflow law is vindicated by this table**, and
its "may rewrite itself wholesale" is if anything understated. A three-line
bottom-anchored block that re-wraps only the line in flight is the right
shape; anything that tried to hold words still would be fighting the engine.

**Chunk size does not govern cadence.** Feeding 20 ms chunks instead of 100 ms
produced 46 events against 47 and a byte-identical transcript. Cadence is
`transcription_interval`'s, not the caller's — which matters because our
recorder's chunking is fixed by the capture path and cannot be tuned for the
caption.

**Silence produces nothing at all.** Five seconds of digital silence: **zero
events**, no line started, no text. The VAD does not hallucinate. Set beside
whisper's `[BLANK_AUDIO]` marker landing as a note body — the open wart ADR
0018 § Consequences names — Moonshine's empty case is genuinely empty and
needs no special rule.

## Memory

Native heap, `Debug.getNativeHeapAllocatedSize()`:

| Point | Native heap |
| --- | --- |
| before load | 15–17 MB |
| after model load | 71–74 MB |
| after a 13.5 s streaming session | 74–80 MB |

**A loaded session costs roughly 57 MB of native heap**, and streaming adds
about 5 MB more on top. That is the figure ADR 0018 § 4's "two engines resident
during a recording" concern should be argued against at R1, when whisper's
resident cost is measured on the same device.

Session initialisation, which R1 pays on the capture surface:

| | Cold process, cold page cache | Warm |
| --- | --- | --- |
| `loadFromFiles` | **1,158 ms** | 142–494 ms |
| `createStream` + `startStream` | 0–4 ms | 0 ms |

Opening a stream is free; loading the model is not. A shell that loads on first
frame will show a second of nothing.

## Suites

**R0 itself: 10/10 green**, re-verified on the final tree. `a03`, the only test that touches the network,
skips itself unless `-e gilune.r0.fetch 1` is passed, so a default run is
9 executed + 1 skipped and reports `OK (10 tests)`. With the model absent
altogether, `a04`–`a10` skip too and only `a01`/`a02` run — which is the
designed behaviour: **no suite run can be made to download 45 MB by accident.**

**Desktop: 420/420 green, 0 failures, 0 skipped**, on a forced
`--rerun-tasks` run. R0 touches no desktop source, and the Gradle change is
scoped to `androidInstrumentedTest`, so `desktopTest` did not even invalidate
— it reported UP-TO-DATE until the re-run was forced. That is itself the
evidence that the dependency cannot reach desktop.

**Full Android instrumented suite: 518 run, 2 failures — both pre-existing
MS1 flakes, proven not ours.**

The two are `Ms1ControlsTest.theSameComposeFieldAccumulatesWithNoTransformationAtAll`
and `Ms1ImeSpikeTest.theLegacyFieldStopsOpeningComposingRegionsAfterACommit`.
Both are MS1 tests that **assert a platform defect reproduces** — that the
legacy `BasicTextField` stops opening composing regions after a commit. On this
API 36 image the defect no longer reproduces reliably, so the assertions that
pin it fail. MS1's own README already records this fragility as intermittent
("roughly one run in four", "markedly better and not proven perfect").

**Proven, not asserted.** The failure count is itself unstable — 2 in the full
suite, 3 on an immediate re-run of the same three classes. So a control was
built with **the Moonshine dependency removed entirely and both R0 sources
moved out of the tree**:

| Build | MS1 classes | Result |
| --- | --- | --- |
| with R0 (full suite) | 39 | 2 of these failing |
| with R0 (re-run, same APK) | 39 | 3 failing |
| **no Moonshine, no R0 sources at all** | 39 | **the same 3 failing** |

Identical failures with R0 wholly absent. **These belong to MS1 and the
emulator image, not to this spike.** Per the spike brief they are noted and
not chased.

Excluding them, **516/518 green**, and every R0 test passes. The suite also
ran **without a single `InitializationProvider` crash** once the transitive
dependencies were excluded — against dozens of them beforehand, which is what
put that defect on the record above. The clean run took about 13 minutes
against 24+ for the crashing one.

## Honest gaps

- **The Compose caption was not built, so ADR 0018 § 6's "does it read calmly"
  is UNANSWERED.** It requires shell code and this spike was chartered to touch
  no product surface. It is R1's first obligation, and the event trace above is
  the input it needs. **The gate cannot be called fully passed without it.**
- **Battery and thermals: unmeasurable here, as ADR 0018 § 6 already concedes.**
  No figure is offered. MS2's rule applies verbatim.
- **x86_64 only. No ARM64 execution, as everywhere else in this repo.** Every
  latency and rtf number is emulator-under-swiftshader and must be re-measured.
  The vendor's Pixel 10a figure (92 ms to finalise, streaming-tiny) is a
  different machine and a different metric.
- **Run-to-run variance is roughly 20%** on the emulator (rtf 0.335–0.461 for
  identical configurations). Single figures above are not medians of many runs;
  each table row is one run unless a range is given. A hardware pass should
  take medians.
- **"Real speech" is a TTS clip, not a person.** It is clean, studio-quiet,
  single-speaker, one accent, no crosstalk, no room. It proves the machinery
  and it proves nothing about WER in a kitchen. Accuracy remains as the
  research left it: vendor self-published.
- **`setKeyterms` was not exercised**, though the transcript shows we need it —
  the product's own name came back wrong.
- **Only streaming-tiny was measured.** small and medium were not downloaded
  and not run; the legacy non-streaming fallback of ADR 0018's Alternative (a)
  was not touched at all.
- **The one-active-provider rule, the FFI processor route and the kernel are
  entirely untouched** by this spike. R0 measured an Android library in
  isolation. Nothing here says the integration works.
- **The download was measured once, over emulator NAT.** No Wi-Fi-only path,
  no interrupted-resume test, no metered-connection behaviour. The `.part` +
  `Range` machinery was read in the bytecode, not exercised.
- **The transitive dependencies were characterised, not exhausted.** One
  concrete fault was found and fixed (the `InitializationProvider` crash), and
  the exclusions were proven to build and run. What was NOT tested: whether
  appcompat and Material collide with the Compose-only app's theming at
  runtime when they ARE present, and whether excluding `appcompat` holds under
  ProGuard/R8 in a release build — this spike only ever built debug. R1 must
  check the release build, because a shrinker that verifies more eagerly than
  ART is exactly where an unresolvable `AppCompatActivity` signature could
  bite.
- **`MicrophonePermissionActivity` survives every exclusion**, since it is
  declared in Moonshine's own manifest. Removing it needs
  `tools:node="remove"`, which was not attempted here.

## Verdict

**PASS WITH CONDITIONS.** R1 may proceed.

Streaming genuinely works on the emulator; the AAR is **16.70 MiB per ABI on
arm64**, roughly half what the research's ONNX Runtime figure implied; the
model ask is **45.23 MB**, not 77.7 MB; partials arrive with revisable,
wholesale-rewriting text and `LineCompleted` freezes as promised; silence
produces nothing. On the evidence a live caption is buildable and the engine is
not the risk.

The conditions:

1. **TTFT passes only with `transcription_interval` set.** The library default
   of 0.5 s gives 1.15–1.19 s, outside ADR 0018 § 6's bar. 0.25 s gives 649 ms
   and passes. R1 must set the interval explicitly, and must re-measure on
   ARM64 before the figure is quoted anywhere as ours — the accumulation term
   is configuration, but the decode term is hardware.
2. **The caption's Compose rendering remains unproven and the gate is not
   fully discharged until it is.** R1 must produce the screenshot ADR 0018 § 6
   asks for, against the event trace recorded here, and a failure there
   reopens this verdict.
3. **The APK cost is the owner's call, not this spike's — but the number to
   put to the owner is 16.8 MiB, not 24.7 MiB.** As shipped by the vendor the
   delta is ≈ 24.72 MiB per arm64 device; **excluding the four transitive
   dependencies at the Gradle level removes 8.13 MiB of it, measured**, and
   filtering `armeabi-v7a` removes another 12.72 MiB from a universal build.
   R1 must adopt both. ADR 0018 § 7 Q5's companion app-package budget should
   be set from **≈ 16.8 MiB per ABI**, and set before R3, not after.
4. **Four packaging facts must be handled in R1, not discovered in it**:
   native libraries are stored uncompressed so there is no compression relief;
   `armeabi-v7a` ships in the AAR and must be filtered out; the AAR merges
   `ACCESS_NETWORK_STATE` and an activity into our manifest, which touches the
   consent story ADR 0018 § 5 sets out; and — **the one that actually bit** —
   `androidx.work` and `appcompat` inject an
   `androidx.startup.InitializationProvider` whose class AGP then dedupes out,
   crashing every process started for the package alone. **Exclude the
   transitive dependencies.** It is not an optimisation, it is a defect fix,
   and it is worth 8.13 MiB besides.
5. **ADR 0018 § 4 should be amended on acceptance** to the measured download
   figure. "77.7 MB, two and a half times the whisper default" becomes
   "45.23 MB, +39%", and the consent ask a person is shown changes with it.
