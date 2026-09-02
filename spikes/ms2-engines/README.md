# Spike MS2 (preview) — whisper and Moonshine over one clip

Status: **EMULATOR PREVIEW — this is not MS2 and discharges nothing.** It is a
relative standing between the two engines, taken on x86_64 under an emulator,
to inform MS2 ahead of hardware. No ADR status changes on the strength of it.

MS2 (ADR 0017 § 8, widened by ADR 0018 § 6) is **whisper on ARM64, on the
owner's physical device, over a fixed clip set, with battery and thermals
recorded beside the seconds** — now measuring **both engines on the same
device**, and setting the packaged mobile default. The device does not exist
yet. Everything below was measured on the same x86_64 emulator every other
Android figure in this repository came off.

## What this preview can and cannot say

**Can say**: that both engines run the app's real routes on the same audio;
roughly how they stand *relative to each other* on one machine; what each one
does to a clip full of the vocabulary the desktop benchmark found hard; and
what a live caption's time-to-first-token looks like beside an engine that
emits nothing until it is finished.

**Cannot say**, and no sentence here should be read as saying:

- **Nothing about ARM64.** x86_64 emulator numbers do not predict ARM64 phone
  performance. Not the ratio, not the direction, not the magnitude. The
  arm64 whisper `.so` has still never executed anywhere in this repository, and
  Moonshine's ARM ONNX paths are a different code path from the x86_64 ones
  measured here.
- **Nothing about battery or thermals.** Unmeasured and unmeasurable here.
  There is no sustained-load figure, no throttling curve, and no idea what
  either engine costs a phone over a day. That is the substance of MS2.
- **Nothing about real acoustics.** The clip is text-to-speech: clean,
  studio-quiet, one speaker, one accent, no room, no crosstalk. It proves the
  machinery and it proves nothing about word error rate in a kitchen.
- **Nothing about the packaged default.** ADR 0018 § 6 reserves that decision
  for MS2 explicitly. This preview is an input to it.

## Ran on

- Emulator AVD `gilune-s1`, headless (`-no-window -gpu swiftshader_indirect
  -no-audio`), booted from snapshot.
- **Android 16, API 36, x86_64**, `sdk_gphone64_x86_64`, **4 vCPUs**,
  2.4 GiB RAM, on an AMD Ryzen 9 3900X host running Windows 11.
- Tree at `daa9191` (R3 shipped), `composeApp` debug + debug-androidTest APKs
  installed manually so app-private storage survived between runs.
- SDK at `%LOCALAPPDATA%\Android\Sdk` with the stale `ANDROID_HOME` overridden
  per process, per CLAUDE.md.

**Four vCPUs matters.** The kernel sets whisper's thread count to
`min(available_parallelism, 8)` (`kernel/crates/gilune-ffi/src/whisper.rs`), so
whisper ran on **4 threads** here against the desktop benchmark's 8. A phone
with 8 cores would take 8 under the shipped code — while ADR 0017's own text
says "threads respect the device — `min(cores, 4–6)` — with tuning left to
MS2". The two agree at 4 cores and diverge above it. **Recorded as a finding
for MS2 to settle; nothing was changed.** Moonshine's own threading is inside
`libmoonshine`/ONNX Runtime, is not configured by us, and was not measured.

## The clip

One clip, generated on the host and pushed by adb, used byte-identical by
every engine — the same trick M4b and R0 used.

- Windows `System.Speech`, voice **Microsoft Hazel Desktop** (en-GB), 16 kHz
  mono s16, rewritten with a canonical 44-byte header so the kernel's
  fixed-offset WAV reader and the app's `WavPcmDecoder` both accept it as a
  Gilune recording. **22.40 s**, 716,684 B.
- The vocabulary deliberately echoes the desktop benchmark's error classes —
  a date, two £ amounts, "ring main", "surveyor", "plasterer", "consumer
  unit", and "Gilune".

**Reference text**, as spoken:

> The surveyor is coming on Tuesday the fifteenth of September to look at the
> ring main. His quote was one thousand two hundred and forty pounds, which is
> fifty pounds more than the plasterer charged. Remind me to file the receipt
> in Gilune before Friday, and ask him whether the consumer unit needs
> replacing as well.

## Method

A throwaway instrumented test class, **deleted after the run** — this report is
the only artefact. One `@Test` per engine per run, and **one `am instrument`
invocation per run**, so every run got a fresh process and paid a real model
load. Three runs each; per-run figures and medians below.

**whisper went through the real kernel route**, so what is timed is what the
app does: the plugin's settings document set to `{language: "en", model: …}`
(an explicit language skips whisper's detection pass — the desktop benchmark's
discipline), then `enableWhisper` — **that call is the model load** — then the
clip captured through the ordinary streaming capture FFI in 64 KiB chunks,
then `runProcessing`, the kernel's own job loop, over the digest-verified blob.
Greedy sampling, as shipped.

