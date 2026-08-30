# Spike S5 — Distribution Policy Matrix

Status: **Draft** — policy matrix researched against live sources on
2026-08-29; the threat-model workshop half of S5 is pending with the
project owner.

Store rules change; every claim below carries its source and the date it
was checked. Re-verify before any release decision.

## Matrix (checked 2026-08-29)

| Payload class | Google Play build | Open/direct Android | Apple App Store | Microsoft Store | Direct Windows/Linux | Direct macOS | Self-hosted server |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Bundled native code (kernel, first-party plugins) | ✓ reviewed with app | ✓¹ | ✓ reviewed with app | ✓ | ✓ | ✓ signed+notarized | ✓ |
| Downloaded native code (dex/JAR/.so/dylib) | ✗ explicit² | ✓¹ | ✗ explicit³ | ✗ if it changes described functionality⁴ | ✓ (own responsibility) | ✓ if signed; unsigned may be blocked⁵ | ✓ |
| Downloaded WebAssembly components | Arguable-favourable²: VM/interpreter carve-out fits a capability-mediated runtime textually; no wasm precedent either way — see [research note](downloaded-plugins-research.md) | ✓¹ | Arguable-favourable³: DPLA 3.3.1(B) engine-agnostic since 2017, 4.7 names “plug-ins”, a-Shell/Wasmic/Delta precedents; catalogue duties (4.7.1–4.7.5) bind — see [research note](downloaded-plugins-research.md) | Conditional: allowed if within described functionality⁴ | ✓ | ✓ (host is signed; components are data to the OS) | ✓ |
| Interpreted scripts at runtime (JS/Lua/…) | Carve-out only via VM/interpreter with indirect API access; must not enable policy violations² | ✓¹ | HTML5/JS mini-apps and plug-ins permitted under 4.7 conditions³ | Conditional⁴ | ✓ | ✓ | ✓ |
| Schemas, settings, declarative workflows/UI (data the reviewed host interprets) | ✓ (data, not code)² | ✓ | ✓ (host stays within reviewed behaviour)³ | ✓ | ✓ | ✓ | ✓ |
| Model files (weights) | ✓ (data) | ✓ | ✓ (data) | ✓ | ✓ | ✓ | ✓ |
| Remote configuration | ✓ within policy | ✓ | ✓ within reviewed behaviour | ✓ within described functionality | ✓ | ✓ | ✓ |

## Channel notes and sources

1. **Open/direct Android is no longer unconditioned.** Google is rolling
   out developer verification for app installs on certified Android
   devices: enforcement begins 2026-09-30 in Brazil, Indonesia, Singapore,
   and Thailand, expanding globally through 2027. Sideloading unverified
   apps falls back to ADB or an “advanced flow” with added friction. The
   ADR 0006 “open Android build” fallback therefore still exists but
   requires registering as a verified developer, and the friction budget
   for unverified distribution should be assumed hostile.
   Sources: [Android Developers Blog — new security layer
   (2025-08)](https://android-developers.googleblog.com/2025/08/elevating-android-security.html),
   [Android Developers Blog — verification rollout
   (2026-03)](https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html),
   [Android Authority timeline
   summary](https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/).
2. **Google Play — Device and Network Abuse policy** (checked live):
   an app “may not download executable code (such as dex, JAR, .so files)
   from a source other than Google Play”; exception for code running “in a
   virtual machine or an interpreter where either provides indirect access
   to Android APIs (such as JavaScript in a webview or browser)”; runtime-
   loaded interpreted languages “must not allow potential violations of
   Google Play policies”, and JavaScript-interface webviews must not load
   untrusted content.
   Source: [Play policy answer
   16559646](https://support.google.com/googleplay/android-developer/answer/16559646).
   Reading for Gilune: bundled-only execution for the Play build (ADR 0006
   holds). A future argument that a capability-denied Wasm interpreter fits
   the carve-out is *possible* — our sandbox denies filesystem/network by
   default, which strengthens “cannot enable policy violations” — but it is
   unproven and must not be a product promise.
3. **Apple App Store Review Guidelines** (checked live): 2.5.2 — apps “may
   not download, install, or execute code which introduces or changes
   features or functionality of the app”; 2.5.6 — web browsing must use
   WebKit (alternative engines by entitlement); 4.7 — apps *may* offer
   non-embedded “HTML5 and JavaScript mini apps and mini games, streaming
   games, chatbots, and plug-ins” subject to 4.7.1–4.7.5, notably “may not
   extend or expose native platform APIs … without prior permission from
   Apple” and per-instance consent for sharing data/permissions.
   Source: [App Store Review
   Guidelines](https://developer.apple.com/app-store/review/guidelines/).
   Reading for Gilune: bundled-only for the iOS build; the 4.7 plug-ins
   avenue is the doorway a future JS-based declarative-plus layer would
   argue through, with 4.7.2 cutting against exposing our capability
   surface without Apple's sign-off.
4. **Microsoft Store Policies v7.19** (effective 2025-10-14; page updated
   2026-07-30; checked live): 10.2.2 — a product “must not attempt to
   fundamentally change or extend its described functionality … through
   any form of dynamic inclusion of code”; 10.2.3 — no secondary software
   not developed by you that doesn't enhance the product; 10.1.5 — add-ons
   and extensions that enhance the product may be acquired after install;
   10.2.9 — signed Win32 apps can be Store-listed via a versioned direct
   download URL. Source: [Microsoft Store
   Policies](https://learn.microsoft.com/en-us/windows/apps/publish/store-policies).
   Reading for Gilune: the most workable store for the full plugin model —
   sandboxed plugin execution is arguably *described functionality* if the
   listing says so plainly; the red line is plugins changing the app beyond
   what was described or violating other policies.
5. **Direct macOS**: distribution outside the Mac App Store requires
   Developer ID signing and notarization; Gatekeeper blocks unsigned or
   suspicious dynamically loaded code, while signed plugin loading is
   supported. Source: [Apple notarization
   documentation](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution).
   Reading for Gilune: Wasm components are data to the OS loader, so the
   sandboxed profile is clean here; a trusted native bridge would need its
   own signing/notarization story.

## Consequences for the architecture (proposed)

- ADR 0006 holds as written for store builds. Its open-Android fallback
  gains a real precondition: developer verification (identity registration
  with Google) even outside Play, from 2026–2027.
- The marketplace UX rule (“available plugin” depends on platform and
  distribution profile) is confirmed as necessary by all three stores.
- The manifest's platform-support field should distinguish: bundled-only
  (Play, App Store), sandboxed-installable (desktop, self-hosted,
  Microsoft Store within described functionality), and data-only
  contributions (everywhere).
- S3's finding that Rust-std guests implicitly import WASI belongs in any
  store-review narrative: the sandbox denies ambient authority by default,
  which is the strongest single argument that installable components are
  configuration, not "code that changes the app" in the policy sense. That
  argument is untested with any reviewer.

## Still owed for S5

- The threat-model workshop (malicious plugin/publisher/device/relay,
  migrations, exports, recovery) — needs the project owner; agenda should
  follow `06-sync-and-security.md` § Required threat-model follow-up.
- A decision on whether to seek Google Play's interpreted-code carve-out
  or Apple's 4.7 route for any post-v0 remote extension. The
  [downloaded-plugins research note](downloaded-plugins-research.md)
  (2026-08-30) upgrades both from "do not plan on either" to
  textually-defensible-but-unprecedented; the v0 stance (bundled only)
  is unchanged, and any promise still requires a minimal test submission
  to each store first.
- Re-verification of all sources at each release gate (store rules moved
  twice in the twelve months before this draft).
