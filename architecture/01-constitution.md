# Architecture Constitution

Status: **Baseline**

These rules take precedence over convenience, individual technologies, and
plugin implementation preferences.

## 1. Capture is preservation-first

A capture is successful when its original bytes and minimum metadata have been
durably committed locally. Transcription, classification, sync, indexing, AI,
and plugin availability are not part of the capture commit.

The capture path must never require a network, account, model, plugin choice,
title, folder, tag, or inferred domain type.

## 2. Source and interpretation are different kinds of truth

Original source material is never silently edited or replaced. Corrections,
transcripts, summaries, tasks, links, and other interpretations are separate
artifacts with provenance back to their inputs.

“Immutable” means append-only and revision-preserving. It does not remove the
user's right to delete data explicitly.

## 3. The product is client-first and locally useful

Core capture, browsing, export, and recovery work without a server. A server
may relay encrypted data, coordinate optional work, or provide hosted services,
but it is not the owner of the only usable copy.

Android and Windows are co-primary initial clients because phones and computers
are where capture and work happen, including voice and future image workflows.
The architecture must not reduce either to a thin companion for a web
application. macOS, Linux, and later iOS remain first-class targets built on the
same portable contracts.

## 4. The kernel owns invariants, not features

The kernel contains only mechanisms that every feature must rely on to preserve
data, security, compatibility, and offline behaviour. It contains no knowledge
of tasks, people, threads, decisions, prompts, model providers, or other domain
features.

A proposed kernel addition must answer: “What user-data invariant becomes
impossible to enforce if this remains outside the kernel?” If there is no strong
answer, it remains a plugin or platform concern.

## 5. First-party features exercise the public contracts

First-party domain features use the same object, event, capability, provenance,
and schema contracts offered to third-party plugins. First-party code may be
bundled or statically linked where a platform requires it, but it must not gain
an undocumented route to user data.

Platform adapters such as microphone recording, secure-key storage, background
scheduling, and notifications are trusted capability implementations rather
than domain-feature exceptions.

## 6. Plugins are replaceable and least-privileged

A plugin receives only declared and user-approved capabilities. It cannot open
the database, enumerate the filesystem, retrieve raw secrets, or make arbitrary
network calls unless an explicitly granted capability permits the operation.

Disabling or uninstalling a plugin revokes execution and access. It does not
delete user-owned objects or make export dependent on that plugin.

## 7. Committed facts and unfinished work are durable

Data changes, emitted events, and scheduled processing are recorded
transactionally. Work may run more than once, so handlers must be idempotent.
The architecture promises at-least-once processing, not exactly-once execution.

Failures are inspectable, retryable, and quarantinable. A failed processor never
rolls back or blocks the original capture.

## 8. Sync meaning is transport-independent

The kernel defines operation identity, causal history, conflicts, tombstones,
and application of remote operations. A sync plugin may choose how bytes move,
but not what those bytes mean.

The system never syncs by copying an open database or SQLite WAL between
devices.

## 9. Unknown data remains preservable

A device may receive an object whose plugin or schema is unavailable. It must be
able to store, sync, back up, and export the opaque payload and its envelope
without understanding it.

## 10. Security and privacy fail closed

Permission absence, invalid signatures, unknown API versions, corrupt payloads,
and unavailable key material deny the requested operation without endangering
already committed data.

Secrets are represented to plugins by scoped handles. Sensitive plaintext is
not written to logs, event payloads, manifests, or sync metadata.

## 11. Portability is continuously testable

Documented export contains all user-owned sources, standard-format blobs,
derived artifacts, relationships, provenance, timestamps, and tombstones needed
to reconstruct meaning. Export cannot be an aspirational end-of-project feature.

## 12. Compatibility is a feature

Public API versions, schema versions, plugin versions, processing versions, and
sync protocol versions are distinct. Upgrade and downgrade consequences are
explicit. Migrations are transactional, restartable, and tested with older data.

## 13. Openness is a goal, not a concession

Interoperability with third-party tools is supported use, not tolerated misuse.
User-owned data lives in documented, versioned formats that other software can
read without this project's code, and no feature may depend on the stored form
being opaque. Leaving the product must never require more than copying the
user's own data.
