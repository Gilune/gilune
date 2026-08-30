# Foundation Acceptance Review

Status: **Prepared — awaiting the owner sitting** (prepared 2026-08-30)

The spike plan requires a single review against the constitution after all
spikes, producing: final disposition of every Proposed ADR, revised
contracts, a constitution-to-evidence trace, the remaining gates, and a
go/no-go for the first production vertical slice. This document is the
prepared material; the owner sitting ratifies §1 dispositions and §5.
The S5 threat-model workshop is the one review input still to be held; it
gates the public-launch items in §4, not the slice itself.

## 1. ADR dispositions (drafts for ratification)

| ADR | Recommendation | Basis |
| --- | --- | --- |
| 0001 shell + kernel language | *(already Accepted for v0, provisional)* | Owner decision 2026-08-30; device budgets assumed |
| 0002 SQLite + external blobs | **Accept for v0** | Gate met: full termination matrix incl. migration vs a real v1 fixture, Linux + Windows, no flakes |
| 0003 WIT + wasm components | **Keep Proposed** | Contract half proven; bundled-Android parity, streaming/async through WIT, and lifecycle remain |
| 0004 durable events/jobs | *(already Accepted for v0)* | S2 validated the implementation on both platforms |
| 0005 operation-based sync | **Keep Proposed** | Convergence semantics proven (3 replicas, tombstones, unknown types, interrupted blobs); causal frontiers, encryption/key epochs, retired devices, compaction, test vectors remain |
| 0006 bundled mobile + declarative UI | *(already Accepted for v0)* | S5 matrix confirms; ADR 0010 sets post-v0 direction |
| 0007 Markdown vault projection | **Accept for v0** | Gate met: projection, rebuild, bidirectional reconciliation incl. crash points, closed-vault restore — both platforms |
| 0008 headless agent + LAN-first | **Keep Proposed** | Feasibility proven (headless kernel hosts in S1/S2; shared-folder transport in S4); LAN discovery, pairing, revocation, service packaging unbuilt — now carried by the vertical slice as its validation vehicle (§5) |
| 0009 licensing | *(already Accepted)* | Owner ratification 2026-08-30 |
| 0010 downloadable store plugins | *(already Accepted, direction)* | Test submission gates promises |

Notes for the two acceptances:

- **0002**: pre-1.0 hardening owed — an I/O-interposition/fault-injecting
  filesystem pass (process aborts only so far) and a second, materialised
  payload-rewriting migration shape. Encryption-at-rest rides with the
  crypto ADR, as 0002 itself specifies.
- **0007**: the copy-versus-reference blob measurement its gate mentioned
  is moot in the ratified layout (blobs live once, in the vault); the
  human-readable timestamp format stays an open vault-format detail;
  filesystem watchers are an implementation concern beyond the startup
  scan the spikes proved.

For 0003, 0005, and 0008 no evidence contradicts the decision; each keeps
Proposed status only because named gate items are unexercised. The named
remainders above are the flip conditions.

## 2. Constitution-to-evidence trace

Evidence classes: **T** automated test (spike suite, repo-runnable),
**F** recorded finding/measurement, **D** design/document review, **—** no
evidence yet.

| Rule | Evidence | Residual gap |
| --- | --- | --- |
| 1. Capture preservation-first | T: S2 capture termination matrix — "saved" never false, pre-commit aborts leave no half-state; capture works with every processor disabled | Real audio capture adapters (mic, permissions) are slice work; spikes captured bytes/text |
| 2. Source vs interpretation | T: S2 — an edited source file is flagged, never ingested as source history; transcripts/tasks are separate objects with provenance | None at spike fidelity |
| 3. Client-first, locally useful | T: every spike runs serverless; S4's relay is a dumb folder. F: S1 Windows + Android boundary | Device budgets assumed (ADR 0001 provisional) |
| 4. Kernel owns invariants, not features | D: kernel modules name no domain type (S2 layout, reviewed). T: with processors disabled, capture/projection/export still work | Holds by review discipline; conventions doc now encodes it |
| 5. First-party uses public contracts | T: S3 parity — bundled native and sandboxed component produce byte-identical outputs from the same WIT contract | Desktop fidelity only; bundled-Android parity outstanding (0003); real platform adapters are slice work |
| 6. Plugins least-privileged, replaceable | T: S3 deny-by-default grants, out-of-scope refusal as errors, audit trail; S2 disable → capture/export unaffected, re-enable backfills | Install/upgrade/uninstall lifecycle, signing, per-use confirmation UX (pre-third-party gates) |
| 7. Durable facts, at-least-once | T: S2 worker termination matrix, duplicate + reordered delivery, retry → quarantine → resume; S4 idempotent application | Torn-write/fsync-reordering below the filesystem API (pre-1.0 hardening) |
| 8. Sync meaning transport-independent | T: S4 — identical semantics over a dumb file relay; device-local state excluded portably (S2 Windows fix). D: no DB/WAL-copy path exists | LAN transport, causal frontiers beyond per-device sequences (0005/0008 remainders) |
| 9. Unknown data preservable | T: S2 unknown-type project/export/edit-quarantine; S4 unknown type round-trips across replicas and projects generically | None at spike fidelity |
| 10. Security fails closed | T: S3 missing/out-of-scope grants are errors, never traps; S4 corrupt op and sequence gap halt the stream safely; S2 malformed front matter quarantines | Signatures and key material do not exist yet — the crypto half of this rule is entirely unevidenced (pre-hosted-sync gate) |
| 11. Portability continuously testable | T: S2 export verifies by manifest on an independent tree; fresh-vault open is safe; S4 vault projection byte-identical across replicas | Public format documentation owed by 1.0 (draft exists in S2 artefacts) |
| 12. Compatibility is a feature | T: S2 v1→v2 migration incl. mid-crash against a fixture from the real v1 binary. F: S1 checksum mismatch fails closed loudly — skew is detected | Version/feature negotiation untested (0003 remainder); one migration shape only |
| 13. Openness is a goal | T: ADR 0007 gate — third-party edits, renames, deletions, foreign files reconcile non-destructively; Obsidian-openable vault. D: ADR 0009 licences, ADR 0010 direction | Vault-format public documentation pending |

