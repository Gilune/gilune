# ADR 0002: Local Persistence

Status: **Proposed**  
Validation: Spike S2

## Context

The local store must survive abrupt termination, support atomic capture metadata
and durable work, run cheaply on phones and desktops, remain inspectable, and
work without a server. Audio and future documents can be much larger than normal
database rows.

Plugin schemas must be extensible without giving plugins database access or
turning the entire store into an unindexed entity-attribute-value system.

## Proposed decision

- Use embedded SQLite for kernel envelopes, revisions, relationships,
  provenance, operations, events, jobs, schemas, grants, and blob metadata.
- Use explicit relational columns for stable envelope fields and canonical
  schema-validated documents for plugin payloads.
- Store large blob bytes in a private content store outside database rows, with
  staging and reference state transactionally coordinated through SQLite.
- Use FTS5 or another rebuildable projection for local full-text search; do not
  make a search index authoritative data.
- Expose only kernel query/mutation contracts, never raw SQL or database handles.
- Treat encryption-at-rest implementation as a security subdecision to be proved
  with the sync/key-management work.

SQLite documents a stable database format and crash-recovery mechanisms, while
also making clear that a WAL is part of local database state and is not a
multi-device sync mechanism: <https://www.sqlite.org/fileformat.html> and
<https://www.sqlite.org/wal.html>.

## Why this is preferred

- SQLite is mature, transactional, portable, and efficient for the target scale.
- One transactional authority can atomically record data, sync operations,
  events, and jobs.
- External blobs avoid large-row rewriting and support streaming, resumable
  transfer, and independent garbage collection.
- Stable envelopes remain efficiently queryable while unknown plugin payloads
  remain preservable.

## Alternatives considered

### Files and JSON only

Excellent for interchange but poor for atomic multi-record changes, durable job
claims, indexed relationships, concurrency, and crash recovery.

### One database/table per plugin

Gives plugins flexibility but makes sync, export, capability enforcement,
unknown-data preservation, and uninstall safety inconsistent.

### Embedded document database

May simplify document payloads but provides less benefit than SQLite once the
kernel must also maintain causal operations, jobs, relationships, and portable
bindings. It also increases vendor and format dependence.

### Blobs inside SQLite

Simplifies referential atomicity but makes large streaming capture, backup,
partial transfer, and garbage collection less flexible. Small blobs may still be
inlined as an internal optimisation later.

## Consequences

- Blob commit and recovery need careful two-phase coordination.
- Database and blob backups must be treated as one logical snapshot.
- Payload indexes require registration and rebuild logic.
- A physical schema and migration test suite become kernel responsibilities.
- Portable export is separate from the SQLite file and remains documented JSON
  plus standard blob formats.

## Acceptance gate

S2 must repeatedly terminate the process during blob staging, transaction
commit, event creation, blob promotion, job completion, checkpointing, and
migration. After restart, the source is either fully discoverable or clearly
absent; it is never reported saved with missing original bytes, and no committed
event or operation is lost.

