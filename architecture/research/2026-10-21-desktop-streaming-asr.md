# Desktop streaming speech recognition — measured 2026-10-21

Evidence for ADR 0027. Throwaway harnesses lived in `C:\gw` and are not
kept; the method is recorded so the numbers can be reproduced.

## Machine and method

- **Machine:** Intel Core Ultra X7 358H laptop, 16 logical cores, Windows 11,
  ~15% background load. **Not** the Ryzen 3900X the earlier whisper benchmark
  (2026-09-01) ran on, so absolute times are not comparable with it.
- **Clip:** Windows SAPI text-to-speech, 16 kHz mono s16, 22.9 s: "Met the
  surveyor on site this morning. The ring main in the kitchen needs replacing
  before the plasterer starts on Thursday. The quote came in at four thousand
  two hundred pounds, which is about ten percent over budget. Ask Marcus to
  chase the electrician, and remind me to send the drawings to Simon by
  Friday." (55 words). Synthetic speech with almost no pauses — a hard case
  for engines that settle at pauses, an easy one acoustically.
- **WER** is word-level against the text above after lower-casing and
  stripping punctuation. Engines that write "£4,200" and "10%" pay **7**
  errors for formatting alone (0.127); the column "real errors" removes that.
- **Streaming simulation:** audio arrives in 100 ms chunks at real time; a
  call that takes longer than the audio it covers delays the next one, as a
  live loop would. *First words* = when the first non-empty hypothesis is
  available. *Revised* = previously shown words later changed. *RTF* =
  compute ÷ audio; above 1.0 the engine falls behind the speaker.
- **Whisper:** whisper-rs 0.16 (the kernel's), greedy, 8 threads, language
  set — the kernel's parameters. Streaming = re-decode the buffer every 1 s,
  commit the longest prefix two consecutive hypotheses agree on
  (LocalAgreement-2, the whisper_streaming policy).
- **sherpa-onnx 1.13.8** through its **JVM API** (`sherpa-onnx-jvm` +
  `sherpa-onnx-native-lib-win-x64` jars) — the route a desktop provider would
  take — 4 threads, greedy, endpointing on.
- **Moonshine Voice 0.1.5** Windows x64 static library through its C API.

## Results

### Whole-clip decode (today's path: transcribe after stop)

| Engine | Model on disk | Decode 22.9 s | Real errors | Notes |
|---|---|---|---|---|
| whisper base-q5_1 (shipped default) | 57 MiB | 1.05 s | 1 ("savela") | punctuation, £, % |
| whisper small-q5_1 | 181 MiB | 3.46 s | 2 | |
| whisper medium-q5_0 | 514 MiB | 13.0 s | 0 | |
| whisper large-v3-turbo-q5_0 | 547 MiB | 20.7 s | 0 | RTF 0.9 on this CPU |
| Parakeet TDT 0.6B v2 int8 (sherpa) | ~460 MiB | 1.03 s | 1 ("savella") | punctuation, £, % |

### Streaming

| Engine | First words | Keeps up? (RTF) | Revised words | Real errors | Size | Licence |
|---|---|---|---|---|---|---|
| whisper base-q5_1, LocalAgreement, 1 s step | 1.2–1.8 s (settled 2.2–2.9 s) | barely: each re-decode 0.7–1.25 s, CPU busy throughout | 17–28 | 1 | 57 MiB | MIT |
| whisper small-q5_1, same | 5–8 s | **no** (4.3 s per re-decode) | 22–35 | 2 | 181 MiB | MIT |
| whisper base, naive 10 s window | 2.2 s | yes | 167 | 26 (words cut at window edges) | — | — |
| **Nemotron speech streaming 0.6B int8, 160 ms (sherpa)** | **1.16 s** | **yes, RTF 0.46–0.52** (worst chunk 105–205 ms) | 8 | **2** ("let", "plaster") — the only engine below medium to get "surveyor" | 631 MB on disk; peak working set 862 MB *including JVM and the harness's in-process compile* | NVIDIA Open Model License (commercial use allowed; not OSI) |
| Parakeet unified 0.6B int8, 560 ms (sherpa) | 2.1 s | **no** (RTF 2.6) | 11 | 4 | ~480 MiB | NVIDIA Open Model License |
| NeMo FastConformer transducer 480 ms int8 (sherpa) | 1.32 s | yes, RTF 0.05 | 5 | 9; no casing or punctuation | ~100 MiB | CC-BY-4.0 |
| Zipformer streaming en 2023-06-26 (sherpa) | 0.82 s | yes, RTF 0.07 | 19 | 9; no casing or punctuation | ~70 MiB int8 | Apache-2.0 |
| Moonshine tiny streaming (native, decode in-progress lines) | 0.71–0.97 s | **no** (RTF 1.04–1.18 on this build; 1.6 without speculative decoding) | 44–54 | ~4 | 45 MB | MIT |
| Moonshine tiny streaming, decode only at line end | 3.05 s | yes, RTF 0.45 | 0 (nothing shown until a phrase ends) | ~4 | 45 MB | MIT |
| Moonshine small streaming, decode only at line end | 4.97 s | **no** (RTF 1.4) | 0 | 1 | 136 MB | MIT |

## Readings

- **Bigger whisper is not a streaming option on CPU.** medium and turbo are
  accurate but take 13–21 s to decode 23 s; a sliding window re-decodes the
  same audio many times over, so they could only ever be a *final pass*.
- **Whisper base with LocalAgreement works, just.** First words in ~1.5 s,
  settled in ~2.5 s, with no new dependency. It keeps a core group busy for
  the whole dictation and is no more accurate than today.
- **Nemotron streaming is the only engine measured that is both live and
  more accurate than what ships.** It is a true cache-aware streaming model:
  each 160 ms chunk costs ~50–100 ms, nothing is re-decoded, and revisions are
  few (8 words over the clip). The price is size (~460 MiB download, ~0.6 GB
  resident) and a licence that is permissive but not open source.
- **Moonshine on Windows did not keep up on this machine** when decoding the
  line still being spoken — the mode that gives provisional words. The same
  engine is real-time on Android (R0), so this may be the prebuilt Windows
  library rather than the model; it would need a profiling spike before
  anyone relied on it. It does offer `keyterms` biasing (names, "Gilune"),
  which nothing else measured has.
- **Parakeet TDT v2** decodes the whole clip as fast as whisper base with
  similar accuracy; not worth a switch on its own.

## Honest gaps

- One synthetic clip, one voice, no noise, almost no pauses. Real dictation
  pauses, which favours endpoint-settling engines (Nemotron, Moonshine).
- Memory for sherpa was measured with the JVM and the harness's own compile
  in the same process; the engine's own resident size is not isolated.
- Casing and punctuation of the sherpa models were not checked (the harness
  normalises them away); the survey says Nemotron emits both.
- No run under the owner's real microphone or accent.
- Moonshine's Windows slowness is unexplained.
