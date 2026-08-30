# Threat-Model Workshop Record

Held 2026-08-30 (owner + assistant), completing the second half of Spike
S5. Agenda per `architecture/06-sync-and-security.md` § Required
threat-model follow-up, plus models/providers and logs from the spike
plan, plus LAN pairing (slice-relevant since the owner's scope decision).
Each threat maps to a mechanism, a user confirmation, a signature/
encryption requirement, an audit surface, or explicit non-support — and
each unresolved item has a named gate.

## Owner decisions made in the workshop

1. **Capture content leaving the device: local-only is the default.**
   Any provider sending capture content off-device requires explicit
   per-provider consent with plain disclosure. The v0 transcription
   provider runs locally (capturing device or paired coordinator per
   ADR 0008); nothing leaves the vault silently. → contract text in
   `05-plugin-system.md` § Provider plugins; resolves the consent open
   question.
2. **Shared-folder transport ships plaintext, disclosed — the posture is
   store-dependent.** For third-party stores the user already trusts
   (Dropbox, a NAS, a git remote), plaintext operation files are a
   reasonable, disclosed user choice — users may even want to read files
   in the store. Disclosure must state that operation history includes
   deleted/tombstoned content the visible vault no longer shows. Any
   store *we* operate is different in kind: the hosted relay must be
   end-to-end encrypted so we cannot read it (already ADR 0005's relay
   contract; unchanged). Note for accuracy: vault Markdown in a shared
   store is readable *and editable* (reconciler ingests edits);
   operation files are integrity-checked — a hand-edited operation halts
   that stream safely rather than applying (S4-proven), which is the
   intended behaviour, not a defect.
3. **Migrations: additive runs automatically; destructive or lossy
   requires explicit user approval plus a retained recovery copy.** →
   contract text in `07-compatibility.md` § Migration execution;
   resolves the migration open question.
4. **Vault at rest stays plaintext in v0.** The vault is as private as
   the disk it sits on; OS disk encryption (BitLocker/FileVault) is the
   documented at-rest story. Optional vault encryption may come later
   but must never break openness by default (constitution rule 13).

## Threat dispositions

| Threat | Disposition | Gate/owner |
| --- | --- | --- |
| Malicious plugin | Deny-by-default capabilities, fuel/memory/output limits, no ambient WASI (S3-proven). High-risk grants (network, broad reads) get explicit consent surfaces | Standing; slice carries consent UX |
| Compromised publisher / update poisoning | v0 is bundled-only (publisher = project). An update can never silently add a capability (05 lifecycle). Signing + transparency required before third-party installs | Pre-third-party-plugins gate |
| Compromised relay, metadata correlation | Relay does not exist in v0; relay is E2EE by contract when built (ADR 0005) | Pre-hosted-sync gate (crypto ADR + external review) |
| Operation replay / truncation / fork | Idempotent application; sequence gaps and corrupt operations halt the stream safely (S4-proven). **New rule: a device restored from backup re-enrolls as a new device identity** — sequence reuse is never legitimate | Standing; re-enrollment rule lands with slice pairing |
| Resource exhaustion via sync (giant ops/blobs) | Per-device quotas and size limits on apply | Slice sync milestone |
| LAN impersonation / unpaired access | Default-deny unpaired devices; pairing by short-code comparison on both screens; revocation supported. These are slice acceptance tests | Slice sync milestone (ADR 0008 validation) |
| Secret exfiltration via granted network | Secrets are opaque handles usable only through declared provider operations; `network.request` scoped by destination; low exposure while bundled-only | Standing; revisit at third-party gate |
| Unsafe schema/migration payloads | Adversarial parsers (0005), transactional migrations (S2-proven), and workshop decision 3's approval rule | Standing |
| Export/backup leakage | Export excludes secrets, grants, and operational data (03). Vault plaintext posture is decision 4, documented | Standing |
| Model/provider exfiltration | Decision 1: local-only default, per-provider consent | Standing |
| Log leakage | Constitution rule 10 bans sensitive plaintext in logs; enforced as a conventions/review item | Standing |
| Recovery-key loss / social engineering | v0 has no data-encrypting keys: transport keys are re-pairable, so key loss costs a re-pair, never data. Real recovery UX arrives with the crypto ADR and must be designed against social-engineering resets | Pre-hosted-sync gate |

## Highest-risk unresolved threats (with owners), per the S5 pass condition

1. **Relay cryptography and key lifecycle** (enrollment, epochs,
   revocation, recovery) — owner: the crypto ADR, behind the
   pre-hosted-sync gate with external security review.
2. **Pairing/impersonation on LAN** — owner: the vertical slice's sync
   milestone; its acceptance tests are the S4-extended scenarios.
3. **Third-party plugin supply chain** (publisher identity, signing,
   transparency, revocation-vs-offline) — owner: the
   pre-third-party-plugins gate (`08-open-questions.md` § plugins).

No v0 product promise depends on any of the above being solved earlier
than its gate.
