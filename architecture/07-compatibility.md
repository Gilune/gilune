# Compatibility, Migration, and Removal

Status: **Draft v0**

Compatibility is defined independently for kernel APIs, plugin packages, data
schemas, sync protocol, and processing logic.

## Version dimensions

| Dimension | Owner | Meaning |
| --- | --- | --- |
| Kernel API package | Kernel | Callable interface and value contracts |
| Plugin package | Publisher | Executable/resources release |
| Object schema | Type owner | Meaning and validation of a payload |
| Event schema | Event producer | Meaning of an event payload |
| Processing version | Processor owner | Idempotency and provenance of outputs |
| Sync protocol | Kernel implementations | Operation encoding and exchange rules |
| Export format | Project | Portable archive structure |
| Vault format | Project | On-disk Markdown/front-matter layout (ADR 0007) |

Changing one dimension does not automatically change the others.

## API evolution

Contracts are organised into small versioned packages rather than one global
kernel version.

- Additive optional functions or fields use a compatible minor version.
- Removed, reinterpreted, required, or permission-expanding behaviour requires a
  major version.
- Plugins declare an explicit supported range for every imported contract.
- The host performs version and capability negotiation before activation.
- Unknown required imports fail activation; unknown optional features are
  discoverable and can be omitted.
- Behavioural compatibility tests accompany type compatibility.

Before public `1.0`, breaking changes are permitted but require recorded fixture
migrations. After `1.0`, a deprecated major API remains supported for at least
one full stable release train, and removal requires an announced replacement and
data-impact statement.

Generated FFI bindings and the binary they were generated from are one
versioned artifact, produced and shipped together per target. Binding
checksums are target-dependent (S1 finding, 2026-08-30: identical source
produced different API checksums for Windows and Android builds), so a
packaging pipeline must never pair bindings from one build with a binary
from another; the mismatch fails closed at first call, loudly, which is the
intended behaviour and must not be weakened.

## Schema evolution

Schema IDs are permanent and versions are positive integers.

Compatible readers must:

- preserve unknown fields;
- reject invalid writes without discarding the prior revision;
- preserve and sync payloads for unknown schema versions;
- avoid rewriting objects solely because a plugin was upgraded.

A plugin that needs a new representation registers a stepwise deterministic
migration. Migration can be:

- **read-compatible** — new code reads old data and writes the new version only
  when the user/object changes;
- **materialised** — a durable background job writes new revisions with explicit
  migration provenance;
- **projection-only** — rebuilds disposable plugin-private state and does not
  change user data.

Silent destructive in-place migration is prohibited.

## Migration execution

Before activation the host validates the complete migration path and estimates
affected data. Each materialised step is:

- transactional at a bounded batch size;
- restartable and idempotent;
- attributable to plugin and migration versions;
- safe when another device still runs the prior schema reader;
- tested against stored fixtures from every supported source version.

Irreversible migrations require an export/recovery checkpoint and explicit
release policy. A failed migration leaves the old plugin version disabled or
restored; it never leaves half-written current heads hidden from the user.

## Plugin upgrade

Upgrade uses the lifecycle below:

1. inspect new manifest, signature, API ranges, and capability differences;
2. quiesce the old version and release or finish leases;
3. checkpoint private state and validate schema migrations;
4. install and migrate transactionally;
5. activate subscriptions and run health/conformance checks;
6. retain rollback material until the new version passes its probation window.

A new or broadened capability is never granted because an older version had a
narrower related grant.

## Disable and uninstall

Disabling a plugin:

- stops new invocations and subscription claims;
- releases active leases safely;
- retains package, grants, private state, schemas, and user objects;
- leaves opaque objects browsable through generic shell views and export.

Uninstalling a plugin:

- removes executable resources after leases stop;
- revokes grants and secret handles;
- removes disposable indexes and private operational state after any chosen
  recovery window;
- retains registered schema descriptors needed to label existing data;
- retains all user-owned objects, revisions, relationships, provenance, and
  standard-format blobs unless the user separately chooses deletion.

Reinstalling a compatible version can reclaim its schemas and rebuild private
state from committed data.

## Rolling multi-device upgrades

Devices will run different application and plugin versions during normal sync.

- A device stores and forwards operations it cannot interpret.
- New operation kinds require protocol negotiation and cannot be emitted until
  the account/device compatibility policy permits them.
- Schema owners prefer additive read-compatible changes during rolling upgrades.
- Compaction never removes information still required by an active supported
  device frontier.
- A device that has been offline beyond the compatibility window must perform a
  snapshot/bootstrap upgrade rather than applying an unsafe partial history.

## Backup and recovery compatibility

Every release capable of migrating data includes fixtures and tests proving it
can:

- open the previous supported database versions;
- resume an interrupted migration;
- restore a pre-migration recovery copy;
- export unknown plugin data;
- rebuild disposable indexes;
- verify blob integrity and report missing bytes without deleting metadata.

## Deprecation record

Every deprecation states:

- affected contract, schema, or protocol versions;
- last version that can produce and consume it;
- replacement path;
- data and sync consequences;
- deadline and minimum supported offline-device behaviour;
- recovery/export instructions.

