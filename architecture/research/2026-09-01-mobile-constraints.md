# Mobile design — internal constraints

Read-only research, 2026-09-01. Repo `D:\CuttingCode\SneddoBuilds\gilune`.
Paths are repo-relative. Everything below is evidence from the tree or the
ADRs; where I reason rather than cite, it says so.

**The one-line summary.** Mobile inherits far more than the ADRs imply —
`androidMain` already compiles `src/jvmShared/kotlin`, so the whole shared
inventory is *architecturally* mobile code today. But it inherits nothing
**executable**: the Android target has not compiled since M6a, the vault is
in app-private storage (breaking the Obsidian-openable promise on phone),
and the desktop theme is not in the shared set. The design is freer than
expected on the editor and more constrained than expected on storage.

---

## 1. What mobile inherits free

`app/composeApp/build.gradle.kts:40` — `androidMain` does
`kotlin.srcDir("src/jvmShared/kotlin")`. **Every file below is already on
the Android compile path.** Nothing needs promoting to be shared; the
question per file is only whether its APIs exist on Android.

Two facts settle most of it:

- `build.gradle.kts:71` — **`minSdk = 29`**. `java.time` is native from
  API 26, so `LocalDate`/`DayOfWeek` need **no core-library desugaring**.
  The date machines are safe as written.
- Every Compose import in `jvmShared` is `androidx.compose.ui.*`,
  `foundation.*` or `material3.*` — the multiplatform surface. No
  `compose.desktop`, no `awt`, no `swing` anywhere in `jvmShared`
  (verified by grep).

| File | LOC | Non-Compose APIs | Android verdict |
|---|---|---|---|
| `GiluneState.kt` | 231 | kotlinx.coroutines, uniffi | Safe. `Dispatchers.IO` fine. |
| `MarkdownEditing.kt` | 544 | none | **Safe — see below.** |
| `Markdown.kt` | 202 | `org.intellij.markdown` | Safe *if the dep resolves* — see Traps. |
| `MarkdownView.kt` | 179 | none | Safe. Pure foundation/material3. |
| `CaptureBar.kt` | 198 | none | Safe. Already the Android screen. |
| `Search.kt` | 453 | `java.io.File`, `LocalDate` | Safe. `File.walkTopDown` over a readable dir. |
| `TaskSearch.kt` | 286 | `LocalDate`, `Locale` | Safe. Compose-free. |
| `TaskDates.kt` | 146 | `LocalDate`, `DayOfWeek`, `Locale` | Safe. Compose-free, pure. |
| `Suggest.kt` | 378 | `LocalDate`, `Locale` | Safe. Only `TextRange`/`TextFieldValue` from Compose. |
| `FilterBlock.kt` | 235 | `LocalDate` | Safe. Compose-free. |
| `Plugins.kt` | 454 | `LocalDate`, uniffi | Safe *once bindings regenerate* — see §2. |
| `Editing.kt` | 84 | `java.security.MessageDigest` | Safe. |
| `Audio.kt` | 44 | `java.nio.ByteBuffer` | Safe. |

### The two questions asked explicitly

**Can `MarkdownEditing`'s `VisualTransformation` + decorations run in
`androidMain` as-is? — Yes, by API.** `MarkdownEditing.kt:20-30` imports
only `androidx.compose.ui.graphics.Color`, `.text.*`, `.text.input.{OffsetMapping,
TransformedText, VisualTransformation}`, `.text.style.TextDecoration`,
`.unit.sp`. All multiplatform; all present on Android. `MarkdownEditTransformation`
(`:111`), `activateLine` (`:142`), `decorationAt` (`:162`), `caretLine` (`:184`),
`toggleCheckbox` (`:208`) and the whole `EditorDecoration` seam (`:76`) are
Android-compatible source **today**.

The caveat is behavioural, not API (reasoned, unmeasured): `VisualTransformation`
is the *legacy* `BasicTextField` API taking `TextFieldValue`, which is what
`EditorState.text` holds (`NoteEditor.kt:131`). On Android that field is driven
by an IME with composing regions and autocorrect, which round-trips offsets
through `OffsetMapping` in ways desktop never exercises. `DecorationOffsetMapping`
(`MarkdownEditing.kt:389`) snaps offsets inside a replaced range to the nearer
edge — correct and monotone, but a Gboard composing span straddling a
`[ ]`→`☐` replacement is an untested interaction. **This is the single
highest-risk inherited component and deserves a spike before a design promises
live-preview editing on phone.**