**Moonshine went through R3's committed-transcript route**: `MoonshineDecoder`
opened first (timed separately, as `enableWhisper` is), then `runMoonshineJobs`
— claim, stream the blob chunk by chunk, decode, `submitOutput` — timed as the
decode. The vendor default 0.5 s interval, as `MoonshineDecoder` ships it.

**TTFT came from R0's own paced rig** (`R0.streamPcm`, 100 ms chunks fed at
real time, `transcription_interval` **0.25 s** — R1's setting), so the figure
is directly comparable with R0's 649 ms on its own clip. It is the R0 rig
rather than `MoonshineSession`, which adds a queue hop the caption pays and
this does not.

Models: whisper `tiny-q5_1` and `base-q5_1` seeded to
`/data/local/tmp/gilune-models` and copied to app-private storage;
Moonshine streaming-tiny from R0's seed at
`/data/local/tmp/gilune-r0/models`. No vault outside a fresh temp directory was
opened by anything.

## The numbers

Three runs each. Every figure is milliseconds of wall clock on the emulator.

| Engine | Run | Model load | Decode | RTF | Native heap after load |
| --- | --- | --- | --- | --- | --- |
| **whisper tiny-q5_1** | 1 | 91 | 4,205 | 0.188 | 44 MB |
| | 2 | 195 | 4,075 | 0.182 | 43 MB |
| | 3 | 262 | 4,502 | 0.201 | 43 MB |
| | **median** | **195** | **4,205** | **0.188** | **43 MB** |
| **whisper base-q5_1** | 1 | 81 | 8,090 | 0.361 | 70 MB |
| | 2 | 282 | 10,865 | 0.485 | 70 MB |
| | 3 | 589 | 8,339 | 0.372 | 70 MB |
| | **median** | **282** | **8,339** | **0.372** | **70 MB** |
| **Moonshine streaming-tiny** | 1 | 926 | 5,069 | 0.226 | 68 MB |
| | 2 | 393 | 4,915 | 0.219 | 68 MB |
| | 3 | 602 | 5,190 | 0.232 | 68 MB |
| | **median** | **602** | **5,069** | **0.226** | **68 MB** |

Decode excludes the load in every row. RTF is decode ÷ 22.395 s of audio.

**Time to first words**, which is a different question and the one ADR 0018
cares about:

| Engine | Config | TTFT | Lag behind the speaker | First text on screen |
| --- | --- | --- | --- | --- |
| **Moonshine streaming-tiny** | paced, interval 0.25 s | **637 / 636 / 646 ms** (median **637**) | 137 ms | `"10."` |
| whisper tiny-q5_1 | — | **4,205 ms** (the whole decode) | — | nothing until finished |
| whisper base-q5_1 | — | **8,339 ms** (the whole decode) | — | nothing until finished |

**whisper's structural equivalent of TTFT is its entire decode**, because it
emits nothing until it is done. That is not a tuning difference; it is the
shape of the two engines, and it is the whole reason ADR 0018 exists. Moonshine
put words on screen **6.6× sooner than whisper tiny finished** and **13×
sooner than whisper base**, on this machine.

Two details worth keeping:

- **R0's `"10."` reproduced exactly.** At 0.25 s the first thing a watcher sees
  is a token with no relation to the speech, revised away moments later. R0
  saw the same artefact on a different clip. It is the revisable tail ADR 0018
  § 1 designs for, and the owner should expect to see it.
- **The paced caption cost more CPU than the file decode**: 10,138 ms median of
  decode wall for the same 22.4 s (rtf 0.453) against 5,069 ms through the
  committed route. **Confounded** — the paced run changes both the interval
  (0.25 s vs 0.5 s) and the feed rate (100 ms real-time chunks vs 64 KiB
  flat out) — so it is not a clean "the caption costs double" figure. It is a
  reason to measure caption cost deliberately on hardware, given ADR 0018 § 4's
  "two engines resident during a recording" concern.

### What these numbers do not mean

- **whisper's RTF is an artefact of the clip length.** whisper.cpp processes
  audio in **30-second windows**, so a 22.4 s clip and a 5 s clip cost roughly
  the same. MB2 measured 2 s of silence at 5.34–5.57 s with tiny-q5_1 —
  *slower* than this 22.4 s clip — which is the same effect from the other
  side. **whisper's RTF here would look far worse on a short capture and
  better on a 29 s one; Moonshine's is genuinely linear in audio length.**
  Comparing the two RTFs directly is therefore misleading, and the raw decode
  seconds are the honest column. Not separately measured in this pass.
