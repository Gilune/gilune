# ADR 0010: Downloadable Plugins on Store Builds

Status: **Accepted** (owner decision 2026-08-30)  
Validation: a minimal test submission to each store before any
user-facing promise of downloadable plugins on that store; re-verify the
policy sources in
[`spikes/s5-distribution-policy/downloaded-plugins-research.md`](../../spikes/s5-distribution-policy/downloaded-plugins-research.md)
at that time.

## Context

ADR 0006 restricts store builds to bundled plugins because the stores
prohibit downloaded executable code. Research dated 2026-08-30 (the
research note above; verbatim policy texts, enforcement record, shipped
precedents) found the ground more favourable than the original matrix
assumed: Google Play's prohibition carves out code run by "a virtual
machine or an interpreter where either provides indirect access" to
platform APIs — a literal description of capability-mediated hostcalls —
and its own text contemplates bundled interpreters; Apple's agreement has
permitted downloaded interpreted code engine-agnostically since June
2017, and guideline 4.7 names "plug-ins" as permitted non-embedded
software, with duties (index, per-item consent, moderation, age gates)
that largely coincide with the capability-grant UX this architecture
already requires. No store has ruled on downloaded WebAssembly in a
bundled non-web runtime, in either direction.

## Decision

- The intended path for downloadable plugins on store builds is the
  **capability-sandboxed WebAssembly runtime itself**, presented under
  the stores' VM/interpreter provisions. The sandbox is the compliance
  argument: deny-by-default capability mediation is structurally what
  both carve-outs describe, not a wrapper added for review.
- **ADR 0006's bundled-only stance remains v0.** This ADR sets direction,
  not schedule; nothing user-facing may promise downloadable store
  plugins until the validation above passes.
- The plugin catalogue is designed to Apple 4.7's duties from the start:
  a public index, explicit per-plugin consent (the capability-grant UX),
  content reporting/moderation, and age-rating gates. The catalogue must
  not present plugins as applications (the storefront clause is the
  binding constraint, not the interpreter).
- On iOS the runtime uses an interpreter or install-time AOT (no JIT for
  third-party apps); the performance budget is measured, not assumed.
- **Fallback ladder if a store pushes back**, in order: execute plugins
  via the platform's own engine (system WebView / JavaScriptSandbox — the
  Obsidian-proven lane); failing that, revert that store's build to
  bundled-only plus declarative extensions (ADR 0006's v0 posture).
  Pushback is handled per store; one store's rejection does not retract
  the other's build.

## Why this is preferred

Openness (constitution rule 13) and the plugin-first architecture argue
against a permanent two-class ecosystem where mobile users only receive
plugins the vendor compiled in. The carve-out reading lets the security
architecture and the distribution argument be the same artifact, and the
graduated fallback means the downside of a rejection is the status quo,
not a redesign.

## Alternatives considered

### Bundled-only forever

Zero review risk, but permanently weakens the plugin promise on the
platforms where capture matters most, and pushes users toward sideloading
channels that Google is actively adding friction to.

### A JavaScript plugin tier in the platform engine

Store-proven (Obsidian), but adds a second plugin runtime and language
beside the wasm contract, and the system engine weakens the capability
story. Retained as the first fallback, not the goal.

### Full webview shell (the Obsidian architecture)

Makes the whole app the carve-out but surrenders native capture latency
(ADR 0001) and the sandbox model (ADR 0003). Rejected.

## Consequences

- The catalogue's 4.7-class duties (index, per-plugin consent surfaces,
  reporting, age gates) become product requirements ahead of need.
- The store-review narrative ("plugins are data interpreted by a reviewed
  host that mediates all platform access") should be written down and
  shipped with the first test submission; S3's deny-by-default WASI
  finding is part of it.
- The manifest platform-support field from ADR 0006 gains a state:
  store-downloadable-pending-validation.
- If both stores ultimately refuse, this ADR is superseded and the
  bundled + declarative + open-channel posture continues unchanged.