**Can `Search`/`FilterBlock`/`TaskDates`/`Suggest`? — Yes, all four,
unconditionally.** `TaskDates`, `TaskSearch`, `Search` and `FilterBlock` are
Compose-free pure machines; `Suggest` touches only `TextRange`/`TextFieldValue`.
These are the "pure machines" ADR 0015 § Mobile promised, and the promise holds
in the code.

`Theme.kt` is **not** in this list — see §4.

---

## 2. What the Android app is today

**Two Kotlin files. That is the whole app.**

- `src/androidMain/kotlin/org/gilune/app/MainActivity.kt` (59 lines) —
  requests `RECORD_AUDIO` (`:29-33`), computes the vault dir (`:41`),
  then renders **stock `MaterialTheme`** (`:43`) wrapping exactly two
  composables: `CaptureBar` (`:49`) and `RecentList` (`:53`).
- `src/androidMain/kotlin/org/gilune/app/AndroidAudioRecorder.kt` (60 lines)
  — `AudioRecord`, 16 kHz mono 16-bit PCM, matching desktop.
- `src/androidMain/AndroidManifest.xml` — **one permission**
  (`RECORD_AUDIO`, `:3`), one activity, and
  `android:theme="@android:style/Theme.Material.Light.NoActionBar"` (`:6`)
  — a hard-coded light theme, so dark mode is broken on Android today.

So the Android app is: **a text box, a mic button, and a flat list of recent
captures.** No tree, no editor, no settings, no search. It is the M3-era
shell — `git log` shows `androidMain` last touched at `63f237e` (2026-08-31,
M6 first pass) and untouched through the M6 second, third and fourth passes.

### Bindings are STALE — and androidMain does not compile

Confirmed concretely. Both generated files are gitignored (`app/.gitignore:6-10`),
so mtime and content are the evidence:

| | Android | Desktop |
|---|---|---|
| size | 95,287 B | 120,240 B |
| mtime | 2026-08-31 00:00 | 2026-08-31 19:31 |
| exported FFI fns | **21** | **33** |

The Android set is a strict subset — staleness, not platform divergence.
**The 12 missing methods:** `backfill_jobs`, `begin_attached_capture`,
`grant_capability`, `is_granted`, `object_text`, `plugins`, `register_plugin`,
`reinstall_plugin`, `remove_plugin`, `revoke_capability`, `set_plugin_enabled`,
`set_plugin_settings`. **Three missing types:** `PluginInfo`,
`PluginDeclaration`, `PluginContribution`.

Because `build.gradle.kts:40` puts `jvmShared` on the Android source set,
this makes `androidMain` **genuinely uncompilable**:
`Plugins.kt:8-10` imports all three missing types; `GiluneState.kt:42-45,53`
calls `plugins()`, `registerPlugin()`, `grantCapability()`;
`GiluneState.kt:172,175,219` call `beginAttachedCapture`, `objectText`,
`backfillJobs`; `FilterBlock.kt:18` and `TaskSearch.kt:17` import `PluginInfo`.

### What regenerating actually needs

There is **no script**. No `cargo-ndk` anywhere in the repo; no
`.cargo/config.toml` Android linkers; no `ndkVersion` or `abiFilters` in
`build.gradle.kts`; **zero Android references anywhere in `kernel/`** —
no `[target.'cfg(target_os = "android")']`, nothing. `app/local.properties:1`
sets `sdk.dir` only, no `ndk.dir`.

The only documented incantation is the **S1 spike's**, for the spike crate
(`spikes/s1-kernel-boundary/README.md:44-60`), which must be adapted to
`gilune-ffi`:

```sh
cd kernel
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=$NDK/bin/x86_64-linux-android29-clang.cmd
export CC_x86_64_linux_android=$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER
export AR_x86_64_linux_android=$NDK/bin/llvm-ar.exe
cargo build --release --target x86_64-linux-android --lib
cp target/x86_64-linux-android/release/lib<name>.so ../app/composeApp/src/androidMain/jniLibs/x86_64/
cargo run -p gilune-ffi --bin uniffi-bindgen -- generate \
  --library target/x86_64-linux-android/release/lib<name>.so \
  --language kotlin --out-dir ../app/composeApp/src/androidMain/generated
```

