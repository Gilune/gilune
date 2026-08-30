# Downloaded plugin execution on the mobile stores — research note

Researched 2026-08-30 (three parallel sweeps: Apple wording + enforcement,
Google wording + enforcement, shipped precedents). Every claim carries its
source; verbatim quotes were pulled from the live policy pages and Apple's
current agreement PDF on that date. This note refines the matrix's two
downloaded-WebAssembly cells; it changes no decision by itself.

## The question

ADR 0006 restricts store builds to bundled plugins because downloaded
executable code is prohibited. But both stores carve out interpreted/VM
code — and a capability-sandboxed wasm runtime is an interpreter whose
entire purpose is the "indirect access" the carve-outs describe. Is there
a defensible, precedented path to *downloadable* sandboxed plugins in
store builds?

## What the texts actually say (verbatim, checked 2026-08-30)

**Google Play, Device and Network Abuse policy**
([answer 16559646](https://support.google.com/googleplay/android-developer/answer/16559646);
the older answer 9888379 now 301-redirects to identical text):

> "an app may not download executable code (such as dex, JAR, .so files)
> from a source other than Google Play."

> "This restriction does not apply to code that runs in a virtual machine
> or an interpreter where either provides indirect access to Android APIs
> (such as JavaScript in a webview or browser)."

> "Apps or third-party code, like SDKs, with interpreted languages
> (JavaScript, Python, Lua, etc.) loaded at run time (for example, not
> packaged with the app) must not allow potential violations of Google
> Play policies."

Textual observations, each verifiable on the page itself:

- The prohibited examples (dex, JAR, .so, "native code") are all
  *platform-native* formats. Wasm is named on neither side of the line.
- The exception says "**a** virtual machine or **an** interpreter" — not
  the system's — and its condition is *indirect access to Android APIs*,
  which is a literal description of capability-mediated hostcalls.
  "(such as JavaScript in a webview or browser)" is an illustrative
  parenthetical.
- The very next sentence regulates runtime-loaded **Python and Lua**,
  which run in no webview: the policy itself contemplates bundled
  interpreters loading code at runtime, and conditions them rather than
  banning them.
- Google's own "Key Considerations" checklist on the same page paraphrases
  the rule as prohibiting downloads of "executable code (like dex or .so
  files) … **(except in VMs/interpreters)**" — no webview qualifier.
