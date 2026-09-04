# Gilune — session onboarding

Gilune is a local-first, capture-first personal memory system: tap → speak →
done on the phone; an Obsidian-openable Markdown vault as the user-visible
store; a plugin-first microkernel underneath; devices sync by exchanging
immutable operations, LAN-first, servers optional. Product principles live in
`sources/README.md`; the architecture package in `architecture/` is the
authority — read its README first. The project was previously named Memory
Project, then Linoema; the permanent `memory`/`org.memory` contract
namespaces are deliberately project-neutral and must not chase the name.
(One deliberate exception, owner decision 2026-08-30: vault front-matter
*keys* are `gilune-*` — they are user-visible product surface — while the
identifiers in their *values* stay `org.memory.*`.)

## Ground rules (distilled — the constitution is the law, read it in full)

- Capture is preservation-first: saving never requires network, account,
  model, or structure; failure favours preservation.
- Sources are immutable; interpretations are separate artifacts with
  provenance. Never ingest an edit to a source as source history.
- The kernel owns invariants, not features. Domain code (tasks,
  transcription, …) is plugin territory: in the spikes it is quarantined in
  `spikes/s2-durable-capture/src/processors.rs` behind a narrow
  `ProcessorCtx`. Kernel modules must never name a domain type.
- At-least-once processing with idempotent handlers; conflicts are preserved
  as multiple heads — never wall-clock last-write-wins.
- Openness is a goal (constitution rule 13): third-party tools reading and
  editing the vault is supported use.
- Decisions are ADRs (`architecture/decisions/`) validated by spikes
  (`architecture/spikes/README.md`). Spikes are disposable evidence; every
  spike README records evidence AND honest gaps — keep that honesty. When a
  question is answered, update `architecture/08-open-questions.md` and the
  decision map: an answer becomes a contract change or an ADR.

## Layout

- `architecture/` — constitution, contracts (01–08), engineering
  conventions (09), ADRs 0001–0012, spike plan, prior-art survey.
- `sources/` — product principles (the "why").
- `spikes/s1-kernel-boundary/` — Compose ↔ UniFFI ↔ Rust boundary spike:
  diagnostic kernel (`kernel/`, cargo test), Compose shell for desktop +
  Android (`shell/`, `gradlew :composeApp:desktopTest` and
  `:composeApp:connectedDebugAndroidTest`); bindings are generated per
  target from that target's own binary (see its README).
- `spikes/s2-durable-capture/` — Rust kernel-core spike: durable capture,
  events/jobs, Markdown vault projection and reconciliation, schema
  migration, plus the sync module and S4 harness. `cargo test` runs the
  crash matrix and the sync convergence scenarios (~30 tests).
- `spikes/s3-plugin-contract/` — WIT processor contract, wasm component
  guest, capability-checked Wasmtime host. Needs
  `rustup target add wasm32-wasip2`; then `cd host && cargo test` (the
  tests build the guest themselves).
- `spikes/s4-sync/README.md`, `spikes/s5-distribution-policy/README.md` —
  evidence reports for sync and store policy.

## Status (as of 2026-08-30)

Green on Linux AND Windows: ADR 0002/0004/0007 (S2 crash matrix incl.
migration against a real v1 fixture), ADR 0003's desktop half (S3: contract
parity, sandbox containment, ~1 ms calls), ADR 0005/0008 semantics over a
shared-folder relay (S4: convergence, gaps, corrupt ops, chunked blobs,
tombstones). The Windows runs (2026-08-30) found and fixed four
portability faults — Windows fsync semantics (backup-semantics dirs,
writable handles), rel-paths normalised to `/` as a portable contract,
fixture byte-exactness via `.gitattributes`, and S3 harness timing windows
that included the one-time guest build; each spike README's Windows
evidence section has the details. S5 policy matrix drafted from live
sources. Spike S1 (ADR 0001, the Compose/Kotlin ↔ Rust boundary) is
**closed provisionally** (2026-08-30): desktop green on Windows, Android
green on an x86_64 emulator (fault list, instrumented suite,
symbolication, packaging, dual-ABI build); ADR 0001 accepted for v0 with
the mid-range-device budgets and first ARM64 execution **assumed until
the owner acquires a device**. ADR 0009 (licensing; MIT-only since
2026-09-01) and ADR 0010
(downloadable store plugins via the sandboxed runtime; test submission
gates promises) are ratified. **Next: application-level engineering — see
`architecture/09-engineering-conventions.md` and the vertical slice.**

## Next steps