- **Run-to-run variance is large and host-driven.** Beyond the three runs
  above, extra samples of the same configurations landed at 3,126 / 4,012 ms
  (tiny), 6,600 ms (base) and 4,017 ms (Moonshine) — a spread of roughly 40%
  on tiny and base. R0 recorded ~20% on the same rig. Single figures mean
  little; the medians are reported for that reason and even they carry it.
- **Cold model load is not measurable on an emulator.** A pass with the guest's
  page cache dropped (`echo 3 > /proc/sys/vm/drop_caches`, adb root) came back
  *faster*, not slower, because the emulator's backing store is a host file
  already in the **host's** page cache, which the guest cannot evict. So every
  load figure above is warm, and the load column is noise-dominated
  (81–589 ms for one model). R0's cold figure of **1,158 ms** for Moonshine,
  taken on a cold process and a cold page cache, remains the better cold
  number. First-recording-after-boot cost is a hardware measurement.

## Accuracy

Transcripts were **byte-identical across all three runs** of each
configuration — both engines are deterministic here — so one row each.

| Engine | Transcript |
| --- | --- |
| **whisper tiny-q5_1** | "The surveyor is coming on Tuesday 15 September to look at the ring main. His quote was £1240, which is £50 more than **the plaster had** charged. Remind me to file the receipt in **Jail Yoon** before Friday and ask him whether the consumer unit needs replacing as well." |
| **whisper base-q5_1** | "**The severe** is coming on Tuesday 15 September to look at the ring main. His quote was £1240, which is £50 more than the plasterer charged. Remind me to file the receipt in **Gileon** before Friday, and ask him whether the consumer unit needs replacing as well." |
| **Moonshine streaming-tiny** (committed, 0.5 s) | "**The survey** is coming on Tuesday the 15th of September to look at the ring main. His quote was 1,240 pounds, which is 50 pounds more than **the plaster are** charged. Remind me to file the receipt in **jail you in** before Friday, and ask him whether the consumer unit needs replacing as well." |
| Moonshine streaming-tiny (caption, 0.25 s) | "**The severity** is coming on Tuesday the 15th of September … " — otherwise identical to the row above. |

**Substantive word errors**, counting content words wrong and *not* counting
numeral or currency formatting:

| Engine | Errors | Which |
| --- | --- | --- |
| **whisper tiny-q5_1** | **2** | plasterer → "plaster had"; Gilune → "Jail Yoon" |
| **whisper base-q5_1** | **2** | surveyor → "severe"; Gilune → "Gileon" |
| **Moonshine streaming-tiny** | **3** | surveyor → "survey"; plasterer → "plaster are"; Gilune → "jail you in" |