The trace surfaces two rules with structural (not incidental) gaps: rule
10's cryptographic half and rule 12's negotiation machinery. Both are
scheduled behind named gates below, not silently absent.

## 3. Contract revisions made in this pass

- `03-data-model.md` § Export contract: relative paths are a
  `/`-separated portable contract on every platform (S2 Windows finding).
- `05-plugin-system.md` § Capability model: standard-library components
  implicitly import WASI; hosts always supply a deny-by-default WASI
  context; broader ambient facilities are declared capabilities (S3
  finding).
- `07-compatibility.md` § API evolution: generated bindings and their
  source binary are one per-target artifact; checksum mismatch fails
  closed and must not be weakened (S1 finding).

No contradictions between contracts and spike evidence were found beyond
these; ADR 0006/0010 cross-references were already recorded.

## 4. Remaining gates (consolidated)

- **Pre-vertical-slice**: none. The slice's scope must exclude everything
  gated below.
- **Device re-validation** (when hardware arrives): ADR 0001 budgets —
  capture-commit < 200 ms, cold start < 1.5 s on mid-range Android; first
  ARM64 execution; S2 suite on-device.
- **Pre-hosted-sync**: the crypto ADR (E2EE envelope, key hierarchy,
  epochs, enrollment/revocation), external security review, relay
  retention/abuse policies (`08-open-questions.md` § hosted).
- **Pre-third-party-plugin installation**: publisher identity/signing/
  transparency, publication checks, quotas, revocation-vs-offline,
  upgrade recovery copies (`08-open-questions.md` § plugins), plus the
  S3 lifecycle remainder (0003).
- **Pre-store-downloadable-plugins**: ADR 0010's minimal test submission
  per store, with policy sources re-verified.
- **Threat-model workshop** (S5's second half, owner + an hour): assigns
  owners to the highest-risk threats; gates public launch, not the slice.
- **Pre-1.0 hardening**: I/O-interposition fault injection, a second
  migration shape, JVM-host Windows symbolication (module+offset or
  minidumps), format documentation.

## 5. Go/no-go for the first vertical slice

**Recommendation: GO**, with the scope amended by the owner (2026-08-30)
to include one real sync transport:

- capture (real microphone adapters) → durable kernel commit →
  transcription via a bundled first-party plugin exercising the public
  contract → vault projection → **LAN peer-to-peer sync between paired
  devices** — on Windows desktop and Android;
- LAN-in-scope consequences, accepted deliberately: device identity
  keypairs, mutual device authentication, an encrypted transport channel,
  local discovery, and pairing UX (short code/QR per ADR 0008) are slice
  deliverables. This is *transport* security only — the relay/E2EE
  envelope, key hierarchy, and epochs stay behind the pre-hosted-sync
  gate. The S4-extended LAN scenarios (pair, refuse unpaired, revoke,
  rejoin) become slice acceptance tests, making the slice ADR 0008's
  validation vehicle.
- The shared-folder transport stays spike-proven and ships whenever
  convenient after the slice; it is not slice-blocking.
- Excluded from the slice: relay/E2EE cryptography, marketplace,
  third-party plugin installation, hosted anything.
- Sequencing note: the threat-model workshop should precede the sync
  milestone of the slice — pairing and impersonation threats are now
  slice-relevant, not just launch-relevant.

Owner ratification of §1 and this section closes the review.