`$NDK` is undefined by the doc. Note `--library <the .so>`: **bindings must
come from the Android binary.** S1 proved UniFFI API checksums are
target-dependent — desktop-generated bindings throw "API checksum mismatch"
against an Android `.so` built from identical source
(`spikes/s1-kernel-boundary/README.md:130-137`, contract at
`architecture/07-compatibility.md:44-45`). This is why the stale file cannot
simply be copied from desktop.

Machine notes (`CLAUDE.md`): stale `ANDROID_HOME=C:\Android\android-sdk` must be
overridden to `%LOCALAPPDATA%\Android\Sdk` per process; the old
`C:\Android\adb.exe` fights the SDK's adb server — kill both, start the SDK's;
emulator AVD `gilune-s1` exists.

**Budget the design should assume: half a day to a day of toolchain work
before a single line of new Android UI can be compiled.** That is a
scheduling fact, not a design constraint, but it belongs in any plan.

### Did the kernel run on Android? Yes.

At M3, `androidInstrumentedTest` ran the shared boundary suite
(`build.gradle.kts:53-59` wires `src/jvmSharedTest/kotlin` into it). That suite
(`jvmSharedTest/.../BoundaryTest.kt`) covers: durable text capture + projection,
streamed chunk-by-chunk capture, external edit → kernel history, deleted
projection → tombstone proposal, and a newer-vault refusal. **So SQLite, the
vault projection and reconciliation all executed on Android** — on an x86_64
emulator, at M3, and not since.

S1 additionally proved on Android (emulator, API 36.1 google_apis x86_64):
8/8 instrumented tests including a WorkManager background worker reopening
the kernel; activity recreation → reopen 11 ms; process death + relaunch →
1.4 s cold; fresh-install first open 859 ms; `aarch64-linux-android` release
`.so` 2.96 MiB; dual-ABI debug APK 31.08 MiB.

### Where the vault lives — and the Obsidian tension

`MainActivity.kt:41`:

```kotlin
val vaultDir = filesDir.resolve("vault").absolutePath
```

`MainActivity.kt:1-3` is the *only* prose on this anywhere: "The vault lives
in the app's files dir for v0".

**This is app-private internal storage** — `/data/data/org.gilune.app/files/vault`.
Unreadable by Obsidian or any other app, invisible in a file manager, and
**wiped on uninstall**.

Repo-wide there are **zero hits** for `getExternalFilesDir`,
`MANAGE_EXTERNAL_STORAGE`, `ACTION_OPEN_DOCUMENT_TREE`, `DocumentFile`,
`takePersistableUriPermission`, `contentResolver`, "scoped storage" or "SAF".
The manifest declares no storage permission of any kind.

**No architecture document addresses this.** `02-kernel-contract.md` and
`03-data-model.md` say nothing about device vault location (03 constrains only
that rel-paths are `/`-separated, `:228-232`). ADR 0007 defines the vault's
*shape* — "openable by third-party tools" (`:40-42`), `.gilune/` inside it
(`:44-46`) — never its device path. ADR 0011:84 assigns "vault path" to
device-local preferences, but that is the desktop ADR.