**The verdict on this one clip: whisper edges Moonshine, and the two whisper
sizes trade errors rather than one dominating.** base-q5_1 fixes "plasterer"
and breaks "surveyor" — **replicating the desktop benchmark's finding of
2026-09-01 exactly** ("base-class fixes ring-main/£/plasterer-class errors but
regressed 'surveyor' on this clip"). That the same trade reproduced on a
different clip, a different machine and a different ABI is the most transferable
accuracy result here.

Three further observations:

- **"Gilune" defeats every model**, again, and Moonshine's "jail you in" is the
  worst rendering yet — R0 got "Jai Yun". R0's proposed remedy,
  `Transcriber.setKeyterms(List)`, is **still unexercised**, and whisper has no
  counterpart configured. This is the one accuracy defect the product can
  plausibly fix itself.
- **Numeral and currency rendering differs and neither is wrong.** whisper
  writes "£1240" and "15 September"; Moonshine writes "1,240 pounds" and "the
  15th of September". whisper's is closer to how a person writes a note;
  Moonshine's is closer to what was said. A style difference to be aware of
  when comparing transcripts, not an error.
- **The caption's words are not the committed words.** The 0.25 s caption run
  and the 0.5 s committed run disagreed on the first word ("severity" vs
  "survey") from identical audio. ADR 0018 § 1's rule that the caption is
  cleared on stop and never promoted is doing real work, not guarding a
  hypothetical.

## Memory

Native heap, `Debug.getNativeHeapAllocatedSize()`, on a fresh process per run.

| Engine | Before load | After load | **Delta** |
| --- | --- | --- | --- |
| whisper tiny-q5_1 | 5 MB | 43 MB | **≈ 38 MB** |
| whisper base-q5_1 | 5 MB | 70 MB | **≈ 65 MB** |
| Moonshine streaming-tiny | 5 MB | 68 MB | **≈ 63 MB** |

Two consequences worth carrying to MS2:

- **ADR 0018 § 4's "two engines resident during a recording" now has a
  number on this machine: ≈ 100 MB of native heap** (whisper tiny-q5_1 plus
  Moonshine), or ≈ 128 MB if the phone were carrying base-q5_1. That is the
  figure the hybrid's cost should be argued against — re-measured on hardware.
- **Moonshine's resident cost is transient by construction.** Heap fell back
  to 15 MB after the committed pass, because `runMoonshineJobs` closes the
  decoder when the pass ends — "between recordings the phone carries no
  resident model". whisper's stayed at 43/70 MB, because `enableWhisper` holds
  the model on the handle for the life of the process. **Different residency
  models, not different appetites**, and it matters more for battery than the
  peak does.

R0 measured ≈ 57 MB for a loaded Moonshine session against ≈ 63 MB here; the
difference is within the noise of a different process and a different baseline.

## What changes on hardware — and in which direction, nobody knows

This is the point of MS2 and the reason nothing above is a conclusion.

- **whisper.cpp on ARM64 takes a different code path**: NEON, and on some
  builds Accelerate-class kernels, against the x86_64 AVX path used here.
  Quantised `q5_1` arithmetic in particular is a different implementation.
- **Moonshine's ONNX Runtime likewise has ARM-specific kernels**, and the
  reduced build Moonshine ships (R0: 6.29 MB, not Microsoft's 30.6 MiB) may
  include or exclude them differently per ABI. **The `.ort` graph is the same;
  what executes it is not.**
- **big.LITTLE scheduling has no analogue here.** Four uniform vCPUs on a
  desktop Ryzen is not four little cores plus four big ones, and a background
  job is exactly the workload a phone scheduler is most likely to park on the
  little cluster. `min(cores, 8)` on an 8-core phone may mean eight threads
  across two very different core types.
- **Thermal throttling does not exist on an emulator.** A 22 s clip will not
  throttle a phone; a backlog catch-up over a neglected vault might, and so
  might a caption running for the length of a real recording. Unknown.
- **Memory pressure is different.** 2.4 GiB with nothing else running is not a
  phone with a browser and a messaging app behind it, and a 65 MB native
  allocation is a different proposition under real pressure.

**The two engines could reorder on hardware.** whisper's advantage here is
partly the 30-second-window effect on a 22 s clip; Moonshine's streaming
architecture is designed for exactly the incremental case an emulator flatters
least. Nothing in this preview constrains what MS2 will find.

## Honest gaps

- **No ARM64, no battery, no thermals** — the whole of MS2, untouched.
- **One clip, one voice, one accent, TTS.** No human speech, no noise, no
  crosstalk, no long recording, no short one. A clip set is MS2's.
- **Three runs, ~40% variance.** The medians are thin evidence and are
  presented as such.
- **whisper's 30-second-window effect was reasoned about, not measured.** No
  clip-length sweep was run; the MB2 silence figure is the only cross-check.
- **Cold-cache model load is unmeasurable here** (see above), so the load
  column characterises nothing but warm re-loads.
- **Moonshine's thread count was neither read nor controlled**, so the two
  engines were not equalised on CPU. whisper's 4 threads are known; Moonshine's
  are not.
- **`setKeyterms` still unexercised**, on the fourth occasion the product's own
  name has come back wrong.
- **`MoonshineSession` — the caption path the app actually ships — was not the
  TTFT instrument.** R0's rig was, for comparability. The session's bounded
  queue and audio-dropping behaviour under load is not characterised.
- **Only streaming-tiny and only two whisper sizes.** Moonshine's legacy
  non-streaming fallback (ADR 0018 Alternative (a)) was not touched, and
  whisper `tiny`/`base` unquantised were not run.
- **Nothing here exercised the widget's `RecordService` path**, real
  microphone input, or a recording longer than 22 seconds.

## What this preview suggests, and what it decides

It **decides nothing**. ADR 0018 § 6 reserves the packaged mobile default for
MS2, and MS2 is a hardware pass.

What it puts on the record for MS2 to test on a phone:

1. **Both engines run the app's real routes on the same audio and produce a
   correct note.** Neither is a build risk.
2. **Realtime is not a marginal gain.** Words on screen at ~640 ms against a
   first word at 4.2 s (tiny) or 8.3 s (base) is the difference ADR 0018 was
   written about, and it survived contact with a second clip.
3. **whisper base-q5_1 is not obviously beyond a phone** — 8.3 s for a 22 s
   clip on four emulated cores — but it costs ~2× tiny's decode and ~1.7× its
   memory for an accuracy *trade*, not an accuracy win. The pencilled phone
   default of tiny-q5_1 survives this preview unchallenged.
4. **On accuracy, whisper edges Moonshine on clean speech** by one substantive
   error in fifty-six words. Small, single-clip, and not where the realtime
   argument is won or lost.
5. **The residency asymmetry** — Moonshine loaded per pass, whisper held per
   process — is a battery question this rig cannot ask.
