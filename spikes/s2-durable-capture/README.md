# Spike S2 — Durable Capture, Events, Recovery, and Vault Projection

Status: **In progress** — core fault matrix green on Linux and Windows;
an Android run of this suite is deferred with the physical device
(S1 closed provisionally 2026-08-30 with device work assumed).

Disposable evidence prototype (see `architecture/spikes/README.md`, S2) for:

- ADR 0002 — SQLite core store plus blobs coordinated transactionally;
- ADR 0004 — transactional events and at-least-once durable jobs;
- ADR 0007 — Markdown vault projection with bidirectional reconciliation.

Schema v2 made operations self-describing (syncable), so this crate also
hosts the Spike S4 convergence harness (`src/sync.rs`, `tests/s4_sync.rs`)
per the plan's instruction that S4 reuse the S2 model — see
[`../s4-sync/README.md`](../s4-sync/README.md) for that evidence.

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

## Evidence: Windows (Windows 11, MSVC, rustc 1.98, 2026-08-30)

Same suite, 30 tests (20 matrix + 10 S4 sync), 6 consecutive runs, no
flakes. The first Windows run failed wholesale and surfaced four
portability faults, fixed forward without weakening an invariant:

- **Directory fsync** — `File::open(dir)` is `ERROR_ACCESS_DENIED` on
  Windows: directories only open with `FILE_FLAG_BACKUP_SEMANTICS`, and
  `FlushFileBuffers` requires a writable handle. `fsync_dir` is now
  platform-split; durability is enforced on both platforms, not skipped.
- **File fsync via read-only reopen** — `FlushFileBuffers` also refuses
  read-only handles, so the `File::open(tmp)?.sync_all()` pattern used
  before renames failed identically. Replaced by `fsync_file`, which
  reopens for write.
- **Relative paths are a portable contract** — the vault and export walks
  built rel-paths with the host separator. On Windows every projected file
  then looked foreign to `reconcile` (all files became rename/import
  candidates), and `.gilune/local` slipped past the export skip-check, so
  device-local state travelled — a real invariant breach, caught by the
  suite. Walks now normalise rel-paths to `/` (`portable_rel`); stored
  `/` paths join back onto roots correctly on both platforms.
- **Fixture byte-exactness (repo, not code)** — `core.autocrlf=true`
  rewrote the v1 fixture's text files to CRLF on checkout, breaking the
  content hashes recorded in its `kernel.db`. `.gitattributes` now pins
  `fixtures/**` as `-text`.

Worth keeping: the reconcile failure mode was preservation-favouring — a
vault written with foreign separators produced import candidates and
quarantine, not corruption — but the export leak shows "device-local state
does not travel" only held per-platform until rel-paths were made portable.

## Honest gaps (not yet evidence)

- Process abort only: no torn-write / power-failure simulation below the
  filesystem API, and no fsync-reordering fault injection. A follow-up
  should add an I/O interposition layer or run on a fault-injecting
  filesystem before ADR 0002 is accepted.
- Migration is now covered (v1→v2, crash mid-step, against a fixture built
  by the real v1 binary), but only one migration exists; a materialised
  payload-rewriting migration remains untested.
- Single process; no concurrent writers or cross-process lease contention.
- Linux and Windows only; an Android run of this suite is deferred with
  the physical device (the S1 boundary itself is proven on the emulator).
- Timestamps in front matter are epoch milliseconds; the human-readable
  format is a vault-format decision, not settled here.
- Blob bytes stored once (in-vault); the copy-versus-reference measurement
  ADR 0007 asks for is therefore moot in this layout, but the layout itself
  still needs the shared-folder-sync safety review.

## Artefacts

- `vault-format-draft.md` — draft vault layout and front-matter schema.
- `tests/s2_matrix.rs` — the termination matrix and invariants, which double
  as the crash-state documentation.
