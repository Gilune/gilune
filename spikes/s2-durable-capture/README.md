# Spike S2 — Durable Capture, Events, Recovery, and Vault Projection

Status: **In progress** — core fault matrix green on Linux; not yet run on
target platforms.

Disposable evidence prototype (see `architecture/spikes/README.md`, S2) for:

- ADR 0002 — SQLite core store plus blobs coordinated transactionally;
- ADR 0004 — transactional events and at-least-once durable jobs;
- ADR 0007 — Markdown vault projection with bidirectional reconciliation.

## What it is

A single Rust crate: a miniature kernel core (`src/`) driven by a CLI
(`spike-s2`), plus a fault harness (`tests/s2_matrix.rs`) that runs the real
binary as a subprocess, aborts it at named crash points
(`GILUNE_CRASHPOINT=<point>`), restarts it, and asserts the recovery
invariants. Every CLI command reopens the kernel and runs the startup
recovery checks, so each restart after a kill exercises recovery.

## Where the sample domain lives — and what it does and does not prove

Tasks and transcription are DOMAIN features: plugins in the real system.
S2 uses them only as the spec's designated sample domain, to drive a
two-hop causal chain (source-ready → transcript → task proposal) through
crashes and duplicate delivery. Everything domain-flavoured is quarantined
in `src/processors.rs`, which simulates two bundled plugins: handlers see a
deliberately narrow `ProcessorCtx` shaped like a scoped `objects.read`
grant (read a referenced source's bytes, read an input revision's payload)
— never a database connection; subscriptions are declared there as a
manifest would; and vault rendering for those types is contributed from
there as `TypeRenderer` entries. The kernel modules (`store`, `capture`,
`jobs`, `vault`, `export`) name no domain type; the projection falls back
to a generic preservable form for unregistered types, and an external edit
to an unregistered type quarantines because no owner can interpret it.
(The CLI's `status`/`verify` diagnostics do name sample types — that is
the harness observing the domain, not the kernel depending on it.)

What this deliberately does NOT prove: the plugin boundary itself.
Capability grant/denial, manifests, sandboxing, cross-language contracts,
and upgrade/uninstall are Spike S3's acceptance gate. `ProcessorCtx` shows
where that boundary will sit; it does not enforce it.

Pipeline: `capture` stages bytes, commits envelope + blob metadata +
operation + event + subscription jobs in one SQLite transaction, then
promotes the blob into the vault by atomic rename. `work` claims durable
jobs under leases and runs two deterministic fake processors
(transcription, task proposal) whose outputs commit idempotently under the
documented key. `project` maintains the Markdown vault; `reconcile` ingests
external edits, renames, deletions, and foreign files; `export` copies the
vault plus an integrity manifest.

Layout decision exercised here (open question in `08-open-questions.md`):
the database and staging live in `.gilune/local/` (device-local, excluded
from export); original blobs live once, visibly, inside the vault
(`Captures/…`), so Obsidian can play them and the vault is the blob store.

## Run it

```sh
cargo test                      # full fault matrix
cargo run -- --vault /tmp/v init
cargo run -- --vault /tmp/v capture --text "TODO: try me"
cargo run -- --vault /tmp/v work
cargo run -- --vault /tmp/v project
```

## Evidence so far (Linux container, 2026-08-29)

15 harness tests, run 20 consecutive times with no flakes:

- **Capture termination matrix** — abort at `stage.after_write`,
  `stage.after_fsync`, `commit.inside`, `commit.after`, `promote.after`:
  "saved" is never reported for an unrecoverable capture; committed captures
  always survive restart; pre-commit aborts leave no half-visible state
  (orphan stages go to `lost+found`, never silently deleted).
- **Worker termination matrix** — abort at `job.after_claim`,
  `job.after_work`, `output.commit.inside`: the job is never lost, only
  delayed by its lease; reprocessing yields exactly one transcript and one
  task (deterministic output IDs + idempotency-key–unique processing runs).
- **Duplicate and reordered delivery** — redelivering both events, downstream
  first, creates no duplicate objects and no duplicate runs.
- **Retry and quarantine** — a persistently failing handler retries with
  backoff, quarantines after 5 attempts, blocks neither capture nor export,
  and completes after `retry-quarantined`.
- **Vault** — deleting every projected Markdown file rebuilds byte-identical
  content; external edits become revisions and converge after
  canonicalisation; external deletion becomes a tombstone proposal and the
  file is not resurrected while the proposal is open; malformed front matter
  and foreign Markdown are quarantined untouched; a renamed file is tracked
  by front-matter ID; an edited *source* file is flagged, never ingested as
  source history (constitution rule 2).
- **Export** — quiescent vault copy verifies by manifest hash on an
  independent tree; device-local state does not travel; opening the export
  as a fresh vault is safe (files become flagged import candidates, nothing
  is lost or crashed).
- **Domain/kernel boundary at S2 fidelity** — with every subscription
  disabled (the stand-in for disabled plugins), capture, projection, and
  export still work and no jobs are created; re-enabling backfills jobs for
  events committed while disabled, and work completes them. An object of an
  unknown type projects in generic form, exports, survives, and rejects
  external edits into quarantine (constitution rules 1, 6, 9).

## Honest gaps (not yet evidence)

- Process abort only: no torn-write / power-failure simulation below the
  filesystem API, and no fsync-reordering fault injection. A follow-up
  should add an I/O interposition layer or run on a fault-injecting
  filesystem before ADR 0002 is accepted.
- No migration crash point yet (single schema version).
- Single process; no concurrent writers or cross-process lease contention.
- Linux only; the acceptance gate needs Android and Windows runs (S1 owns
  the platform boundary).
- Timestamps in front matter are epoch milliseconds; the human-readable
  format is a vault-format decision, not settled here.
- Blob bytes stored once (in-vault); the copy-versus-reference measurement
  ADR 0007 asks for is therefore moot in this layout, but the layout itself
  still needs the shared-folder-sync safety review.

## Artefacts

- `vault-format-draft.md` — draft vault layout and front-matter schema.
- `tests/s2_matrix.rs` — the termination matrix and invariants, which double
  as the crash-state documentation.