- Google ships
  [androidx.javascriptengine](https://developer.android.com/develop/ui/views/layout/webapps/jsengine),
  a sandboxed non-WebView JS engine for apps that can also compile
  app-supplied WebAssembly — incoherent with a system-webview-only
  reading.

**Apple.** Two texts govern. App Store Review Guideline
[2.5.2](https://developer.apple.com/app-store/review/guidelines/) is the
famous prohibition ("may not download, install, or execute code which
introduces or changes features or functionality of the app"). But:

- **Guideline 4.7** explicitly permits software "not embedded in the
  binary, specifically HTML5 and JavaScript mini apps and mini games,
  streaming games, chatbots, **and plug-ins**", and since April 2024 lets
  emulator apps "offer to download games" — downloaded foreign bytecode
  executed by a bundled VM. Conditions 4.7.1–4.7.5: privacy compliance
  and content moderation/reporting/blocking; no extending or exposing
  native platform APIs to the software without Apple's permission
  (4.7.2); **explicit user consent in each instance** before sharing data
  or permissions with any individual software (4.7.3); a public index of
  offered software with universal links (4.7.4); age-rating gates
  (4.7.5).
- **Developer Program License Agreement 3.3.1(B)** (current PDF, stamped
  2026-08-18): "Interpreted code may be downloaded to an Application but
  only so long as such code: (a) does not change the primary purpose of
  the Application … (b) does not bypass signing, sandbox, or other
  security features of the OS; and (c) … does not create a store or
  storefront for other Applications." **The old "only by Apple's built-in
  WebKit framework or JavascriptCore" restriction was removed in June
  2017** ([The Register,
  2017-06-07](https://www.theregister.com/2017/06/07/apple_relaxes_developer_rules/));
  the widely-cited engine lock has been dead for nine years. The current
  clause is engine-agnostic.

## Precedents (all store listings checked live 2026-08-30)

| Precedent | Store(s) | Model | What it proves |
| --- | --- | --- | --- |
| Obsidian mobile | both | Downloaded community JS plugins run in the system WebView | A note-taking app with an in-app plugin directory ships today; the JS-in-webview lane is fully proven |
| Roblox | both | Millions of downloaded, unreviewed Luau-bytecode "experiences" in a bundled VM | The model scales — but survives via commercial alignment plus real 4.7.1–4.7.5 machinery (moderation, index, age gates); negotiated, not doctrinal |
| Delta (2024) | Apple | Downloaded ROMs executed by bundled emulator cores | Foreign bytecode for a bundled VM became legitimate the day Apple named the category in 4.7 |
| a-Shell | Apple | Users download `.wasm` commands; wasm3 interpreter + WebKit wasm | Apple's reviewer objected to "curl, pip and wasm" (Nov 2020); **appeal granted** — the only documented review decision naming wasm, resolved favourably |
| Wasmic | Apple | Executes arbitrary user-provided wasm/WASI modules | Apple-approved execution of wasm the reviewer never saw (developer-tool framing) |
| Shareup | Apple | wasm3 interpreter, **bundled** wasm | The safe first step; matches ADR 0006's v0 stance |
| Pydroid 3, QPython | Play | Bundled CPython + pip-installed packages, ~a decade live | Bundled interpreters executing downloaded code are tolerated at scale on Play |
| Expo / CodePush OTA | both | Downloaded **Hermes bytecode** for a bundled non-system VM, industrial scale | Enforcement follows behaviour, not the literal engine; no documented takedown of the model itself |
| Termux | Play | `apt` downloads native binaries; team itself calls the technique policy-violating | Live regardless — Play enforcement targets dex/.so-downloading SDKs and malware, not interpreters |
| Prime Video | **not** mobile stores | Daily OTA wasm modules via WAMR to 8,000+ device types | Engineering feasibility at scale; explicitly excluded from iOS ("we don't deploy this … on mobile devices") |

Enforcement pattern across both stores: what gets killed is downloaded
code reaching **native/private APIs** (Apple's 2017 JSPatch/Rollout
crackdown), downloaded **dex/JAR/.so** (Play's automated SDK scanning,
2023+; Pinduoduo malware suspension), behaviour that visibly changes the
app after review (Apple's 2026 vibe-coding removals), and anything
resembling **an alternative app store**. Nobody has a documented ruling —
approval or rejection — that turns on downloaded wasm in a bundled
non-web runtime. Reviewer misfires happen (iSH and a-Shell 2020, CPython
2024 static-scan false positives) and are corrected by appeal.

## Reading for Gilune

1. **The sandbox is a compliance asset, not a costume.** Deny-by-default
   capability mediation is the strongest possible answer to Play's "must
   not allow potential violations" condition and Apple's 3.3.1(B)(b) and
   4.7.2 — structurally, not promissorily. Our plugins never see a
   platform API; they see a capability-checked contract world.
2. **Play is near-settled textually**; residual risk is reviewer
   discretion in the absence of a wasm-specific precedent, and the
   documented fallback (execute plugins via the system
   WebView/JavaScriptSandbox) exists if a review ever challenges the
   bundled runtime.
3. **Apple is favourable but unproven for a *catalogue*.** The interpreter
   is not the risk; the storefront clause (3.3.1(B)(c)) and 4.7's
   conditions are. A compliant plugin directory needs: a public index
   with universal links, per-plugin consent (our capability-grant UX is
   exactly this), content moderation/reporting, and age gates — product
   requirements, not review-day surprises.
4. **iOS forbids JIT for third-party apps** — plugin execution there is
   interpreter (wasm3/WasmKit/WAMR-interp/Pulley-class) or AOT-at-install.
   A performance budget to measure, not a blocker; a-Shell and Wasmic
   ship this way.
5. **The graduated ladder stands**: bundled (proven, ADR 0006 v0) →
   declarative/data extensions (legal everywhere) → JS-in-system-engine
   tier (Obsidian-proven on both stores) → downloaded wasm plugins
   (textually defensible on both stores, no direct precedent; test with
   a minimal review submission before promising anything).
6. **Nothing here changes v0.** ADR 0006 holds. When dynamic mobile
   plugins are actually scheduled, this note feeds a fresh ADR, and every
   source above gets re-verified — both stores moved their texts within
   the last two years.

## Honest gaps

- No documented store ruling on downloaded wasm in a bundled non-web
  runtime, on either store, in either direction.
- Roblox's tolerance is negotiated and non-transferable; treat it as an
  existence proof, not a licence.
- Apple's 4.7 names HTML5/JavaScript alongside "plug-ins"; whether a
  reviewer reads "plug-ins" as technology-neutral is untested.
- Google has never glossed the VM/interpreter exception officially beyond
  the policy page itself; the bundled-interpreter reading rests on the
  page's internal logic and enforcement record, not a Google statement.
- Store texts drift: every quote above is dated 2026-08-30 and must be
  re-verified at any release gate.