**So this is squarely a design decision, and a load-bearing one.** The product
promise (`CLAUDE.md`: "an Obsidian-openable Markdown vault as the *user-visible*
store"; `architecture/foundation-review.md:67` gates on it) is **not met on
Android today**. The options a design may claim, with their costs:

1. **Keep `filesDir`.** Zero work, honest for v0, but the phone vault is a
   private silo — no Obsidian, no file manager, lost on uninstall. Then
   M5 LAN sync is not a convenience, it is *the only way data leaves the phone*.
2. **`getExternalFilesDir()`.** Still app-scoped, no permission needed,
   visible over MTP and to a file manager, **still wiped on uninstall**.
   Obsidian cannot open it on Android 11+.
3. **SAF / `ACTION_OPEN_DOCUMENT_TREE`.** The real "user picks a folder"
   answer and the desktop parity. But the kernel opens the vault by
   **absolute path** (`Gilune.open(vaultDir)`, `GiluneState.kt:34`) and Rust
   does `File` I/O over it; SAF gives a `content://` URI, not a path.
   Bridging means either an fd-passing layer or `MANAGE_EXTERNAL_STORAGE`.
   **This is a kernel-boundary change, not a shell change** — flag it as such;
   it is nowhere in any milestone.
4. **`MANAGE_EXTERNAL_STORAGE`.** Gives a real path, works with Obsidian,
   and is a Play Store policy fight (`spikes/s5-distribution-policy/`
   is the place that reasoning belongs).

A design that assumes the phone vault is user-visible is assuming work
nobody has scoped.

---

## 3. Hard constraints from the ADRs

**ADR 0006 — declarative UI for third-party plugins.** "Third-party UI
contributions use shell-rendered declarative slots rather than arbitrary
native, Compose, WebView, or component-rendered widgets" (`:30`).
"First-party plugins use the same slots when sufficient" (`:31`). Mobile-store
builds execute **only bundled** plugins (`:22`); ADR 0010 sets the post-v0
direction but "this decision's bundled-only stance remains v0" (`:35-38`).
"The shell owns accessibility, themes, navigation, permission prompts, and
safe rendering" (`:73-74`).
→ *A mobile design may not invent a plugin-drawn widget. Every plugin-owned
surface on phone must be a slot the shell renders.* The good news: the slot
machinery is already shared (`Plugins.kt:92-106`).

**ADR 0011 — the capture bar STAYS on Android.** The 2026-08-31 amendment
(`:135-143`) drops the desktop capture bar and replaces it with Ctrl+N plus a
top-bar mic — and states explicitly: "**The capture bar remains the Android
surface unchanged.**" Also `:126-128`: "The capture panel is shared with
Android, so the microphone becomes a contribution there too."
→ *Desktop's capture gesture is a keyboard gesture. Mobile keeps the region.
A design that mirrors the desktop top-bar-mic shape onto phone contradicts a
ratified amendment.* The mic is plugin-gated in shared code already —
`CaptureBar.kt:66-67` draws it only when
`state.plugins.contributes(Slots.CAPTURE_ACTIONS, RECORD_VOICE_ACTION)`.

**ADR 0014 — no tabs, no panes, and a named promotion path.** § Mobile
(`:142-146`): "Not applicable — one note, no tabs, no panes. `EditorState`,
`NoteEditor` and the pane tree stay in the desktop source set; **the shared
set must not learn what a tab is.** If a mobile reader ever needs
`EditorState`, **it is promoted alone.**"
→ *This is both a prohibition and an invitation. The design may claim
`EditorState` promotion by name; it may not claim tabs, splits, or anything
that makes `jvmShared` tab-aware.*

**ADR 0015 — suggest is desktop-only today, by placement not by nature.**
§ Mobile (`:423-430`): "Recognisers, token format, date resolution and query
evaluation are pure and live in `jvmShared`, so a mobile reader styles the
same tokens and could render the same block from the same code. The popup,
the rail panel, the search panel and the Edit-mode overlay are desktop-only,
since `NoteEditor`, `EditorState` and the rail sit in the desktop source set.
**Mobile v0 therefore reads and toggles task lines; inline suggest waits for
a mobile editor.**"
→ *Note the reason: the popup is desktop-only because of **where the files
sit**, not because of an API. See §4 — `SuggestPopup.kt` is 100% portable.*

**ADR 0013 + the whisper gate — no transcription on the phone, and today no
path to any.** `kernel/crates/gilune-ffi/Cargo.toml:27-30` gates whisper as an
optional cargo feature; `lib.rs:326-330` documents "Desktop builds only …
Android v0 captures and lets a paired coordinator transcribe";
`lib.rs:361-367` returns `"this build has no local transcription"` otherwise.

**The honest consequence for the mobile flow, which no document states
plainly:**

- A voice capture on Android commits durably and **stays audio**. It projects
  a note with a `gilune-blob` link and an empty/preview body.
- `GiluneState.captureAudio` (`:137-160`) calls `runProcessing()` and reports
  `"spoken note ready"` on outputs — on Android that is always 0 outputs, so
  the message falls through to `"voice captured"`. Not wrong, but the shared
  code is written for the desktop path.
- **The "paired coordinator" does not exist yet.** M5 LAN sync is unbuilt
  (`CLAUDE.md`: "M5 remains the last milestone of the vertical slice").
  So today a phone recording is a permanently untranscribed audio blob unless
  the vault is copied to a desktop by hand.
- **Therefore dictation-at-the-cursor (ADR 0013) cannot work on mobile at
  all** until M5 lands. `GiluneState.dictate` (`:167-189`) inserts
  `objectText(receipt.sourceId)` at the caret; on Android that is null, and
  the user gets `"dictation saved; no words recognised"`. A mobile editor
  design may include a dictate affordance, but must either hide it or state
  honestly that words arrive later, after a sync with a desktop.
- Secondary: the transcription plugin is registered by the kernel only under
  `#[cfg(feature = "whisper")]` (`lib.rs:294-308`), so a mobile settings
  screen shows a **shorter plugin list** than desktop. Not a bug — but a
  settings design must not hard-code the desktop set.
- Fragility worth flagging: the exclusion is by **cargo feature, not
  `cfg(target_os = "android")`**. Nothing in the build system prevents an
  Android build with `--features whisper`; there is no build script at all
  (§2), so the exclusion is convention.

**ADR 0001 — the device budgets are ASSUMED, not measured.** Status
(`:3-11`): accepted for v0 "provisional"; the physical-device items —
"capture-commit and cold-start budgets on mid-range hardware, and first ARM64
execution — are **assumed, not measured**". The numbers
(`architecture/spikes/README.md:30-38`):

- **capture commit: < 200 ms** on a mid-range Android device
- **cold start to capture-ready: < 1.5 s** on the same device
- kernel contribution to package size: < 15 MB per ABI (release `.so` is
  2.96 MiB arm64 — clears it ~5×)
- LAN sync of 100 small operations between two idle devices: < 5 s

Emulator figures exist but S1 states they are explicitly **not budget
evidence** (`:138-142`): cold-open 114 ms, first call 25 ms, put-record 15 ms,
FFI streaming ~10 MiB/s, **debug cold app start ~13 s**.
→ *The 1.5 s cold-start budget is the sharpest constraint on a mobile design.
Desktop measures ~1.4 s and is JVM+Compose bound (`CLAUDE.md`). Any mobile
design that puts work on the path to first frame — vault scan, search index,
plugin enumeration, a rendered tree — is spending a budget that has never been
measured and is already nearly exhausted by the framework. **Design the first
frame to be the capture surface, with everything else lazy.***

---

## 4. The open seams a design may claim

**1. `EditorState` promotion — explicitly sanctioned (ADR 0014:145-146).**
It is **65 lines**, `NoteEditor.kt:126-190`. Dependencies: Compose runtime,
`java.io.File` (Android-safe), `assembleNote`/`splitNoteRaw`/`contentHash`
(all in shared `Editing.kt`), and **one desktop coupling** —
`var reading by mutableStateOf(Prefs.readingMode)` (`:139`). `Prefs` is
`java.util.prefs.Preferences` (`Prefs.kt:8,13`), which is not usable on
Android (see Traps). **Promotion cost: move 65 lines, parameterise one
boolean default.** `insertAtCaret` (`:195-207`) is another 13 pure lines and
comes along free. This is the cheapest, best-sanctioned move available to the
design.

**2. `Theme.kt` is desktopMain — and this is where "inherit the desktop
look-and-feel" gets interesting.** `desktopMain/.../Theme.kt` (83 lines) is
pure `material3` + `compose.ui` — **fully portable, promotable as-is**.
Android currently renders stock `MaterialTheme` (`MainActivity.kt:43`), i.e.
the purple the owner rejected on desktop.

But read the file's own opening comment (`Theme.kt:1-5`): *"Material's
defaults are tuned for a phone: bigger type, taller rows, and a purple accent
that reads as 'stock Android' on a Windows desktop."* The `Compact` typography
(`:65-74`) drops body text to **13–14 sp** and label text to 13 sp —
deliberately desktop density.
→ *The design should inherit the **palette** (`Light`/`Dark`, `:19-63`) and
**reject the density**. Splitting `GiluneTheme` into a shared colour scheme
plus a per-platform `Typography` is the honest form of "desktop look-and-feel
inherited". Shipping `Compact` on a phone would be a legibility and
touch-target regression.* Also note the manifest hard-codes a light Android
theme (`AndroidManifest.xml:6`), so dark mode needs that changed too.

**3. `SuggestPopup.kt` is already portable — ADR 0015's "desktop-only" is
about placement, not capability.** All 49 imports are multiplatform Compose;
`Popup`, `PopupPositionProvider`, `PopupProperties`
(`SuggestPopup.kt:46-48`) all exist on Android. The 180-line file could be
promoted essentially unchanged.
→ *A mobile toolbar/suggest design may reuse the caret-anchored popup and the
whole `Suggest.kt` machine. The genuinely new mobile problem is not the popup
— it is that the soft keyboard occupies the bottom half of the screen, so a
caret-anchored popup must flip and the "positioned by
`TextLayoutResult.getCursorRect`" strategy (ADR 0015:110-113) needs IME-inset
awareness. That is new work, but small and well-bounded.*

**4. What a mobile toolbar would reuse from the pure machines.** A formatting
toolbar (bold/italic/heading/list/checkbox/link) is the natural mobile answer
to "no keyboard shortcuts". It needs range-manipulation over
`TextFieldValue` — and the primitives exist or are one step away:
`insertAtCaret` (`NoteEditor.kt:195`), `toggleCheckbox`
(`MarkdownEditing.kt:208`), `activateLine` (`:142`), `caretLine` (`:184`).

**Confirmed: ADR 0015 § 5's `replaceRange` primitive landed, and it landed in
the shared set** — `Suggest.kt:307`,
`fun replaceRange(value: TextFieldValue, range: IntRange, replacement: String): TextFieldValue`,
covered by three tests (`SuggestTest.kt:286,295,307`) pinning caret placement,
no-trim/no-auto-space, and exactness at both ends. So the exact-range write a
toolbar needs is **already inherited and already tested**. A mobile formatting
toolbar is largely composition over existing pure functions plus new
composables — the least risky substantial thing in this whole survey.
For a due-date button, `dateCandidates`/`resolveDatePhrase`
(`TaskDates.kt:86,50`) are pure and ready.

**5. The tasks plugin's contributions do NOT gate mobile surfaces today —
they are desktop-composed only.** `contributionsTo`/`contributes`
(`Plugins.kt:92-98`) are shared and Android-safe, and `CaptureBar.kt:66-67`
does gate the mic on a contribution — that one works on mobile. But
`navigationPanels` (`Plugins.kt:126-139`) returns rail destinations, and the
rail is desktop-only; `tasksDecorations` (`Plugins.kt:347`) is shared but is
consumed by `NoteEditor`, which is desktop-only. ADR 0015 debt 10 (`:573-575`)
is explicit: "the shell knows `tasks.fields` belongs to `org.memory.tasks`
because it is **written in shell code** … a second host would have to
re-implement it."
→ *So: a mobile shell is that second host. Slot **reading** is free; slot
**rendering** is per-shell and must be written. The design should say which
slots the mobile shell renders (I would argue `capture.actions` and
`note.actions` for v0, `navigation` only if a mobile rail exists) and state
that the rest render nowhere on phone — which ADR 0011's "renders nothing
while empty" already permits.*

**6. `GiluneState` already registers both bundled plugins on open**
(`:42-53`), including the grant-once-on-first-install consent policy. Mobile
inherits plugin registration, enablement and consent **for free** — but has
no settings screen to expose any of it (see Traps).

---

## 5. Traps — what would make a naive design wrong

**T1. "It's in `jvmShared`, so it works on Android" is unproven by
execution.** `androidMain` has not compiled since M6a. Everything in §1 is an
API-level verdict from imports, not a build. Notably `org.jetbrains:markdown`
is declared for Android (`build.gradle.kts:49`) but that KMP artifact's
Android variant resolution has never been exercised here — if it publishes no
`android` variant, Gradle may fail or fall back to the JVM variant. **The
first task of any mobile work is a compile, not a design.**

**T2. `Prefs` does not work on Android.** `Prefs.kt:8,13` uses
`java.util.prefs.Preferences.userRoot()`. Android ships the `java.util.prefs`
API but its backing store is unusable in practice (reasoned from the API, not
tested here). Mobile needs `SharedPreferences` or DataStore. This matters
because `Prefs` holds `vaultDir`, `theme`, `readingMode`, rail width and tab
layout — i.e. **every device-local fact ADR 0011:84 assigns to the device.**
A mobile design needs its own device-local store, and `EditorState` promotion
(§4.1) trips over it at `NoteEditor.kt:139`.

**T3. The capture bar is a contract, not a widget.** ADR 0011's amendment
pins it as the Android surface. But `CaptureBar.kt` was written for a desktop
foot-strip: `singleLine = true` (`:103`) with `Key.Enter` intercepted via
`onPreviewKeyEvent` (`:95-102`). On a soft keyboard Enter is an IME action,
not a key event, and a capture-first phone app arguably wants multi-line. The
design may restyle it — but should say it is honouring the amendment's
*region*.

**Confirmed by grep: `CaptureBar(` has exactly one caller in the whole tree —
`MainActivity.kt:49`.** Desktop dropped it with the ADR 0011 amendment. So
`CaptureBar.kt` (198 lines) is **de facto Android-only code sitting in the
shared source set**, and the design is free to reshape it for touch without
any desktop regression risk. Its `RecentList` (`:173-198`) is likewise
mobile-only and is the obvious seed for a capture-feed home screen.

**T4. The checkbox glyph is a touch-target failure.** E3 renders `- [ ]` as a
**single character** (`MarkdownEditing.kt:218-219`, `☐`/`☑`) and desktop makes
it clickable via `decorationAt` over a transformed offset, with a hand cursor
via `pointerHoverIcon` (`CLAUDE.md` records the `overrideDescendants=true`
fix). On touch there is **no hover**, and one glyph at 13 sp is far below the
48 dp minimum tap target. Tapping it also places the caret, which by E3's own
rule (`MarkdownEditing.kt:99-107`) reverts that line to raw. **Toggling a
checkbox by tapping it is a genuinely unsolved interaction on mobile** and
needs a deliberate answer (a wider invisible hit area, a swipe, or a toolbar
action) — not an assumption that the desktop gesture ports.

**T5. Plugin consent surfaces have no mobile home.** ADR 0011:52-66 requires a
per-plugin surface showing class, profile, permissions, events and
contributions, and requires **network use to be disclosed and consented before
it happens**. `SettingsPane.kt` (618 lines) is desktop-only. Android has no
settings screen at all. ADR 0006:73-74 puts permission prompts on the shell.
→ *A mobile design that ships plugins without a consent surface violates
0011. Either promote a reduced settings screen or state that mobile v0 ships
only bundled plugins whose local capabilities are consented by installation
(the policy `GiluneState.kt:39-46` already implements) — and that
`network.request` therefore cannot be granted on phone at all.*

**T6. The kernel opens vaults by absolute path.** `Gilune.open(vaultDir)`
(`GiluneState.kt:34`) takes a `String` path and Rust does `File` I/O beneath
it. Any SAF-based storage design collides with this at the FFI boundary. See
§2 option 3 — this is kernel work, not shell work.

**T7. `minSdk = 29` / `targetSdk = 36` / `compileSdk = 36`**
(`build.gradle.kts:70-73`). API 29 is Android 10. That is what makes
`java.time` free (good) but it also means **scoped storage is fully in force**
— the pre-Android-10 "just write to /sdcard" escape does not exist. Any
user-visible storage design must go through SAF or `MANAGE_EXTERNAL_STORAGE`.

**T8. No `windowSoftInputMode` in the manifest.** An editor design must
specify IME inset behaviour (`adjustResize` / `WindowInsets.ime`), or the
keyboard will cover the caret. Trivial to fix, easy to forget, and it
invalidates any mockup that does not reserve the bottom half of the screen.

**T9. There is no UI-test net on Android.** `desktopTest` gets
`compose.desktop.uiTestJUnit4` (`build.gradle.kts:36`);
`androidInstrumentedTest` gets only `kotlin("test")` + androidx.test runner
(`:53-59`). The 22 desktop tests including the UI regression tests do not run
on Android. A mobile shell ships with the shared *logic* tests and nothing
else unless a compose-ui-test dependency is added.

**T10. Dark mode is doubly broken.** The manifest pins
`Theme.Material.Light.NoActionBar` (`:6`) and `MainActivity` uses stock
`MaterialTheme` with no dark scheme (`:43`). Both must change together, and
`Prefs.theme` (the desktop mechanism, `Prefs.kt:20-23`) is unavailable (T2).

**T11. ARM64 has never executed.** Every Android number in the repo is
x86_64 emulator. The arm64 `.so` builds and packages and has **never run**
(`spikes/s1-kernel-boundary/README.md:167-172`). A design should not treat
any performance affordance as validated on the hardware it will ship to.

---

## Suggested reading order for the design agent

1. ADR 0011's amendment block (`:133-163`) — the capture bar rule.
2. ADR 0014 § Mobile (`:142-146`) — the promotion licence.
3. ADR 0015 § Mobile (`:423-430`) — what is pure and what is placed.
4. `MainActivity.kt` — 59 lines, the entire current app.
5. This file's §2 storage options — the decision most likely to be made
   accidentally.