1. **The vertical slice** (GO ratified 2026-08-30, review closed).
   Production code lives in `kernel/` (Rust workspace), `contracts/`
   (public contracts), and `app/` (Compose Multiplatform, from M3);
   spikes stay disposable reference. Milestones:
   - ~~M1 kernel core~~ — done 2026-08-30: schema v1, capture commit +
     recovery, at-least-once jobs, per-device operations; 6-point crash
     matrix green (`kernel/crates/gilune-cli/tests/crash_matrix.rs`).
   - ~~M2 vault~~ — done 2026-08-30: schema v2, deterministic projection,
     bidirectional reconciliation (edits→revisions+ops, deletes→proposals,
     renames-by-id, quarantine, imports); `gilune-*` front-matter keys.
   - ~~M3 boundary + shells~~ — done 2026-08-30: full FFI surface
     (streamed capture crosses chunk-by-chunk), `app/` Compose project,
     desktop + Android apps boot and pass the shared boundary suite
     (6 desktop + 6 instrumented). Packaged desktop app measures
     **~1.4 s cold / ~1.5 s warm to window** (double-click launch;
     earlier "~4 s" was gradle+probe overhead — and PowerShell
     `Start-Process` stdio wiring makes the jpackage launcher fail to
     show a window at all, so measure with `cmd /c start`). Mic adapters
     (javax.sound / AudioRecord → WAV) are code-complete and
     WAV-encoder-tested; microphone recording verified by the owner on
     Windows desktop (2026-08-30).
   - **Perf review (future)**: desktop startup baseline is JVM+Compose
     bound at ~1.4 s; if it ever needs to shrink, the avenues are
     AppCDS/AOT-cache and deferring kernel open off the first frame.
     Android cold start on real hardware rides with the ADR 0001 device
     budgets.
   - ~~M4 transcription~~ — done 2026-08-31. M4a: processor contract in
     `contracts/processor/` (WIT + Rust trait mirror), schema v3
     (processing_runs, provenance), kernel work loop with narrow
     ProcessorCtx, deterministic outputs (derived IDs), crash-tested
     (mid-output-commit abort → exactly-one on retry). M4b: local
     Whisper (whisper-rs 0.16, `whisper` cargo feature, desktop dll
     only; Android captures and lets a coordinator transcribe). Model
     auto-downloads to `%LOCALAPPDATA%\Gilune\models\ggml-tiny.bin`
     (~75 MiB, pre-seeded on this machine). End-to-end proven: TTS
     speech wav → capture → job → Whisper → transcript object →
     `Transcripts/*.md`. Build needs LLVM libclang
     (`scoop install llvm cmake`; LIBCLANG_PATH to scoop llvm bin).
   - **Review items (open)**: (1) standalone double-click launch of the
     packaged exe behaved inconsistently under automated probes while
     gradle-exec and the owner's manual launch worked — re-verify by
     hand on the current build; probes must not kill processes by name
     (the owner may be running the app). (2) ~~multi-process safety~~ —
     **multi-instance on one vault is supported use by owner decision
     (2026-08-31)**, torture-tested (20 concurrent capture processes
     racing project/reconcile/work; 20 clean runs). Fixes: SQLite
     busy_timeout, unique projection temp files, both-constraint
     upsert, age-gated stage sweeping (recovery can't steal a live
     capture; `force_stage_sweep` for tools), idempotent promotion,
     bounded rename retry — and `ids::short()` now uses the UUIDv7
     random tail, since the timestamp prefix collided filenames for
     same-millisecond captures. (3) whisper.cpp logs spam stderr;
     silence via log hooks.
   - **M5 LAN sync — ADR 0019 ratified 2026-09-02 (build authorised;
     Accepted only at its 16-point gate), staged M5a/M5b/M5c.** Owner
     constraints: transport only (0008's headless agent = later
     round); FULL replica; phone syncs on open + periodic unmetered
     background; pairing = 6-digit SPAKE2 code AND desktop-QR both in
     v0. Design: DNS-SD `_gilune._tcp` (Rust mdns-sd desktop /
     NsdManager adapter Android; cached-address-first, manual
     host:port fallback), SPAKE2→Noise XXpsk0 via snow (both crates
     UNAUDITED — recorded), identity keys OUTSIDE the vault
     (DPAPI/Keystore wrap; vault meta.identity_pub makes re-enrolment
     mechanical), gilune-kernel::operations + gilune-sync crate split
     behind a Transport trait, raw envelope bytes stored for future
     signatures, revocation mid-session at op boundary + third-replica
     refusal by origin device. Gate C needs the owner's real device
     (like MS2). **M5a DONE (2026-09-03, one Opus agent)**: schema v5
     (sync_frontiers, integrity_alerts UNIQUE(kind,subject),
     operations.envelope raw BLOB, events.origin_device,
     blobs.origin, meta.op_seq deleted — MAX(op_seq) per device IS
     the counter), real-v4-binary fixture migration test (worktree
     build; adopted history applies into a fresh replica to the same
     digest); canonical-JSON versioned codec, kinds reconciled ONCE
     (create-source/object→object-created, create-revision→
     object-revised; unrebuildable rows→legacy-operation keeping the
     sequence DENSE); 37 portable vectors in kernel/vectors/
     (valid ones re-encode byte-exact; generator #[ignore]d — format
     changes must be an act); apply = one txn/op, halt kinds
     sync-gap/-corrupt-operation/-unsupported-version/-limit-exceeded
     (alerts raised OUTSIDE the txn; successful apply clears them;
     unknown kind applies-and-forwards, no halt); **add_revision now
     supersedes only the heads it saw and NAMES them in the op**
     (readers take one rule: newest_head_first created_ms then
     revision_id; recent_sources rewritten deterministic; projection
     keeps gilune-conflicted); limits.rs one home (env 2MiB, blob
     512MiB, free-space margin 64MiB via new dep fs4, 10k ops +
     256MiB/session; spike's attacker-sized Vec::with_capacity
     retired — staged length IS the resume point); gilune-sync:
     Transport/Session split (+devices()/have_blob() for the pull
     side; send_envelope returns BlobNeed so the crate never parses
     payloads), loopback = real TCP on a second handle. Gate A green:
     all ten S4 scenarios vs PRODUCTION kernel + reorder/dup/
     restore/offline + vectors + every bound (visible halt, nothing
     past fault, clean resume) + 4 apply crash tests. Suites: cargo
     150/0, desktop 436/0, instrumented 610/0/1(a03); dll restaged,
     both ABIs rebuilt, bindings byte-identical (SyncHalted maps to
     existing Invalid — shells learn of halts from alerts, M5c).
     **M5b DONE (2026-09-03, one Opus agent + two shell sub-agents,
     agent-coordinated)**: identity = X25519 minted on first use,
     device-local {device_id}.identity.json in a shell-nominated root
     (%LOCALAPPDATA%\Gilune\sync / filesDir/sync; Debug withholds the
     private half) — KEYED BY VAULT device_id, an accepted refinement
     recorded in 0019's Amendments (one identity per machine+vault;
     same-machine copy not detected = rule-8-excluded, stated);
     re-minting seeds the old device's frontier or the first session
     with the original halts. KeyWrap = uniffi foreign trait (no
     adapter ⇒ unwrapped WITH the reason in the file + keyProtection
     "none" surfaced): desktop DPAPI user-scope via direct Crypt32
     JNA (UI_FORBIDDEN, LocalFree, round-trip self-test before
     offering; 6 real-adapter tests), Android Keystore AES-256-GCM
     seal (no on-device round-trip test — gap). §4 re-enrolment table
     exact + visible notice. Pairing: SPAKE2 (role identity strings
     listener/joiner so reflection fails) → Noise XXpsk0
     (25519/ChaChaPoly/BLAKE2s; snow trimmed to named ciphers); pair
     secret from the handshake hash (never transmitted); credential
     kind bound into the prologue (QR→code downgrade fails); QR
     payload GILUNE-PAIR|1|secret|fingerprint|host:port (15 malformed
     shapes rejected). Default-deny = psk0 property: listener
     trial-decrypts msg1 against held pair secrets, unpaired callers
     get SILENCE (wire-tap tests: no payload, no static key on the
     wire). M5a framing refactored behind Channel — one serve/one
     exchange loop for loopback AND encrypted. Revocation deletes the
     pair secret; mid-session closes at op boundary; third-replica
     refusal is a kernel apply property via a roster-projected
     refusal list. Rate limits: per-peer doubling backoff; windows
     single-use/3-min/5-attempts-then-abandoned-with-secrets-cleared.
     FFI: the nine 0019 §6 methods + configure_sync (shell owns the
     identity path; syncSetup wired BEFORE openVault in both shells);
     bindings byte-identical AGAIN; dll restaged; ABIs 5.24/5.36 MB.
     Five bugs found by gates/GUI, fixed in code: joiner-paired-
     before-listener-committed (confirmation frame), revocation check
     before the blocking read, source-port-recorded-as-address
     (listening port travels in the encrypted greeting), GUI cleared
     the code before the IO thread read it, peer list never
     refreshed. Gate B 19/19 ×3 runs + LIVE two-OS-process pairing
     (note captured on A projected as Markdown on B) + GUI pair/
     revoke/re-pair + emulator Sync screen with live camera preview.
     Suites: cargo 217/0, desktop 442/0 (+6 DPAPI — the shell had
     shipped untested), instrumented 610/0/1 fresh-boot. Gaps:
     Sync-screen instrumented tests absent; CAMERA dialog never
     watched (installed -g); listener serves one peer; restarted
     device takes a new port (no discovery till M5c); Ctrl+N notes
     don't sync (pre-existing M2 import gap — surface in M5c?).
     **M5c DONE (2026-09-03, one Opus agent + one Android sub-agent;
     the sub-agent's close-out report reached MAIN when its parent
     reply bounced — read both)**: discovery = _gilune._tcp behind
     PeerDiscovery (desktop mdns-sd 0.21, daemon lazy; Android
     NsdManager adapter Discovery.kt — both API paths, SERIAL
     resolve pump (<34 answers concurrent resolves with
     ALREADY_ACTIVE), own executor, main-thread refusal, TXT = v/fp
     ONLY, no MulticastLock + a comment saying never add one;
     advertise() waits for onServiceRegistered so advertising is
     measured). TWO SCHEDULED MEASUREMENTS ANSWERED: mdns-sd
     COEXISTS with the Windows 11 responder on UDP 5353; 100 ops =
     1.21 s, cold browse 2.51 s, together 3.72 s of the 5 s budget
     (loopback-labelled) — browse costs 2× the exchange, so
     cached-address-first is a REQUIREMENT, pinned by a
     browse-counting test. Port stability: fixed default 51837 →
     ephemeral if taken, published everywhere; peer's true port from
     the encrypted greeting. Fallback chain proven: cached →
     discovery → typed → words (three diagnostics incl. AP
     isolation). Live-run defects fixed+pinned: mDNS resolved peers
     to 127.0.0.1; incompatible-version peer read as "switched off";
     disable froze settings 60 s joining sessions (now ends them at
     an op boundary like revocation). Lazy routable bind at
     set_sync_enabled(true) — firewall prompt OBSERVED (gate 13).
     Concurrent listener thread-per-session cap 8. Alert surface:
     GiluneStatus gains blobs_awaiting+alerts, integrity_alerts()
     FFI, banners both shells, all seven kinds in user words
     (sync-unknown-op-kind worded as NOT damage). Scheduling:
     foreground on open/resume behind shouldSyncOnForeground;
     PeriodicWorkRequest 15 min UNMETERED+batteryNotLow no-charging
     unique KEEP; never an FGS. **androidx.work:work-runtime:2.10.0
     added — the R0 InitializationProvider defect does NOT reproduce
     for a DIRECT app dependency** (provider merges into the APP
     manifest; test APK carries none; real enqueue + full suite
     prove it — the AAR-side exclusions stay). Sub-agent finding,
     coordinator decision: sync-enabled was process-local — now
     PERSISTED device-local beside identity/roster (never the vault:
     a copied vault must not open a port), resume_sync in the shared
     seam. docs/guide/sync.html + screenshot + nav across all
     guides. Gate C: 11/12/15 proven two-process real-socket real-
     Noise (real-device half AWAITS THE OWNER'S PHONE), 13 observed,
     14 real-WorkManager-asserted, 16 measured loopback-labelled.
     Suites: cargo 247/0, desktop 469/0, instrumented 647/0/1;
     clippy clean; ABIs 6.20/6.34 MB. Gaps: NsdDiscovery never saw a
     second device; sub-34 resolve path never executed; revoke-last-
     peer leaves the KEEP schedule (worker no-ops); worker retry
     unexercised; IPv6-only peers undiscovered by design.
     **M5 delivery complete pending hardware: ADR 0019 stays
     Proposed until gate C's real-device six run on the owner's
     phone; 0005 (LAN half) and 0008 (needs the headless-agent demo)
     likewise. THREE WINDOWS FIREWALL DIALOGS from the routable-bind
     tests were left OPEN on the owner's desktop — allow/deny is the
     owner's click, never an agent's.**
   - **Post-delivery review + fix round (2026-09-03, Fable 5.1
     coordinating: 8 finder angles → 7 verifiers → 3 fixers + 1
     integrator, all Opus)** — `/code-review high 16a88f4..HEAD`
     over the Moonshine + MS1 + M5 range (~26k lines): 27 candidates
     verified, 25 CONFIRMED/PLAUSIBLE, 2 refuted. The defects clustered
     exactly where ADR 0019 said the risk was — three-replica apply
     ordering (per-device seqs have NO causal parents; pull orders
     streams by device_id = UUIDv7 = vault age, so a referent can
     arrive AFTER the op that names it), listener hardening, and the
     pairing address — plus one data-loss seam. KERNEL fixes (all with
     falsified tests): a synced tombstone can no longer delete a
     LOCALLY EDITED note (reconcile keeps the file as an import + alert
     `vault-deleted-note-edited`; withdraw_tombstoned only removes
     bytes equal to what the kernel would render NOW); absent referents
     (object-revised/tombstone/supersedes before their object/head)
     halt ONE stream with `sync-awaiting-referent`, frontier unmoved,
     resume next pass (previously: FK error killed the session with no
     alert / silent divergence / head resurrection — s4 scenario at
     :362 had DEPENDED on the silent tombstone drop); SyncHalted is
     `{kind, subject, reason}` so malformed/path-escaping envelopes are
     `sync-corrupt-operation` not "limit exceeded"; zero-byte blobs
     complete (an empty recording used to wedge sync with that peer
     forever); run_exists restored BEFORE process() (post-commit crash +
     failing retry no longer quarantines a delivered job); run loop
     claims ONLY its own processor ids (JobFilter::Among — an
     unregistered handle/`gilune-cli work` used to burn external
     processors' attempts); `![[name]]` placeholder only for in-transit
     sync blobs; NotFound vs other read errors distinguished; per-chunk
     fsync removed. SYNC fixes: pre-handshake timeout on accepted
     sockets + keepalive + silent callers count as rate-limit failures
     (8 silent TCP connects used to wedge the listener till restart);
     routable address published instead of 0.0.0.0 (QR/typed pairing
     would have FAILED on real hardware — `service::publishable`,
     if-addrs via existing lock edges); refusal set is DURABLE vault
     state (`refuse_device`/`unrefuse_device`/
     `reconcile_refused_devices`; `set_refused_devices` REMOVED —
     re-enrolment used to wipe revocations + alerts); outbound sessions
     check permission at op boundaries (revocation/sync-off stopped
     only inbound before); roster reload-merge under a `.lock` sidecar
     (activity + worker each hold a SyncService); one browse per
     sync_now; advertise retried. SHELL fixes: a provider registered
     while another transcriber is active registers DISABLED
     (`applyOneTranscriberRule` — both transcribers were enabled by
     default on EVERY fresh install and R1→R3 upgrade, queueing orphan
     jobs per recording); Android backup/D2D EXCLUDES filesDir/sync
     (data_extraction_rules + backup_rules — a transferred identity the
     Keystore can't open left sync permanently "not set up") + "Reset
     sync on this phone" recovery; one shared alert table
     (jvmShared/SyncAlerts.kt, `alertIsDamage`, both new kinds worded
     NOT-damage); provider failures surfaced (`ProcessingPass`,
     `GiluneLog` hook); desktop Sync section loads alerts on entry;
     discovery start-wait no longer eats the browse budget; QrScan
     disposed-flag (`QrSession`); model downloads only if caption on OR
     moonshine active + connectivity callback re-evaluates
     WaitingForWifi; Keystore class-level lock (+ the on-device
     round-trip test M5b lacked); MS1 rig restores a keyboard after a
     hard-killed run; `QrAnalyser`; resumeSync off the vault-open path;
     project() only if applied>0; SyncWorker gates on persisted
     `wanted`. ADR 0019 + 0018 Amendments record every rule change;
     08-open-questions closes three answered questions. Below-cap
     cleanup left as debt: blobs::finish_blob re-implements
     capture::promote; external.rs third digest reader; sealed-file
     envelope written 4×; handshake 4-tuple; TranscriptCollector vs
     foldCaption + event flattening duplicated; `Store::status()`
     counts PARKED jobs in jobs_pending (shell shows a sentence; a
     `jobs_parked` field is the real fix). INTEGRATION PROOF (rebuilt
     dll 5.55 MB restaged, ABIs 6.26/6.40 MB, bindings BYTE-IDENTICAL
     on both targets): cargo 273/0/2-ignored + clippy silent, desktop
     504/0 (forced --rerun-tasks — gradle does NOT treat the staged dll
     as a task input, so an unforced run re-reports the stale-dll
     result), instrumented 733/0/1 fresh-boot with the repackaged APK,
     zero MS1 flakes.
     LESSON: S4's scenarios never crossed device streams out of causal
     order — every future sync scenario must include a third replica
     that pulls the streams in the WRONG order.
   - **Second review + fix round two (2026-09-03, Fable 5.1
     coordinating; 8 seeded finder angles → 7 verifiers → 3 fixers +
     integrator, Opus)** — `/code-review high a2607eb..HEAD` over the
     FIRST fix round + every set-aside candidate: 35 verified, 33 real,
     2 refuted. VERDICT ON ROUND ONE: it fixed what it targeted and
     introduced ten regressions, each tested only against its brief's
     scenario and not the adjacent one. **RULE ADOPTED: a fix ships
     with its SECOND-ORDER test (the case one step past the obvious)
     or it is not done — falsified by reverting the fix.** Regressions
     fixed: awaiting-referent had NO exit when the referent's device
     was REVOKED (innocent stream wedged forever, worded "nothing is
     wrong") → naming ops now carry `object_origin_device` +
     `supersedes_origin` (additive codec keys, 2 new vectors, 37
     byte-identical) and an absent referent is Awaited / Refused
     (forward-only, `sync-referent-refused` not-damage, withdrawn if
     re-admitted) / Uninterpreted (forward-only); `canonical_sha`
     re-render orphaned every revise-then-delete note → TOMBSTONE NAMES
     THE HEADS IT SAW, withdraw iff disk==recorded AND every local head
     named (legacy tombstones: projection revision not an external
     edit); `publishable` picked the numerically LOWEST IPv4 (VPN/
     Hyper-V over LAN) → EVERY routable candidate published+ranked,
     QR field five `h1,h2,h3:port` (≤4 hosts; single host byte-
     identical), joiner dials in turn 2 s each, `addressing.rs` one
     classifier both sides (SameSegment<Routable<LinkLocal<Loopback;
     unusable DROPPED — discovery's dialability had ranked 0.0.0.0/
     224.x ahead of the LAN); roster reconcile RE-REFUSED a re-paired
     device each launch → `previous_device_ids` (identity records
     ancestry ≤8, encrypted greeting carries, pairing withdraws every
     name, reconcile never re-adds a Paired peer's names; nothing adds
     a refusal from a greeting); `syncNeedsProjection` ignored
     blobsCompleted (placeholder body forever) → both counted;
     render→None hid unreadable local txt silently → `vault-content-
     unreadable` (not damage, self-clearing); `vault-deleted-note-
     edited` never cleared → observed resolution per reconcile (file
     gone / words back as new; no schema — nothing turns an import
     into an object yet); `sync_now` ignored persisted `wanted` →
     honours it, outbound flag initialised from it; incomplete()
     store-wide-sticky + max() merge → per-SESSION pending, sum;
     `finish_blob` vs `promote` had DIVERGED (promote left the stage →
     lost+found duplicate under multi-instance) → one `promote`
     pub(crate), correct branch; Moonshine per-job failJob path read as
     "0 transcribed" → `ProviderPass` carried through both handles.
     FFI ADDITIONS (bindings CHANGED, android==desktop):
     `GiluneStatus.jobsParked`, `IntegrityAlert.damage` (kernel
     `alerts::is_damage` is THE classification — shells' kind table
     dropped; `AlertKindCouplingTest` reads alerts.rs at desktopTest),
     `GiluneException.IdentityUnreadable` (typed; a sealed roster that
     won't open raises it too; the "Reset sync" prose matcher is GONE),
     `registerPluginDisabled` (opt-in provider never enabled even
     briefly; register-then-disable survives only for both-on upgrades,
     which get a persisted one-time notice), `ProposedOutput.
     expectedHead` + `ClaimedJob.subjectHead()` (kernel CAS — a moved
     head refuses the revise; Moonshine passes it), `PairingOffer.
     addresses` + `listening_addresses`. Cleanup landed: one
     `SealedFile` envelope (reads `sealed`/`payload`/`secret` — old
     installs keep keys+pairings), `Handshaken::establish` (no flow can
     forget the timeout swap), one streamed `digest_of`/`digest_reader`
     (verify/find_stage/BlobStream — no whole-blob reads), halt_transfer
     + blobs_awaiting_count + sync_frontiers deleted, `external-
     interpret`, `foldCaption(retain)` shared by the collector,
     `captionEventOf` one flattener, VaultFiles byte readers shared,
     LuminanceScratch reused frame buffer, wordlessRecordings off
     `preview` (one query not N), device_id + refused-set cached per
     pull (refused keyed on raw meta text so a second process's
     revocation is never missed), alert clearing 10 DELETEs → 1
     existence SELECT, project() skips record_projection when
     sha+path+revision unchanged, pairing window refresh on IO every
     3rd tick. Docs: sync guide gains Reset (Android-only), the
     not-a-problem messages, multi-address section; plugins guide
     model size corrected (57 MiB). RIG: paired-device test port band
     moved 52000–59999 → 20000–27999 (old band inside the OS ephemeral
     range — a client socket could squat a restarting device's port,
     1-in-15 flake); gate_c's 5 s budget test is load-sensitive while
     agents build — judge it on a quiet machine. INTEGRATION PROOF
     (rebuilt dll + both ABIs; bindings +26 lines, android==desktop):
     cargo 334/0 + clippy silent; desktop 527/0 (--rerun-tasks);
     instrumented 769 = 768/1-failed/1-skipped on a FRESH boot with
     the failure being Ms1ImeSpikeTest's 2000 ms keyboard-teardown
     row, green 32/32 class-alone on the same emulator minutes later
     — that row is load-sensitive EVEN ON A FRESH BOOT when the host
     is compiling (it bounds a platform behaviour in wall time);
     the shell fixer's earlier run of the identical tree was 769/0.
     Two integration-agent API 500s mid-run; the coordinator finished
     steps 6–8 by hand (a gradle attach during an adb daemon restart
     fails with "No services running on device" — re-run). Documented gaps: a
     pre-key naming op whose refused-origin referent never arrived
     still halts (origin unknowable); legacy tombstone fallback can't
     see an edit made elsewhere; jobs with an enabled plugin but no
     engine in THIS build are unclaimable and uncounted (no row records
     the absence); `blobsAwaiting` still read by no shell.
     Gaps: GiluneStatus lacks blobs_awaiting/alerts (deliberate, no
     FFI change — `gilune-cli alerts` meanwhile, M5c wires shells);
     per-peer establishment rate limits → M5b (no peer identity yet);
     malformed blob declaration files under sync-limit-exceeded
     (imprecise); no duplicate-JSON-key vector; loopback serves one
     connection; per-device sequences only, no causal parents (S4's
     inherited gap). **Next: M5b** (identity, SPAKE2/Noise pairing,
     KeyWrap adapters, roster, revocation, QR, FFI surface) then M5c
     (discovery, firewall UX, live exchange, docs).
     RIG NOTE (2026-09-03): MS1 determinism holds on FRESH boots
     only — after ~90 min of accumulated emulator load
     Ms1ImeSpikeTest degrades (2000 ms teardown assertion, activity
     lifecycle timeout); class alone 4/4, full suite clean after
     emulator restart. Restart the emulator before judging MS1.
   - **M6 desktop shell** — design session held 2026-08-31; ADR 0011
     (shell regions, fixed five-slot list, plugin-owned UI, everything
     but the vault projection removable) and ADR 0012 (the Markdown
     buffer is always the document; rich presentation renders it) are
     ratified. Order: ~~(a) kernel plugin registry~~ — done
     2026-08-31: schema v4 (`plugins`, `plugin_grants`), declarations
     live beside their implementations
     (`vault::plugin_descriptor`, `whisper::descriptor`), job delivery
     gated on plugin state, removal keeps a tombstone so bundling is
     not consent, grants never inherited across an update or a
     removal, FFI surface (`plugins`, `register_plugin`,
     `set_plugin_enabled`, `remove_plugin`, `grant_capability`, …)
     and `gilune-cli plugins`. Off means nothing is queued and waiting
     jobs are parked; switching on resumes them but never backfills.
     ~~(b) shell restructure~~ and ~~(c) editor E1~~ — done
     2026-08-31: rail with a collapsing tree, creation, rename, and
     delete-that-answers-its-own-tombstone-proposal; document pane
     with Rich/Raw, properties strip, autosave and the stale-buffer
     guard; capture bar across the foot with the mic drawn only when
     a plugin contributes `capture.actions`; settings surface (vault
     with a folder picker, plugins with permissions/events/
     contributions and enable/remove/allow, appearance, about);
     desktop palette and density instead of stock Material purple.
     Voice capture is its own shell-declared adapter, so removing
     transcription leaves recording intact. Markdown rendering uses
     `org.jetbrains:markdown` in shared code.
   - **M6 second pass (2026-08-31, Fable, screenshot-validated)** —
     the first shell build shipped unvalidated and missed the design;
     rebuilt against the wireframe and verified by launching probe
     instances and reading screenshots. Editor stage **E2 is done**
     (owner: "I expect to be able to edit the markdown in the app"):
     `MarkdownEditTransformation` styles the live buffer via
     VisualTransformation with identity offsets — styling can never
     change bytes (tested). Segmented Edit|Read; notes open editing.
     Top bar gained working jump-to search (Ctrl+P) and shortcuts
     (Ctrl+Shift+C focuses capture, Esc closes settings). Tree: inline
     naming (no modals), icons, Captures inbox count, context menu
     incl. Reveal in Explorer. Out-of-app deletions surface as a
     banner (restore/delete). Settings follows the Obsidian shape the
     owner asked for: manager section with switches plus one nav entry
     per plugin opening its own screen (per-permission Allow/Revoke
     rows, events, contributions, Remove). Consent policy at the host:
     a bundled plugin's LOCAL capabilities are granted once on first
     install (FFI test `builtin_grants.rs`); `network.request` always
     waits for an explicit yes; nothing re-grants after revoke or
     removal. **Next in M6**: E3 only after a spike; command-palette
     slot; Android shell parity (bindings are STALE — regenerate from
     an NDK build before compiling androidMain). M5 remains the last
     milestone of the vertical slice.
   - **M6 third pass (2026-08-31, agent team: four Opus implementers,
     Fable managing/validating; screenshot-validated)** — ADR 0013
     ratified and implemented: processor outputs may **revise their
     subject**; transcription lands in the capture note (body = text
     only, audio linked via `gilune-blob`); `Transcripts/` is gone and
     old vaults merge once on open (`transcript_merge`, flag
     `transcripts_merged_v1`; proven on a copy of the owner's vault).
     **Attached captures** (`begin_attached_capture`) commit like any
     capture but never project — the dictation substrate. App: editor
     mic **dictates at the cursor** (attached capture → whisper →
     `object_text` → insert at caret); capture-bar mic = new note from
     speech, opening the note it makes. Checkbox E3 slice: `- [ ]`
     renders as a glyph off the caret's line, raw on it, click toggles
     (offset-mapped transformation, exhaustively tested). Editor buffer
     is body-only (`splitNoteRaw`/`assembleNote`, byte-lossless,
     tested) with `TextFieldValue`; click-anywhere-to-edit with a UI
     regression test (compose uiTestJUnit4); Properties window (⋯ menu;
     `gilune-*` locked, custom pairs editable); tree nests attachments
     under their notes hidden until expanded, orphan non-md hidden;
     inline rename (co-renames attachments by stem); neutral
     surfaceContainer colours (dialogs no longer purple). Kernel bug
     fixed with test: `ingest_external_edit` used to REPLACE the
     document, stripping `rel_path` (a voice note lost its audio link
     on first edit) — now merges text into the head document. Owner
     added the logo (`gilune-logo.png` window icon, `gilune.ico`
     packaged). Suite: ~60 kernel tests + 22 desktop (incl. 1 UI).
     **Open after this pass**: dictation not yet voice-tested end to
     end by a person; imports (front-matter-less files) recorded but
     surfaced nowhere; `- []` without a space is not a task marker
     (matches Obsidian); deleting a note leaves its wav on disk
     unlisted (correct — preserved source); Android bindings STILL
     stale.
   - **M6 fourth pass (2026-08-31, agent team round two)** — ADR 0011
     amended (owner): desktop capture bar REMOVED; capture-first is
     Ctrl+N (Untitled note, title focused/selected, Obsidian naming
     via `untitledName`/`createUntitledNote`) + top-bar mic
     (new-note-from-speech; still plugin-gated). Rail has Files/Search
     panels (VSCode shape); **Ctrl+F** = vault search, Ctrl+P =
     quick-open. `Search.kt` (jvmShared): DSL — bare terms AND,
     "phrases", -negation, file:/path:/content:, [key:value]/[key]
     metadata; `SearchOperators` registry is the plugin-extensibility
     seam (proven by a registered tag: operator test); no OR yet.
     Editor: Obsidian-style editable title (rename-on-commit via
     `renameNote`, co-renames attachments; body field carries
     testTag("note-body") so the title keeps real semantics);
     properties dialog restyled to the settings idiom (field borders
     full `outline` per owner feedback); checkbox hover shows a hand
     cursor (`pointerHoverIcon` needs overrideDescendants=true — the
     inner text field's I-beam wins otherwise). Whisper speed
     (measured, release, ~14s clip): default 4 threads → 8 =
     12.2s→7.1s; explicit language skips the detect pass = 7.1s→4.2s;
     n_threads always set, language via transcription plugin settings
     ("Spoken language" field on its plugin screen; default auto,
     takes effect next launch). **ADR 0014 (tabs + split panes)
     ratified as proposed** — stage M (tabs in one pane) is the next
     shell milestone; then M+1 explicit split + layout persistence,
     M+2 drag. Probes gained search:<q> and props:<rel>.
   - **M6 fifth pass (2026-08-31, agent team round three: model →
     two parallel UI agents, Fable coordinating/validating;
     screenshot-validated incl. a persistence relaunch)** — ADR 0014
     stages M AND M+1 shipped; M+2 (drag) stays deferred. Model:
     `Tabs.kt` (desktopMain; leaf type is `TabPane` — `Pane` was taken
     by SettingsPane's nav) holds the pane tree, `TabsState` (open
     dedupe, openToSide MOVES the tab, close refusal, dissolution,
     `label()` stem disambiguation, serialise/restore), and
     `flushEditor` — the guarded write extracted from NoteEditor so
     deactivating a tab flushes through the same path; 23 tests.
     Split-right on a single-tab pane is a no-op with a message (one
     buffer per note makes split a move). UI: `Panes.kt` PaneHost +
     tab strip (dot↔× in one fixed slot, error colour when stale,
     middle-click close, context menu, overflow chevron); the active
     PANE is named by a primary top rule on its front tab, drawn via
     drawBehind because inside horizontalScroll fillMaxWidth is a
     no-op (infinite constraints — cost one screenshot round). Shell:
     Ctrl+Tab/Shift+Tab, Ctrl+W, Ctrl+1…9, Ctrl+\; status line moved
     to the window scaffold; per-vault layout in Prefs
     (`tabs-<pathhash>`, save skipped over 7500 chars); window close
     and vault switch flush every tab then save. Tree: Open to the
     side, rename/delete notify tabs (folder rename walks contained
     notes so their tabs follow). Alignment seam fixed:
     `PANE_HEADER_HEIGHT` (36.dp, Tabs.kt) pins the rail header AND
     each strip so the divider runs unbroken (owner screenshot).
     Probes: `note:` now takes a ;-list, `split:<a>;<b>`, and any
     unknown probe value (e.g. `restore`) opens a probe-titled window
     showing only the persisted layout. **Probe runs persist layout —
     never point one at the owner's vault.** Known consequences:
     "Save now" on a clean buffer writes nothing (single write path);
     search results have no Open to the side yet; suite ~119 desktop
     tests (2 UI) + 23 model.
   - **M+2 drag (2026-08-31, same day: one Opus implementer, Fable
     validating with real scripted mouse drags + screenshots)** — the
     owner waived the ADR's Compose-spike gate and added within-strip
     reorder (both recorded in ADR 0014's Amendments). Model:
     `moveTab(tab, from, to, index)` (same-pane reorder never flushes;
     cross-pane activates the moved tab and flushes the displaced one
     through select's path) and `splitWith(tab, from, target)`
     (splitRight's surgery extracted to `splitOff`; identity drop
     returns false). Gesture in Panes.kt: press still selects, ~6.dp
     slop turns the held press into a drag inside the SAME TabChip
     pointer loop (no competing pointerInput); `TabDrag` holds a
     bounds registry (panes/strips/chips via boundsInRoot) and the
     live session; drop targets in order: strip gap (2dp caret) then
     a pane's outer right quarter (12% primary tint) then nothing.
     Drag image, caret and tint all render in the PaneHost root
     overlay — never laid out inside the horizontalScroll strip
     (infinite constraints). Edge auto-scroll on overflowing strips
     (read-verified only: a test would deadlock waitForIdle). Suite
     137 desktop tests; live validation: cross-pane move, reorder,
     and edge-split each performed with a real mouse drag via
     scratchpad drag.ps1 (SendInput on the "Gilune probe" window
     only) and screenshot-confirmed, incl. mid-gesture affordances.
     Tear-out and the four-edge grid remain out of scope.
   - **Divider resize + collapsible rail (2026-08-31, one Opus
     implementer, Fable validating live)** — ADR 0014 amendment (pane
     weights drag) and ADR 0011 amendment (rail resize/collapse).
     Model: `TabsState.resize(split, divider, delta)` clamps to
     MIN_PANE_WEIGHT 0.15 with partial moves; weights already in the
     layout codec so resizes persist for free. UI: shared
     `DragDivider` in Panes.kt — 1.dp hairline, 9.dp grab band via
     requiredWidth + **zIndex(1f)** (without it PaneView's
     Initial-pass pointerInput eats the overlap), AWT E_RESIZE
     cursor, live resize with a 160.dp px floor. Rail: width
     170..480dp persisted (`Prefs.railWidth`), collapse via top-bar
     Menu toggle / **Ctrl+B** / dragging shut (raw travel <120dp on
     release; reopens live crossing 120dp outward, so the mouse alone
     recovers it); collapse remembers the arranged width; Ctrl+F
     un-collapses then waits two frames before focusing (field
     doesn't exist until the rail composes). Suite 144 desktop tests;
     validated live with scripted drags + keybd_event (scratchpad
     key.ps1): pane resize, rail resize, toggle collapse, relaunch
     persistence (collapsed + weights + width), Ctrl+F reopen with
     typed query landing focused, drag-shut. Gap: stacked splits
     would need a vertical DragDivider twin (no renderer builds them
     yet).
   - **Tasks plugin T1 (2026-08-31, agent team: two researchers → design
     synthesis + mockups → ADR 0015 ratified in stages → four
     implementers; Fable coordinating, live-validating with scripted
     typing)** — ADR 0015 **Accepted**: `due:`/`done:` plain ISO keys on
     task lines only, `/` two-stage caret menu (natural language → ISO
     at insertion, never stored), global filter (default empty = every
     checkbox), generic ` ```filter ` blocks behind a required `type:`
     (T4), `search.types` + type-scoped operators (T2), `tasks.fields`
     as the first plugin-published slot; ADR 0011 amended five→nine
     slots; research notes in `architecture/research/`. T1 SHIPPED:
     kernel `Contribution.config` (manifest JSON — no migration;
     uniffi default "" keeps Kotlin call sites compiling; dll
     restaged); `editor.decorations` seam in MarkdownEditing.kt
     (checkbox = the shell's own first registration, per-match offset
     table, fence gating per-registration with the checkbox opting
     in, activation chain, `decorationAt` replaces checkboxUnder);
     suggest machinery (TaskDates/SuggestSession immutable state
     machine/`replaceRange` — insertAtCaret trims so cannot express
     completions/SuggestPopup non-focusable caret popup); tasks
     plugin wired (declaration `org.memory.tasks`, both-enabled
     tasks.fields gate, filter live without restart, settings screen
     with contribution chips). Suite 242 desktop + 65 cargo. Live
     proof: typed `/due tom` in a probe → `due:2026-09-01` on disk
     via autosave; prose `due:` stays plain; plugin off → tokens
     plain text, bytes unchanged, checkboxes still draw. Known gaps:
     decorations `config` JSON mirrors Kotlin recognisers untested
     (can drift); `done:` styled but stamped only in T2; no
     mouse-click acceptance in the popup; popup highlight not
     scrolled into view; Android bindings pre-existing stale
     (Plugins.kt uncompilable there since M6a). **T2 SHIPPED
     (2026-08-31, two parallel agents + coordinator alignment)**:
     done-stamping rides the activation chain via a registration
     matching the TASK MARKER itself (a line about to be ticked has
     no done: token to host the handler); render-losing registrations
     still activate (pinned by test, no seam change needed);
     `type:`/SearchType registry in Search.kt (line-level =
     narrowed-note evaluation per §4), TaskSearch.kt carries
     `type:task` + due:/done: operators (date/today/overdue/week/
     none/before:/after:, malformed ⇒ empty not prose) + the T4 seam
     (`TaskHit`/`taskRows`, `searchVault(maxLines=)`); panel gate =
     declared `search.types` contribution (coordinator aligned the
     slot constant + declaration after both agents landed). Suite
     ~284. Live-proven: toggle stamped done:2026-08-31 to disk;
     `type:task due:week` in Ctrl+F returned exactly the task lines.
     Gaps: a checkbox quoted in a code fence still stamps (activateLine
     carries no fence context); `today` resolves when the decoration
     registry builds (session over midnight stamps the open day);
     suggest's trailing space + stamp's leading space = double space
     before done:; validation lesson — scratch-vault PLUGIN STATE
     persists (a disabled test plugin stays disabled next probe).
     **T3 SHIPPED (2026-08-31, one agent)**: `RailPanel` is a
     plugin-keyed value (FILES/SEARCH companions); the `navigation`
     slot is real — `navigationPanels(plugins)` reads contributions
     whose config names a panel (nameless ones dropped), the tasks tab
     gated additionally on `tasksPanelEnabled()` (settings key
     "panel", absent=true; one JSON document with the filter,
     read-modify-write both keys); TasksPanel = one
     `searchVault("type:task", maxLines=200)` per vaultTick →
     `taskRows` → pure `agenda()` partition: checked excluded,
     overdue < today, today, today+1..+7 (deliberately NOT the
     due:week window so views never overlap), undated collapsed;
     50-row cap per group with +N more; rows NAVIGATE only. Probe
     `panel:<key>`. Suite 308. Live-proven: four groups with correct
     colours; done task in no view. Gaps: panel staleness = search's
     (vaultTick; buffer edits arrive at next tick, ADR's
     settle-refresh not implemented); rows open the note, not the
     line; tasks due past today+7 appear in no view (bounded by
     design — search answers them); `navigationPanels` knows the
     tasks panel by id in shell code (debt 10). **T4 SHIPPED
     (2026-08-31, one agent) — ADR 0015 delivery complete**:
     FilterBlock.kt (pure: lines joined = the DSL's whitespace-AND;
     classify Empty/NoType/UnknownType/Runnable BEFORE evaluating —
     the hint row never guesses); MarkdownView gains
     `BlockRenderers` (a table keyed by fence info string —
     `filter` is the shell's own and only registration into
     `Slots.EDITOR_BLOCKS`; unknown info strings fall to the code
     box, which is also the removal story); TaskHitRow extracted
     from the panel and shared; Read-mode rows navigate via
     TabsState.open. **DEVIATION, owner-visible: Edit mode ships
     the raw fence, not the ADR's caret-outside overlay** — rows
     don't sit on the text line grid, the composed offset mapping
     threads back through checkbox/hover/popup anchors, and a
     covered block can't be clicked into; pinned by test
     `editModeShowsTheFenceItselfRatherThanTheList`; no evaluation
     runs in Edit mode at all. Suite 341. Live-proven:
     `type:task due:week done:none` rendered exactly its two rows
     in Read mode, the type-less block its hint, Edit mode the raw
     fences with in-fence tokens correctly un-decorated. Gaps: each
     block = one vault pass; rows open the note not the line;
     EDITOR_BLOCKS has one producer (the settings.form lesson).
     **Post-delivery (2026-08-31, owner ask "due before today / not
     done")**: `before:`/`after:` values now take anything
     `resolveDatePhrase` resolves (today/tomorrow/weekday-next),
     resolved at evaluation; new `is:open`/`is:done` operator on
     `type:task` reads the checkbox marker itself (what "not done"
     means; `done:none` stays the date-token question); hint rows
     updated; recorded in 0015's post-delivery amendments alongside
     the T4 edit-mode deviation (pending owner word: accept as final
     or schedule the overlay round). Suite 351. Live-proven:
     `type:task due:before:today is:open` returned exactly the one
     open overdue task. **Owner-reported fix (2026-08-31)**: a
     just-opened note rendered its FIRST task line raw (caret parked
     at offset 0 with no visible caret ⇒ line 0 was "the caret's
     line") and the first click couldn't toggle it. Now
     `caretLine == null` means "no caret — decorate every line" and
     NoteEditor passes null while the body field is unfocused; the
     raw-line exception follows a caret someone can see. Coordinator
     fix; two seam tests rewritten to the new contract; live-proven
     (fresh open shows all glyphs, first click stamps done:).
     **Second owner report same evening**: ticking tasks with the
     agenda open didn't update the panel — vaultTick existed and the
     panel listened, but the editor's own writes never raised it
     (autosave wrote silently; recorded T3 gap). Fix: `write()` calls
     `state.noteSaved()` (vaultTick++) after any successful save, and
     checkbox activation now calls `write()` directly (§ 1
     write-on-action — no 1.2 s autosave wait). Panel, search and
     filter blocks all refresh off the same tick; kernel ingestion
     still waits for sync (display fact, not history). Remaining
     edge: a dirty buffer flushed by tab DEACTIVATION (flushEditor in
     Tabs.kt has no state handle) is still silent until the next
     tick. Live-proven: ticked a task from the open panel — its
     "today" group vanished without reload.
   - **User docs site + README (2026-09-01, one agent + coordinator
     validation via headless Chrome)** — `docs/` is a hand-authored
     static site (no build step; `.nojekyll`; relative links only —
     it serves from https://gilune.github.io/gilune/ once the owner
     flips Pages to main/docs): landing + six guides (notes, capture,
     tasks, search — the canonical user DSL reference, workspace,
     plugins), app-palette styling, 8 cropped probe screenshots +
     512px logo in docs/assets/. README.md rewritten product-first
     (centred logo/name, honest status, verified build-from-source
     block incl. the uniffi-bindgen step, licence preserved). RULE:
     user docs never use internal vocabulary (kernel/slots/ADR/
     seams); when features change, update the relevant guide page
     and reshoot its screenshot via the probe tooling.
   - **Whisper model benchmark + model setting (2026-09-01, owner
     ratified "sounds good")** — measured on this machine (21.5 s TTS
     clip, 8 threads, language set, greedy, throwaway harness in
     %TEMP%\wb): tiny 632 ms / tiny-q5_1 553 ms / base-q5_1 1019 ms /
     base 1190 ms. Quantised ≈ full siblings' accuracy and slightly
     FASTER; base-class fixes ring-main/£/plasterer-class errors but
     regressed "surveyor" on this clip; "Gilune" defeats every model.
     Shipped: model is a transcription-plugin setting (radio column
     beside Spoken language, one settings JSON `{language, model}`,
     kernel reads only `language` so `model` is inert to it);
     catalogue in Plugins.kt (`WHISPER_MODELS`, fastest first),
     **default now base-q5_1** (57 MiB < tiny's 75); per-model files
     in %LOCALAPPDATA%\Gilune\models so switching back is instant;
     absent/unknown key falls back to default. tiny-q5_1 (31 MiB) is
     the pencilled phone default pending MS2. MACHINE NOTES for any
     fresh whisper-rs build (MB0 will hit all of these): scoop CMake
     4.x needs `CMAKE_POLICY_VERSION_MINIMUM=3.5`; its VS-generator
     detection fails against the 2019 Build Tools → import
     vcvars64.bat env + `CMAKE_GENERATOR="NMake Makefiles"`; and
     NMake try-compiles die (U1052 build.make not found) beyond
     ~MAX_PATH — build from a SHORT path, never the scratchpad.
   - **Mobile design ratified + filter blocks render in Edit
     (2026-09-01)** — ADR 0017 (mobile app) **Accepted**: capture-first
     shell, live preview under a no-reflow law, always-glyph
     checkboxes, LOCAL on-device transcription (the coordinator story
     was an M4b build artefact, corrected pre-ratification), staged
     MB0–MB3 behind MS1 (IME) and MS2 (ARM64 whisper) spikes; five
     answers in its Ratification record — note #3: desktop KEEPS
     raw-on-the-caret-line, the shells deliberately differ. Research
     notes in architecture/research/2026-09-01-*. Separately, the
     owner overturned ADR 0015's T4 edit-mode fallback: a `filter`
     block now "behaves like a checkbox — renders until the caret is
     placed within it". Seam grew `EditorBlockDecoration` (block-
     scoped claims collapse a closed fence to reserved lines through
     the one offset table; overlay anchored from getLineTop/Bottom so
     height IS the reserved lines); caret on any fence line reveals
     the whole block; non-row clicks fall through to place the caret
     inside (reveal); rows navigate; edit cap 8 rows + more (Read
     keeps 50); no evaluation while the caret is inside; unterminated
     fences never collapse. Suite **381**. Screen-validated (render +
     click-through reveal). Coordinator confession: broke the
     no-PowerShell-regex rule on decisions/README, mojibaked an
     em-dash, caught it in the diff pre-commit — the rule stands.
     **MB0 DONE (2026-09-01, one agent)**: gilune-ffi cross-compiled
     for arm64-v8a + x86_64 WITH whisper (5.70/5.73 MB per ABI vs the
     15 MB budget; +libc++_shared.so — whisper links the NDK STL);
     incantation is `kernel/scripts/build-android.ps1` (auto-discovers
     SDK/NDK/make/libclang, overrides the stale ANDROID_HOME,
     -NoWhisper/-Abi flags, short build cache C:\gilune-android; the
     ABI bridge toolchain file MUST stay named android.toolchain.cmake
     or cmake-rs stops recognising the cross-build). Four encoded
     traps: no VS/ninja → Unix Makefiles + msys2 make; ANDROID_ABI
     must be set (CMAKE_ANDROID_ARCH_ABI does NOT substitute);
     whisper-rs-sys 0.15 applies HOST-Windows flags to Android
     (/utf-8 suppressed via CMAKE_*_FLAGS, -ladvapi32 stubbed);
     stale CMakeCache pins the wrong ABI (script wipes on mismatch).
     Bindings regenerated from the Android binary came out
     BYTE-IDENTICAL to desktop's — 07-compatibility's target-dependent
     checksum rationale did not hold (rule kept; owner may revisit).
     androidMain compiles with ZERO source changes; instrumented suite
     280/0 on gilune-s1 (jvmSharedTest rides along); desktop 381
     unregressed. Gaps: whisper built+loaded but never EXECUTED on
     Android (arm64 never run at all — MS2); model-cache pathing
     waits for a caller (MB1/MB2); AGP strip warning (no ndkVersion)
     cosmetic. **MB1 DONE (2026-09-01, one agent + coordinator
     emulator validation)**: MainActivity is the ADR shell —
     edge-to-edge, adjustResize, DayNight, composer-first home (IME
     action = newline, commit ✓), feed after first frame, voice rows
     with §4 pending wording ("enabled ≠ able" bug caught by eye),
     idle timer (pure-tested; expiry COMMITS), Capture/Notes nav
     (mode-swapped under IME), notes list + Read view with 48dp
     tap-to-tick (parser now carries source lines — MdBlock.line +
     ListItem.task; desktop Read shows glyphs too, inert), settings
     (vault truth + Export-a-copy zip via SAF + plugins/consent +
     appearance), DataStore prefs, Theme schemes moved to
     jvmShared/Palette.kt (ThemeChoice moved from Prefs.kt;
     secondaryContainer family added — stock-purple nav leak;
     CaptureBar.kt DELETED, one caller), espresso-core 3.7 pinned
     (3.6 reflects on an API-36-removed method). Suites: instrumented
     330/0, desktop 385/0. Coordinator live proof on emulator:
     first-run vault dialog, typed capture → "captured" → feed row.
     ADB lesson: `adb exec-out > file` in PowerShell mangles binary
     — screencap to /sdcard + pull. Gaps: navigation slot unread
     (no Agenda tab yet — MB3), filter fences = code box in mobile
     Read (MB3), transcribing-row over-claim window until
     enableWhisper wires (MB2), tertiary palette family undefined,
     timer lifecycle untested by a real cycle, still x86_64-only.
     **Record widget DONE (closes MB1)**: RemoteViews 4-cell widget
     (Record/New note/Search→Notes-until-MB3/inbox count = Captures
     *.md dir read, refreshed on vaultTick + service broadcast);
     RecordService = mic FGS (manifest+startForeground MICROPHONE
     type; own second Gilune.open — multi-instance is the sanctioned
     shape; recorder thread → 64KiB ChunkedSink → beginCapture,
     header first with 0xFFFFFFFF sizes — a stream can't know its
     length, content-addressed blobs can't be patched; whisper.rs
     reads fixed offsets so safe; device test proved a chunk crossed
     BEFORE stop); notification Stop → "Recorded m:ss · tap to
     open"; New-note QS tile only (mic tile withheld per ADR). API-36
     trap encoded: a FGS that declines via stopSelf() still crashes
     (ForegroundServiceDidNotStartInTime) — refuse() goes foreground
     for the instant it takes to withdraw. Suites: desktop 394/0,
     instrumented 350/0. Gaps: emulator mic = silence (no speech
     through the path; transcription unwired until MB2 — whisper IS
     in the .so, nothing calls enable_whisper yet); lock-survival
     asserted not exercised; Play Console FGS declaration = owner
     action; revoke-between-draw-and-tap window unhandled;
     MediaPlayer playback of 0xFFFFFFFF-size wavs untested; widget
     never placed on a real launcher.
   - **MS1 IME SPIKE DONE (2026-09-01): PASS-WITH-CONDITIONS**
     (spikes/ms1-ime/README.md; 39 tests stay as regression; real
     InputConnection via onCreateInputConnection, NOT
     performTextInput). Mapping held everywhere (straddling regions,
     autocorrect, CJK, surrogates, both caret rules); but Compose's
     LEGACY BasicTextField(TextFieldValue) corrupts unbatched
     composing after a commit (prefixes accumulate; 3 controls put
     the fault in the field, not our decoration; restartInput no
     cure). CONDITIONS: MB2 uses TextFieldState+OutputTransformation
     (retires manual mapping; ADR 0012's invariant becomes a
     type-system fact; undoState free; the IME sees DECORATED text so
     glyphs stay delimited); no length-delta decoration inside a
     word; physical-device matrix ASSUMED. Latent: the DESKTOP editor
     sits on the same legacy field — AWT/CJK composing untested
     there.
   - **MB2 DONE (2026-09-01, one agent + coordinator on-device
     validation)**: Android live-preview editor on
     TextFieldState+OutputTransformation per MS1. KEY CLASSPATH
     FINDING: Foundation 1.7.6's TextFieldBuffer has NO styling API —
     so the transformation changes ONLY the checkbox bytes (caret
     never consulted; offset table shape-stable) and styling is a
     sibling BasicText painting METRIC-NEUTRAL spans over a
     transparent-text field (colour/underline/strike only; layout
     agreement asserted by test at every line top and wrap point).
     COST RECORDED: heading size / bold weight / italic slant
     unrendered in Edit until a toolkit bump (Read renders all
     three; headings take the accent colour). Checkbox tap = 48dp
     boxes from the field's own layout, consumed on Initial pass
     only inside a box, selection restored, haptic, immediate write.
     mobileEditorDecorations() enforces MS1's whitespace-delimited
     rule over the whole registry (TASK_MARKER alone would let
     `- [ ]milk` glyph inside a word). Toolbar row + 232dp panel via
     pure BufferEdits through state.edit{} (whole-buffer rewrites
     would collapse undo + restart composing); / drives the shared
     SuggestSession by typing the trigger; undo = undoState, proven
     against a real InputConnection commit. Suites: instrumented
     425/0, desktop 394/0. Coordinator drove it live: capture →
     note → Read glyphs → Edit with styled tokens + toolbar. Gaps:
     glyph long-press eats platform selection start; title rename
     loses on back-without-commit (desktop parity); row scrolls
     under ~384dp width; perf unmeasured on long notes.
   - **Mobile transcription + dictation DONE (2026-09-01)** —
     **WHISPER'S FIRST ANDROID EXECUTION**: 2 s silence → transcript
     in 5.34–5.57 s (x86_64 emulator, tiny-q5_1, 3 runs, outputs=1
     failed=0; NOT a phone number — MS2 stands). Phone default
     tiny-q5_1 implemented at the READ SITE (`transcriptionModelKey()`
     returns the unresolved key — the only shared change, pinned from
     both sides so editing the shared default fails tests). One
     enable/download call site (LaunchedEffect keyed
     state|granted|settings; consent = POSITIVE grant — empty lists
     fail toward not fetching; .part rename; a second racing call
     site in openVault was removed). Pending states:
     `TranscriptionPhase`, only Ready earns "transcribing…"; third
     wording "downloading the transcription model…" added beyond §4's
     two (an in-flight download must not read as "needs"). Dictation
     = note.actions row gated contribution AND Ready; minimal
     BufferEdit at the caret; blank transcripts write nothing.
     WIDGET GAP FOUND: RecordService never ran processing — now
     enableTranscriptionForService + runProcessing + re-project on
     its own handle (enable_whisper is PER-HANDLE, kernel-confirmed;
     service never downloads — a home-screen tap is no place for
     consent; idempotent jobs make double-processing safe); ON_START
     refresh so widget notes stop reading "transcribing…" forever.
     INTERNET added to the manifest (S5/Play surface, like the mic
     FGS type). Suites: instrumented 454/0/0-skipped (the whisper
     test genuinely ran), desktop 394/0. Gaps: download flow never
     exercised end-to-end (model seeded via adb); **[BLANK_AUDIO]
     wart — whisper's silence marker lands as a real note body
     (desktop shares this; owner decision pending)**; runtime not
     persisted across process death; widget pays a model load per
     recording.
   - **MB3 DONE (2026-09-01)** — the ratified staging is complete.
     Search tab (panel DSL, live operators/types, empty-query hint);
     Agenda via the NAVIGATION SLOT as data (shell three + take(2)
     contributed; appears/leaves with the tasks plugin + its setting;
     vanished selection resolves to the shell tab as a VALUE, no
     write-back; M3 nav merges icon descriptions when labelled —
     tests address by label); agenda rows TOGGLE on mobile (§8; the
     desktop panel deliberately doesn't — both sides recorded in
     AgendaScreen) via toggleTaskLine guarded on exact line text;
     agenda() moved to jvmShared/Agenda.kt (pure, zero desktop
     change — TasksPanelTest untouched green); filter fences render
     in mobile Read; widget Search cell routed; launcher shortcuts
     (static XML, widget drawables not adaptive icons). Suites:
     desktop 394/0; instrumented 482/1 — the 1 is an MS1 CONTROL
     (no transformation) flaking on cold `-no-snapshot` AVD boots
     (3 runs failed 6/3/1 different members; the spike's 5 green
     runs were warm-snapshot). RIG NOTE: boot the AVD from snapshot
     for MS1 runs. Found in passing: READ-VIEW TICKING doesn't stamp
     done: (builtInDecorations only, contradicting VaultFiles' own
     comment) — folded into the Enter-continuation round. Search is
     per-keystroke unindexed on mobile (inherited); no OR
     (inherited).
   - **Enter continues lists (both editors) + Read-stamp fix
     (2026-09-01, owner rule)** — shared pure
     `listContinuation(text, caretAfterNewline)` in MarkdownEditing
     (reads the buffer AFTER the newline so mid-item Return falls
     out: split text lands behind the fresh marker, Obsidian-style;
     "empty item" judged over marker remainder + carried text;
     checked→unchecked; indentation preserved; fences and a live
     suggest session excluded; empty item UNWRAPS incl. the typed
     newline — CM6 behaviour, pinned). Desktop hooks onValueChange
     (IME-safe; suggest wins structurally since preview-Enter
     starves the field); mobile is an InputTransformation editing
     the incoming change (soft Return is a committed newline, not a
     key; MS1's no-whole-rewrite honoured; ONE undo removes
     newline+marker, device-proven). Read-view tick now stamps done:
     (call-site bug — ReadScreen took the default chain). Suites:
     desktop 420/0; instrumented 508/3 — the 3 are MS1 rig flakes,
     now known non-deterministic EVEN FROM SNAPSHOT (different
     members per run; rig-stabilisation spawned as a background
     task). Not continued: ordered lists, blockquotes,
     outdent-before-clear on indented empties; a batched
     autocorrect+Return single commit won't continue (safe default,
     un-tested against real Gboard).
   - **Moonshine arc R0–R3 DONE (2026-09-02, four sequential Opus
     agents, Fable coordinating; ADR 0018 Accepted with six
     consequence amendments; research
     architecture/research/2026-09-02-moonshine.md)** — realtime
     transcription per the owner's ask ("realtime on the phone will
     just feel better"). **R0 spike PASS-WITH-CONDITIONS**
     (spikes/r0-moonshine/): AAR ai.moonshine:moonshine-voice:0.1.5,
     natives 16.70 MiB/arm64, model 45.23 MB / 8 files crc32c from
     download.moonshine.ai, TTFT 649 ms at transcription_interval
     0.25 s (condition), partials rewrite MID-PHRASE, silence emits
     ZERO events (no [BLANK_AUDIO] analogue — so clean_transcript is
     deliberately NOT ported to the provider); transitive-dep
     exclusions are a DEFECT FIX (androidx.startup
     InitializationProvider crashes isolated process starts).
     **R1 (caption, 57d0959)**: org.memory.moonshine declared in
     Plugins.kt, registered by the ANDROID shell only;
     network.request-consented download is OURS (okhttp excluded —
     vendor AssetDownloader unreachable; manifest checksums are
     BASE64 crc32c decoded big-endian; java.util.zip.CRC32C is API 34
     vs minSdk 29 so the CRC is hand-rolled; .part→verify→rename,
     .ready last; Wi-Fi-only default); caption = fixed 68 dp block
     above the composer reserved by the record tap, bottom-anchored,
     settled lines onSurface + ENTIRE revisable region muted, fed by
     a throw-guarded tee on the recorder loop (capture writes first)
     into a bounded-queue session thread; display-only tested (the
     recording commits identically with the caption on/off/crashing);
     abiFilters arm64-v8a+x86_64 (the AAR ships armeabi-v7a);
     first words 1.3–1.9 s (model loads while recording runs —
     recording never waits). **R2 (FFI route, 67023e1; discharges
     ADR 0015 debt 7)**: register_external_processor / claim_job /
     submit_output / fail_job on the uniffi surface; ClaimedJob is a
     kernel-OWNED object (unconstructable by callers — not a write
     door); whisper's run-record→commit_outputs→complete_job became
     the shared Store::deliver_outputs + one public idempotency_key;
     blob crosses streamed 64 KiB, digest-verified twice (whole file
     before the first chunk, running hash on the same handle);
     NO schema migration (lease/processing_runs/blob facts suffice;
     registration is per-handle per-process, deliberately not a row);
     run loop EXCLUDES registered external ids (else it burns their
     leases — pinned both directions); stale claimant's late
     fail cannot resurrect a landed job; crash points as real process
     aborts in gilune-cli/tests/external_route.rs; contracts/processor
     records the third transport. cargo 92, bindings byte-identical
     across targets again. **R3 (provider)**: declaration grew
     processorId moonshine-transcribe (distinct from whisper's
     `transcribe` — shared id = coupled disable + unanswerable
     provenance) + blobs.read/objects.write; MoonshineProvider.kt
     claims→streams→fresh-decodes the COMMITTED blob→submits
     whisper's document shape, on BOTH handles (MainActivity and
     RecordService — the widget gets words now); the
     GiluneState.externalProcessing seam runs after every
     runProcessing call site, null on desktop (unchanged by
     construction); ONE ACTIVE TRANSCRIBER is host policy in
     Plugins.kt (TRANSCRIPTION_PROVIDERS precedence: whisper
     incumbent; providerSwitch disables the displaced side and says
     so in words, providerSwitchWarning says it BEFORE the flip;
     both-enabled via CLI ⇒ whisper wins, moonshine claims nothing);
     catch-up: queue stays blanket, the WRITING is wordless-scoped in
     the provider (worded subject ⇒ complete with no outputs) —
     consequence: those runs are recorded so moonshine can never
     later re-decode them; grown-declaration consent =
     newlyDeclaredLocalCapabilities (never offered ⇒ granted; never
     re-grants after revoke, pinned). Real-engine proof on emulator:
     committed wav → all three sentences in the note. Suites: desktop
     436, instrumented 610 (2 = the known MS1 rig flakes, 1 skip =
     R0's a03). Open after the arc: hybrid caption+whisper-words is
     UNREACHABLE (exclusivity; matches §2's one-engine rule — owner
     eye flagged); packaged mobile default stays whisper until MS2
     (moonshine opt-in); two models briefly co-resident (~57 MB each,
     unmeasured); dictation-mic has no caption (no-reflow reasoning
     fails over a live buffer); docs site has no mobile/caption
     guide; MS2 owns ARM64/battery/thermals/"Gilune"-vocabulary.
   - **MS1 rig stabilised (2026-09-02, one Opus agent, owner-started
     chip; coordinator decision (a) ratified in-flight)** — the flakes
     were never establishment timing: the emulator's bound LatinIME
     calls finishComposingText UNPROMPTED 150–860 ms after every edit
     on the LEGACY Compose field (a platform EditText under the
     identical rig takes zero interjections), and waitForIdle only
     waits for our process — every composing assertion raced another
     process, and the spike's original 5 green runs were luck. Fix:
     `Ms1InputSessionRule` disables device IMEs per row and restores
     in finally + a JVM shutdown hook (chained OUTSIDE the Compose
     rule — bare @Rule ordering is undefined); rows whose subject IS
     the keyboard opt back in via @WithSystemKeyboard and await the
     teardown as an event (bounded poll, no blind sleeps). The two
     old defect rows were CONTRADICTORY under any deterministic rig
     (region asserted open AND absent either side of the same ~260 ms
     teardown) and were rewritten to pin the REAL mechanism — field
     opens the region, keyboard tears it down, next span appends =
     bytes the user did not type; falsification proven (annotation
     stripped ⇒ all 3 mechanism rows fail). MS1 verdict + all four
     conditions UNCHANGED (condition 1 reinforced; TextFieldState
     unflaky 8/8 in diagnosis). Re-proof: 39/39 ×6 cold-no-snapshot
     + ×6 snapshot; full suite 610 = 609/0/1(a03 skip) from BOTH boot
     types — zero flakes anywhere. README keeps the superseded
     account verbatim. New honest gaps: one keyboard measured
     (Gboard); 36 rows now run keyboard-UNBOUND; whether
     TextFieldState suffers the same teardown is unasserted (wants
     its own @WithSystemKeyboard row).
   - **Third review + surgical round (2026-09-03, Fable 5.1; finders
     and verifiers on Sonnet after an Opus 529 outage; ONE Opus fixer,
     sequential, adversarial tests; coordinator line-by-line review, NO
     follow-up sweep — owner decision to stop the review→fix loop).**
     Honest accounting the owner asked for: sweep 1 found 25 (all
     fixed); sweep 2 found 33 = 15 NEW regressions from round one + 18
     re-listed cleanup seeds; sweep 3 found 33 = 10 NEW regressions
     from round two + 4 pre-existing + 19 re-listed (now PARKED below).
     Round-two regressions came from clever fallbacks tested on honest
     paths only. Fixed with simplest-safe rules: `previous_device_ids`
     RECORDED never believed (pairing un-refuses ONLY the peer's current
     device_id; each claim → alert `sync-previous-identity-claimed`
     not-damage + explicit FFI `readmit_device(id)` durable via meta
     `readmitted_devices`, overruled by a later revoke; roster pair()
     retires only the paired-as name); legacy keyless tombstone
     withdraws ONLY if sole head == projected revision AND disk ==
     recorded AND not a local edit (strictly narrower; transcript_merge
     now uses shared `operations::tombstone_payload` so its tombstones
     name heads); Referent::Uninterpreted ONLY when the held unreadable
     op is from the operation's origin device (origin key, else the
     deliverer) — unrelated devices are not evidence → Awaited;
     `SubjectMoved` completes the job as `skipped_moved` (new
     ProcessingSummary/FFI field) and the pass CONTINUES; one-row
     `ProcessorCtx::subject_head_and_payload()` on both routes; schema
     v6 = `CREATE INDEX IF NOT EXISTS operations_target ON
     operations(target_id)` (fixture chain re-pinned to
     SCHEMA_VERSION); WIT `proposed-output.expected-head` +
     `host-objects.subject-head`; `registerMoonshineOnHandle` shared by
     activity + RecordService (service raises providerOffNotice via
     MobilePrefs(context) when IT switched off); gate_c default-port
     test probes 51837 first; joiner rate-limiter keyed by the QR's
     fingerprint. Refuted this sweep: `promote`'s already-exists branch
     (target path is digest-bound, same bytes). Suites: cargo 345/0,
     desktop 529/0 (--rerun-tasks), clippy clean; dll 5.69 MB restaged,
     ABIs 6.45/6.67 MB, bindings byte-identical (readmitDevice,
     skippedMoved). **Android instrumented NOT RUN this round: the
     emulator and the gilune-s1 AVD were ABSENT from the machine and
     the SDK appears mid-migration (C:\Android\android-sdk is now live;
     new untracked scripts/move-android-to-d.ps1 +
     verify-android-on-d.ps1 and a build-android.ps1 edit appeared
     during the run — the OWNER's work, left uncommitted; the ANDROID_HOME
     machine notes above are STALE until the owner confirms the new
     layout).** Kotlin compiles for both source sets; two new
     instrumented tests unexecuted.
   - **Android design pass (2026-09-04, owner feedback from the running
     emulator; one Opus implementer, Fable reproducing/validating on the
     device; four items ratified via AskUserQuestion, all recommended
     options taken).** Findings: the `<application>` element named NO
     icon (launcher showed the stock robot; `:113`'s icon is the QS
     tile's); "stuck after creating a note" was the CAPTURE screen — the
     bottom nav hides while the IME is up (`MainActivity` Home &&
     !isImeVisible), ✓ left the composer focused, so the only exit was
     the system back gesture; the agenda's empty state was "Nothing
     open."; and a silent recording read "transcribing…" forever though
     the kernel showed its job done in 6 s with an empty transcript.
     Shipped: adaptive launcher icon (`mipmap-anydpi-v26`, white
     `@color/gilune_launcher_background` in values/ only, 512 px
     `drawable-nodpi` foreground with the v2 artwork at 58% of the
     canvas, monochrome layer; `GiluneLogo()` in About + the first-run
     vault notice); `rememberComposerDismiss()` = clearFocus+hide, ONE
     definition, called by ✓ (guarded on non-blank) and the idle-timer
     commit, plus a `composer-dismiss` button ("dismiss-keyboard", the
     editor's glyph) drawn only while the composer has focus; the
     composer now owns a `TextFieldValue` so an OUTSIDE draft (agenda
     seed, commit clear) lands with the caret at its end (real defect:
     Compose's String overload kept offset 0 in front of a seeded
     `- [ ] `); `agendaEmpty(rows, sections)` in jvmShared/Agenda.kt —
     NO_TASKS → explainer + "Start a task" (Capture tab, `taskDraft`
     never overwrites a non-blank draft, focus nonce) / NONE_OPEN →
     bounded-views sentence naming `type:task`; kernel
     `SourceSummary.interpreted` = EXISTS processing_run for an event
     whose subject is the source (the RUN, not the revision origin:
     Moonshine writes zero outputs for silence) → shell
     `Transcription.Silent` → "no speech heard", asked BEFORE the plugin
     questions. FFI +1 field; bindings byte-identical both targets; dll
     5.69 MB restaged; ABIs 6.39/6.55 MB. ADR 0017 Amendments record
     all four; capture + tasks guides gained prose (no reshoots).
     Suites: cargo 347/0, desktop 532/0 (--rerun-tasks), instrumented
     785/1/12 warm AVD — the 1 = SyncScreenUiTest ActivityScenario
     teardown timeout, 14/14 class-alone; 12 = model-gated skips.
     **RIG LESSON (my authorisation, my fault): the instrumented runs
     WIPED the emulator's app data — the owner's vault on it, the
     whisper model the owner had downloaded, grants, sync identity.
     dumpsys showed the package removed and re-added (uid walked
     10216→10222→10226) across installs; gradle connected tests
     uninstall the app under test after the run. RULE: an emulator
     holding anything the owner cares about gets its vault exported
     (Settings ▸ Export a copy) BEFORE any install or instrumented run,
     or the run waits.** Machine note: `cargo build` of the whisper dll
     from Git Bash fails — a stale CMakeCache pins NMake while cmake-rs
     defaults to VS 17 2022 (only 2019 Build Tools exist); build from
     vcvars64 with CMAKE_GENERATOR="NMake Makefiles" +
     CMAKE_POLICY_VERSION_MINIMUM=3.5 + LIBCLANG_PATH. Closed gap: the
     phone's consented model download ran end-to-end in the owner's
     session (31 MB tiny-q5_1 landed in filesDir/models). Open after
     the pass: TEXT captures queue a `transcribe` job (subscriptions key
     on the event, not the media) that sits pending with 0 attempts
     until some OTHER pass runs — `GiluneState.captureText` alone never
     calls processAll (owner decision: run a pass after a text capture,
     or let the next pass sweep); desktop `TasksPanel.kt:111` still says
     one lower-case "nothing open" for both cases (`agendaEmpty` is
     there for it); `interpreted` means ANY processor finished, which
     coincides with "a transcriber did" only while one processor
     subscribes to source-ready; the idle-timer dismissal is structural
     (same function) but no row drives a real background cycle.
   - **Continuous builds in GitHub Actions (2026-09-04, owner ask; one
     Opus implementer on branch ci/github-actions, Fable reviewing and
     merging; four decisions ratified: push builds + `v*` tag Releases,
     MSI + DEB only, debug-signed APK always + release-signed when
     keystore secrets exist, whisper on every target).**
     `.github/workflows/build.yml`: `version` job decides ONE version
     (tag `v<MAJOR≥1>.<MINOR>.<PATCH>` → `-Pgilune.version` +
     `-Pgilune.versionCode=run_number`; anything else fails fast with the
     jpackage reason; untagged = gradle defaults) → `windows` (cargo
     build --features whisper, cargo test, bindings, dll staged to
     resources/windows-x64, desktopTest `-Pgilune.kernel.profile=release`,
     `packageReleaseMsi` — the Compose plugin downloads its own WiX) ∥
     `linux` (same + clippy -D warnings, UI tests under `xvfb-run` with
     SKIKO_RENDER_API=SOFTWARE, `packageReleaseDeb`) ∥ `android`
     (`.github/scripts/build-android.sh`: bash cross-compile for both
     ABIs via the repo's `kernel/scripts/ndk/android.toolchain.cmake`
     bridge, NDK pinned 27.2.12479018, libc++_shared copied+stripped,
     bindings from the x86_64 .so; assembleDebug always; assembleRelease
     only when `GILUNE_KEYSTORE_BASE64/_PASSWORD`, `GILUNE_KEY_ALIAS/
     _PASSWORD` secrets exist — gradle `signingConfigs.release` exists
     only when `GILUNE_KEYSTORE_PATH` names a file) → `bindings` parity
     (three generated surfaces diffed; byte-identical) → `release` (tags
     or `dry_run_release` dispatch; the only job with contents:write;
     publishes MSI, DEB, `gilune-debug-signed.apk`, `gilune-release-
     signed.apk` when present). Triggers include `ci/**` branches so a
     pipeline change can be proven before main. `GILUNE_CI=1` is set by
     the workflow and NOTHING else: gate_c's 5 s budget is RECORDED but
     not JUDGED there (runner exchange 2.96 s vs 1.21 s here). CI FORCED
     THREE CODE FIXES: Main.kt joined `jna.library.path` with a literal
     `;` (a packaged Linux app would never have loaded the kernel — now
     File.pathSeparator); `register_plugin_as` read-then-inserted, so the
     20-process torture test hit `UNIQUE constraint failed:
     plugins.plugin_id` on the runner — now an upsert with the state READ
     BACK so a race loser never applies an opt-in provider's subscription
     as enabled (two 20×8-thread tests); `app/gradlew` was committed
     non-executable (+ `.gitattributes` eol=lf for `*.sh` and
     `.github/**`). Gradle: `targetFormats(Msi, Deb)`, `linux {}` block
     (package `gilune`, PNG icon, maintainer, Office/utils), version
     properties additive. Green run:
     https://github.com/Gilune/gilune/actions/runs/33892013202 — Linux
     12m43, Windows 22m49, Android 17m46; MSI 102.8 MB, DEB 95.8 MB,
     debug APK 114.0 MB (ABIs 6.45/6.67 MB); cargo 349/0, desktopTest
     532/0 on BOTH OSes. Tag path proven by a refused `v0.0.0-*` tag
     (deleted) + the collect step run locally over real artifacts; `gh
     release create` itself has NEVER executed (first real tag proves it;
     `gh workflow run build.yml --ref main -f dry_run_release=true`
     rehearses everything but that call). Docs: README points at
     Releases + Linux build lines + 57 MiB; 09-engineering-conventions
     § Continuous builds and releases. COST NOTE: the repo is PRIVATE, so
     runs bill Actions minutes — Windows counts 2×, so one full run ≈ 77
     billed minutes; every push to main is a run. Gaps: no instrumented
     Android tests in CI, no macOS, DEB only; nothing proves a package
     INSTALLS and runs; DEB says `Maintainer: Unknown` until
     `nativeDistributions.vendor` is set (check the MSI install path
     first); the locally staged dll + jniLibs are stale relative to the
     plugins.rs fix until rebuilt (dev-run gotcha).
   - **v1.0.0 RELEASED (2026-09-04)** — the owner wanted a first listed
     build ("the windows app is essentially usable now"); a v0.0.1 tag was
     refused by the rule, v1.0.0 chosen instead. Run 33898099309 green
     incl. the Release job (`gh release create` proven):
     https://github.com/Gilune/gilune/releases/tag/v1.0.0 with MSI 102.9
     MB, DEB 95.8 MB, gilune-debug-signed.apk 114.0 MB. Main run
     (33895536902) also green. WARM-CACHE BASELINE (tag run): Windows
     14.6 min (kernel build 1.8, cargo test 7.1 = the largest step, MSI
     2.8), Android 11.2 (cross-compile 8.0 — NOT cached: the script builds
     under RUNNER_TEMP while rust-cache covers kernel/target only), Linux
     5.0. Cold first run: 24.0/19.9/13.3. Speed levers agreed as an
     assessment, not yet built: kernel artefacts cached by source hash
     (skip the build when kernel/ is unchanged), test suites off the
     packaging critical path, ABI matrix, Android target dir under
     kernel/target, paths-ignore for docs-only pushes (each push bills a
     full run). **INSTALLER DEFECT found by the owner installing 1.0.0**:
     no Start-menu entry (jpackage adds one only when asked; the windows
     block asked for nothing) and no fixed `upgradeUuid` (a random one per
     build ⇒ a newer MSI installs BESIDE the older). Fixed in the windows
     block (menu=true, menuGroup "Gilune", upgradeUuid
     c077b799-5193-4ce3-b93a-411c67e29e37 — fixed for the life of the
     product; 1.0.0 predates it and must be uninstalled by hand) and
     shipped as v1.0.1. Installed location: C:\Program Files\Gilune\
     Gilune.exe. DPAPI KeyWrap is Windows-only by design; the DEB's
     identity file is unwrapped-with-reason.
   - **Owner testing 1.0.1 on Windows (2026-09-04) — fixes HELD LOCALLY
     by owner decision until a batch is worth a run**: (1) Return in the
     note title did nothing visible — `NoteTitle`'s Enter branch committed
     the rename (early-return when unchanged) and swallowed the key. Now
     Enter commits, leaves reading mode if needed, and raises
     `EditorState.focusBody`; the body field's LaunchedEffect puts the
     caret at offset 0 and requests focus, retrying across frames because
     the field may have been composed by that same key (the rail's Ctrl+F
     lesson again — `FocusRequester.requestFocus()` THROWS on an
     unattached requester and returns Unit in Compose 1.7.3, so retry on
     `isFailure`). Title field gained testTag "note-title". Four
     NoteEditorUiTest rows (unchanged name, renamed name + file follows,
     reading mode switches to editing, Escape stays put); desktop 536/0.
   - **CPU review (2026-09-04, owner ask)**: idle desktop = 203 ms CPU per
     60 s (0.34% of one core), 4 of 85 threads active (a native render
     thread 94 ms, VM Periodic Task 62, another native 31, D3D Screen
     Updater 16); no polling at idle — Panes.kt's 16 ms loop runs only
     during an edge drag, SyncSettings' 1 s loop only while a pairing
     window counts down, GiluneState has no timers. Active-path cost is
     the design gap: `searchVault` walks+reads every .md per query from 4
     callers (agenda per tick, filter blocks per settle, both search
     panels per keystroke); reconcile reads+hashes every projected file
     per pass; the shell's `vaultTick` carries no identity and cannot see
     a second process. Owner: "rescanning the vault, when we know where
     the change happened is a bit ludicrous… plugins need to be able to
     be smarter than that." → **ADR 0020 DRAFTED (Proposed)**: dense
     resumable `changes_since(cursor, limit)` feed read from the durable
     record (events + the four projection mutations that raise no event
     today: rename-by-id, withdrawal, import, quarantine), path-centric
     VaultChange {seq, kind, rel_path, previous_rel_path, object_id,
     revision_id, cause}, CursorUnknown ⇒ the only sanctioned full scan;
     consumer-owned incremental indexes (tasks plugin index; search
     per-note cache by sha; editor updates its own entry on write);
     reconcile stats size+mtime before reading (git's index rule, full
     hash pass on open); no file watcher. Staged K1/K2/P1/P2/S with a
     7-item gate. **REVISED same day after the owner's review** (points
     of contention the coordinator listed, plus the owner's question
     "what happens when we add people… a task has a person"): dense via a
     `meta` counter in the same txn (NOT AUTOINCREMENT — rollback gaps);
     git's racy-file rule instead of a full hash pass on every open
     (Verify vault = the explicit full hash); NO retention, an EPOCH in
     the cursor instead (CursorUnknown = foreign epoch or unheld seq);
     projection-only scope stated (attached captures never appear —
     events stay the object-level record); `cause` best-effort; crash
     window between file write and row commit stated;
     `reconcile_path(rel_path)` O(1) for the editor's own save. **INDEX
     HALF REWRITTEN — private per-plugin indexes REJECTED (the owner's
     point: cross-plugin queries would need one plugin to know another).
     Now: a kernel-hosted SYSTEM INDEX of FACTS `(anchor, predicate,
     value)` — anchor = note or note#line; predicates namespaced by
     plugin id and OPAQUE to the kernel (data, like event types) — in a
     device-local INDEX DB beside the vault (never in it, never synced,
     disposable, own schema outside the fixture chain) with FTS5 for
     text; plugins register pure INDEXERS text→facts (uniffi foreign
     trait `NoteIndexer` now, WIT `index-note` later — pure over text so a
     sandboxed plugin needs no vault access); the kernel re-derives ONE
     note's facts per change; operators COMPILE to constraints (plugins
     own the DSL words; SearchOperators/SearchType become the
     registration route), the kernel answers conjunctions with a join on
     the anchor (`type:task due:week @alice` — nobody wrote the join);
     scope from the anchor (line vs note); plugin removal drops its
     namespace; private indexes allowed only for non-facts (embeddings
     later). Staged K1 feed / K2 reconcile / K3 system index / P1 tasks
     indexer+operators / P2 search / S shells; 9-item gate incl. two
     shared-nothing indexers joining, incremental==rebuilt property,
     racy-rule row, 1k-note measurement. OPEN (owner deferred): desktop
     watcher-as-hint vs periodic stat while focused. AWAITS OWNER
     RATIFICATION; build after the memory round (landed 579872e).
   - **Memory round DONE (2026-09-04, one Opus implementer; commit
     579872e HELD LOCALLY with the batch).** Owner: 335 MB in Task
     Manager with one note is not a lightweight app. Measured on a probe
     copy of installed 1.0.1 (empty vault, idle): 504 MB private / 360 WS
     / 101 threads; NMT committed 216 MB = heap 80 (13 used) + **G1 GC
     bookkeeping 75 (MaxHeapSize defaulted to 16 GB on a 64 GB machine)**
     + metaspace 30 + code 11 + symbols 10; whisper base-q5_1 59 MB
     loaded AT VAULT OPEN and idle; Direct3D renderer ≈135 MB private /
     85 WS (measured via -Dskiko.renderApi=SOFTWARE — kept, owner
     decision); ~100 MB untracked native (DLL images, CRT, SQLite, JNA,
     mDNS, AWT). Shipped: (1) `compose.desktop.application.jvmArgs` =
     `-XX:+UseSerialGC -Xms32m -Xmx768m -XX:CICompilerCount=2` (each with
     its measured reason; ReservedCodeCacheSize/MaxMetaspaceSize measured
     ≈1 MB, NOT added); (2) **whisper on demand**: `WhisperEngine`
     {model_path, idle 10 min, Mutex<Slot{Arc<ctx>, in_flight,
     last_used}>}; `enable_whisper` validates the file (is_file) and
     returns Ok WITHOUT loading (FFI unchanged; shells' Ready semantics
     unchanged); `lease()` loads under the slot lock (racing first jobs
     share ONE context) and never holds it across process(); a Lease
     holds its own Arc so the sweep cannot take a running job's model;
     `sweep` drops only when in_flight==0 && idle ≥ 10 min; one detached
     std::thread per engine holding a Weak, wakes every 60 s, exits when
     the engine drops (cannot pin process exit); non-uniffi
     `Gilune::whisper_model_resident()` for tests; **model load measured
     74 ms** (cold job 883 vs warm 809 ms on 1 s audio); (3) the CI path
     leak `-Djna.library.path=D:/a/gilune/...` was `application {}`-level
     jvmArgs → moved to `tasks.matching { it.name == "run" }` (Compose
     registers `run` after evaluation, `tasks.named` fails) together with
     -Dgilune.vault/-Dgilune.probe. RESULT (same probe method):
     **308 MB private / 243 WS / 64 threads / NMT 91 / GC 0 / no
     whisper_model_load line at open** (err.log zero bytes). Tests: 8
     residency rows in whisper.rs (skip without GILUNE_TEST_MODEL; sweep
     driven by hand with an hour-long interval so the thread can't be why
     one passes), 2 whisper_smoke rows + the e2e row asserts
     not-resident→resident, 3 DesktopLaunchArgumentsTest rows reading
     build.gradle.kts (comments stripped — they name the forbidden path);
     falsified in three reverts (4+1, 4+1, 2/1 rows red). Suites: cargo
     349/0/2 + clippy silent (incl. --features whisper); desktop 539/0
     (--rerun-tasks, release profile); Android compiles both source sets,
     ABIs 6.41/6.57 MB rebuilt via the owner's script (run, not edited);
     instrumented NOT run (no emulator; no Android test added); bindings
     byte-identical; dll 5,702,144 B restaged. Docs: 0018 post-delivery
     amendment (owns the enable_whisper door), 09 § Desktop footprint,
     capture guide one sentence. **FINDING: uniffi 0.29 folds DOC COMMENTS
     into the metadata checksum — editing `enable_whisper`'s docstring
     changed `uniffi_gilune_ffi_checksum_method_gilune_enable_whisper`
     (48408→34755) and made the targets' bindings differ until BOTH
     binaries were rebuilt from the same source. A prose-only edit to a
     `#[uniffi::export]` item IS a bindings change.** Gaps: sweeper wakes
     once a minute for the engine's life even with nothing resident
     (parking it is tidier; simplest-safe shipped); phone's freed 59 MB
     inferred from the shared path, not measured on a device; whisper
     stderr spam untouched (now appears at the first job, not at open).
     Jpackage note: Task Manager's second "Gilune" (1 MB) is the launcher
     relaunching itself with an adjusted PATH and waiting on the child.
   - **PARKED DEBT (2026-09-03, owner decision — recorded, NOT to be
     re-counted by future review sweeps; fix opportunistically when a
     change already touches the file).** Verified real in three
     consecutive sweeps and deliberately deferred each time behind
     correctness: (1) `configure_sync` is a per-handle-opener ritual
     (MainActivity/Main.kt via GiluneState.syncSetup; SyncWorker calls
     it directly) — deeper form `Gilune.openConfigured`; RecordService
     correctly opens WITHOUT it. (2) `IntegrityAlert.kind` is a free
     String; the kind set lives in alerts.rs AND SyncAlerts.kt, pinned
     by AlertKindCouplingTest reading alerts.rs as text (regex matches
     `pub const` only) — deeper form a uniffi enum. (3) One-active-
     transcriber stays shell policy (ADR 0018 Q4, ratified);
     `applyOneTranscriberRule` still mutates plugin state from Kotlin
     for upgraded vaults. (4) Jobs whose plugin is enabled but whose
     engine THIS build lacks are unclaimable and uncounted
     (`jobs_parked` counts disabled subscriptions only). (5) Pre-key
     naming ops (no origin key, no held op) still await forever —
     no production vault predates the key. (6) Moonshine.kt
     `moonshinePlan` three `when` arms differing only in `notice`;
     `phase==Ready && modelDir!=null` coupled. (7) Plugins.kt
     `providerSwitchWarning` re-derives what `providerSwitch` built;
     `enable` as a behaviour selector; two sites re-derive "another
     transcriber is active". (8) operations.rs `adopt_legacy_envelopes`
     per-row SELECT for columns the batch SELECT could carry;
     `conn_count` duplicates `row_exists`; two inline COUNT(*) checks.
     (9) service.rs `.lock().unwrap_or_else(|e| e.into_inner())` ×21,
     only `roster()` has an accessor. (10) tests: `copy_tree` in
     paired/mod.rs AND migration_v5.rs beside `files_under`. (11)
     SyncAddresses.kt `deviceAddresses` vs SyncSettings.kt
     `pairingAddresses` same shape. (12) `project()` per live object:
     fs::read + 2×sha256 + 4 SELECTs per call from 12 sites, no
     mtime/size pre-check. (13) `wordlessRecordings` re-runs
     recentSources(2000) on EVERY vaultTick while the transcriber
     settings screen is open. (14) `facts()`: two uncached identity-
     file reads + parses per status call. (15) `dial_first` serial 2 s
     per candidate (≤4). (16) `write_blob_chunk_bounded` per-chunk
     stat + open/close (kept deliberately for multi-instance safety).
     (17) `head_verdict` matches a revision id by substring over raw
     payload text (UUID entropy makes false positives negligible).
     (18) `is_damage` matches on &str — no exhaustiveness check; an
     omitted KNOWN kind reads non-damage (unknown-kind = not damage is
     ratified). (19) `GiluneStatus.blobsAwaiting` is read by no shell.
   - **Team-coordination lesson (2026-08-31)**: agents verify against
     the whole tree — a coordinator edit that breaks compilation
     (Main.kt File-vs-String) cost one agent ~40 minutes of retry
     loops. Keep the shared tree compiling at every moment agents are
     live, or gate coordinator edits until they finish.
   - **Dev-run gotcha (machine note)**: `gradlew :composeApp:run` sets
     `compose.application.resources.dir`, and Main.kt prepends it to
     `jna.library.path` — so dev runs load
     `app/composeApp/resources/windows-x64/gilune_ffi.dll` (the staged
     RELEASE dll), not `kernel/target/debug`. After kernel changes,
     restage that dll or dev runs silently execute stale kernel code
     (cost an hour on 2026-08-31). UI probes: `-Pgilune.vault=<dir>`
     and `-Pgilune.probe=note:<rel>|read:<rel>|settings[:section|
     :plugin:<id>]` open a screen for screenshots; probe windows are
     titled "Gilune probe" — automation must only ever kill PIDs of
     windows with that exact title, never anything named Gilune.
   - **Bug found and fixed 2026-08-31** (owner's vault held two
     recordings but one transcript): `enable_whisper` used to create
     the `transcribe` subscription, so a capture made before the model
     finished loading produced an event with **no job** and was never
     transcribed. Registering the plugin during `Gilune::open` now
     subscribes it before any capture is possible. Captures already
     stranded are recovered deliberately with `backfill_jobs`
     (`gilune-cli backfill`, or Settings ▸ Plugins ▸ Catch up missed
     captures) — proven on a copy of the owner's vault: 1 queued,
     1 output, verify clean.
3. ~~Spike S1~~ — closed provisionally 2026-08-30 (see its README). When
   the owner's device arrives: run the instrumented suite on hardware,
   measure the capture-commit/cold-start budgets, first ARM64 execution.
   Machine notes (REWRITTEN 2026-09-04 after the owner reinstalled
   Android Studio): the LIVE SDK is `ANDROID_HOME=C:\Android\android-sdk`
   (emulator 37.1.11, NDK 27.2, platform-tools 37.0.1, platforms 34/36/
   37.0, system image `android-36;google_apis;x86_64`) — the former
   `%LOCALAPPDATA%\Android\Sdk` NO LONGER EXISTS; `app/local.properties`
   (gitignored) points `sdk.dir` at the live SDK. The AVD `gilune-s1`
   was recreated by the coordinator via avdmanager on the API 36
   `google_apis` x86_64 image (pixel_6 profile): Android Studio's Device
   Manager had built it on the **API 37 / Android 17 16 KB-page image**
   (`sdk_gphone16k_x86_64`), which the suite is not pinned to and whose
   16 KB page size rejects any `.so` not 16 KB-aligned (ours are not) —
   ALWAYS check `ro.build.version.sdk` = 36 and `ro.product.model` =
   `sdk_gphone64_x86_64` before judging a run. Boot headless:
   `emulator -avd gilune-s1 -no-window -gpu swiftshader_indirect
   -no-audio -no-boot-anim` (~36 s from snapshot). The owner's
   `scripts/move-android-to-d.ps1` / `verify-android-on-d.ps1` (untracked,
   owner-authored) describe a planned move to `D:\Android` that has NOT
   been run; `kernel/scripts/build-android.ps1` now reads ANDROID_HOME
   first (owner edit). **Emulator after the reinstall (2026-09-04) —
   what went wrong, CORRECTED RECORD.** Four instrumented attempts ran
   zero tests: our app ANRed at startup ("failed to complete startup",
   bytecode verification logged at ~5 bytecodes/s) and twice
   `system_server` crashed the instant instrumentation attached
   (`INSTRUMENTATION_ABORTED: System has crashed`). The coordinator
   FIRST blamed the hypervisor (WHPX on an AMD Ryzen 3900X with Hyper-V
   + WSL running; no AEHD package) — WRONG: the rig ran 769/0 on WHPX
   before, and AEHD is incompatible with Hyper-V, which the owner needs
   for Docker (the failed AEHD install left a STOPPED `aehd` kernel
   service — owner to remove via its `silent_uninstall.bat`). The real
   cause was a FRESHLY CREATED AVD: first-run GMS services (Play
   services ~320 MB, Messaging, Search, Android System Intelligence) on
   a 2–2.5 GB guest → 130–180 MB free and hundreds of MB swapped → our
   app's startup starved → ANR; `system_server` fell over under the
   same pressure. MEASUREMENT TRAP that misled the diagnosis: toybox
   `top -n 1`'s summary line is CUMULATIVE SINCE BOOT (it showed
   "300–500% sys" long after the first-boot dexopt storm) — always read
   the SECOND iteration of `top -n 2 -d 5 -b` for live load. Working
   config: `gilune-s1` = API 36 `google_apis` x86_64, `hw.cpu.ncore=4`,
   `hw.ramSize=3072`, `hw.gpu.enabled=yes`, booted `-no-window -gpu
   swiftshader_indirect -no-audio -no-boot-anim`; after a FRESH AVD's
   first boot let GMS settle ~3 min before the first instrumented run.
   ALSO REAL: `C:\Android\adb.exe` (2016, v1.0.32) is FIRST on PATH and
   fights the SDK's v1.0.41 server on port 5037 ("adb server version
   (32) doesn't match this client (41); killing") — delete/rename it or
   drop `C:\Android` from PATH; always call the SDK adb by absolute
   path. **RESULT on the settled AVD (2026-09-04): instrumented 772
   ran / 0 failed / 12 skipped in 212 s** — the surgical round's two
   new tests executed green, so that round is now fully verified. The
   12 skips = R0's a03 (network) + 11 MODEL-GATED rows (R0 a04–a09,
   MoonshineCaptionTest ×2, MoonshineProviderTest real-engine): the
   seeded Moonshine model at `/data/local/tmp/gilune-r0/models` died
   with the old emulator. To re-seed: fetch the 8 files per
   spikes/r0-moonshine/README.md (45.23 MB, crc32c) and `adb push` them
   to that path, or let the app's consented download run once and copy
   `filesDir/models/moonshine-tiny-streaming-en` out. Until then the
   real-engine rows skip honestly.
4. ~~Owner decisions~~ — all closed 2026-08-30: ADR 0009 ratified, the
   S5 threat-model workshop held
   (`spikes/s5-distribution-policy/threat-model.md`), and the Gilune
   name derivation recorded in `architecture/README.md` § The name.

## Conventions

- Application code follows `architecture/09-engineering-conventions.md`
  (Accepted 2026-08-30).
- Docs use British spelling and the existing terse, contract-like style.
- Commit messages: imperative subject; body says what changed and why;
  spike commits summarise the evidence they add.
- The spike crates are disposable prototypes — clarity over polish — but
  the boundary rules above hold even there.
