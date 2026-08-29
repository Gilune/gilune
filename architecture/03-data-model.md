# Durable Data Model

Status: **Draft v0**

This document defines the logical records the kernel must preserve. It does not
fix the storage encoding or physical SQLite schema; those remain implementation
details provided they satisfy these contracts.

## Core terminology

- **Source**: original user input or imported original material, such as audio,
  text, an image, or a document.
- **Artifact**: a user-created or derived object, such as a transcript, task, or
  summary.
- **Proposal**: a derived artifact that has not been accepted as an action or
  authoritative correction.
- **Relationship**: a typed edge between objects.
- **Revision**: one immutable version of an object or relationship.
- **Operation**: a syncable statement that creates a revision, relationship,
  tombstone, or other durable change.
- **Processing run**: a durable record of a processor consuming inputs and
  producing outputs.

## Identifiers

Identifiers are globally unique, opaque 128-bit values. UUIDv7 is the proposed
initial representation, but callers must not infer authority or causal order
from its timestamp portion.

Separate IDs exist for objects, revisions, relationships, blobs, events,
transactions, operations, devices, plugins, and processing runs. An operation
also carries a device-local monotonic sequence so missing operations can be
detected without trusting a clock.

IDs are never reused, including after deletion.

## Object envelope

Every source, artifact, and proposal uses a kernel-owned envelope conceptually
equivalent to:

```text
object_id           opaque ID
type_id             namespaced type, for example org.memory.tasks/task
schema_version      positive integer owned by the type's plugin
class               source | artifact | proposal
schema_owner        plugin ID
created_operation   operation ID
current_heads       one or more revision IDs
origin_device       device ID
display_created_at  user/device-supplied timestamp, not conflict authority
tombstone           absent or deletion metadata
payload             opaque schema-validated value
```

The kernel can preserve and sync an envelope whose `type_id` or schema is
unknown. Understanding the payload is not required for storage, export, or
tombstoning.

The initial interoperable payload representation should be canonical JSON with
JSON Schema. “Canonical” must mean a pinned canonicalisation scheme — RFC 8785
(JCS) is the default candidate — because integrity digests and idempotency keys
hash payloads and must agree across languages and devices; the encoding ADR
fixes the choice. Canonical CBOR remains an optimisation candidate after the
API and export semantics are proved; storage-specific SQLite JSONB is not an
interchange format.

## Revisions and concurrent heads

Revisions are immutable. Updating an object creates a new revision that names
the heads it was based on.

- One head means the object has a single current revision.
- Multiple heads mean concurrent changes exist.
- The kernel preserves all heads until a domain merge or explicit user choice
  produces a revision that supersedes them.

The default kernel behaviour is conflict preservation, not last-write-wins.
Plugins may register a deterministic merge function for types they own. Devices
without that plugin continue to preserve the heads as opaque data.

## Sources

A source object's original payload and referenced original blobs never receive
an in-place correction. Later interpretation is represented by another object
and provenance links.

Minimum audio-capture commit:

```text
source envelope
audio blob reference
origin device
capture start/end display timestamps, if known
media type and byte length
integrity digest
```

Transcription language, inferred title, people, tasks, and model results are not
required to commit the source.

## Relationships

Relationships are first-class, revisioned records:

```text
relationship_id
type_id
schema_version
from_object_id
to_object_id
current_heads
payload
provenance
tombstone
```

Examples include `derived-from`, `mentions`, `belongs-to-thread`, and
`supersedes`. Relationship types are namespaced and schema-owned just like
object types.

The kernel understands only a small reserved set needed for preservation and
provenance. It does not give domain meaning to arbitrary relationship types.

## Provenance

Every derived or proposed artifact records:

- input object revision IDs and, where relevant, blob IDs or byte/time ranges;
- the processing run ID;
- processor plugin ID and version;
- processor configuration fingerprint;
- model/provider identifier when one was used;
- creation operation and device;
- optional confidence and human-review state;
- causation and correlation IDs linking the processing chain.

Provenance is append-only. Accepting a proposal records a decision or creates a
new artifact; it does not erase the proposed origin.

## Processing runs

A processing run is durable operational evidence rather than domain content. It
contains input references, processor version, idempotency key, status, output
references, start/end display timestamps, and a redacted error record.

Prompt bodies and sensitive provider responses are stored only when an explicit
retention policy permits them. Credentials are never included.

## Blobs

Large binary data is stored outside object payloads behind the blob contract.

Blob creation uses a recoverable sequence:

1. Stream bytes to a private staged location while calculating length and an
   integrity digest.
2. Flush and close the staged bytes.
3. Commit the object, blob metadata, and reference in one kernel transaction.
4. Atomically promote the staged blob or record it as ready.
5. On startup, reconcile abandoned stages and committed-but-unpromoted blobs.

A capture is not reported as saved until the minimum committed state can be
recovered after process termination.

Blob IDs are externally opaque. Deduplication, plaintext hashes, encryption
layout, and remote object keys are not exposed in public IDs.

## Conceptual kernel stores

The proposed local implementation contains logical stores for:

- objects, revisions, and current heads;
- relationships and relationship revisions;
- provenance and processing runs;
- blobs and references;
- operations and per-device sequence state;
- events, subscriptions, jobs, and attempts;
- schema registrations;
- plugin installations, grants, and private state;
- tombstones, device acknowledgements, and garbage-collection eligibility.

This is not an entity-attribute-value model. Stable envelope fields are explicit
and indexable; plugin payloads are typed documents with optional registered
indexes or projections.

## Query contract

The kernel query API supports bounded operations over envelope fields, type IDs,
relationships, provenance, time ranges, and registered payload indexes. It does
not expose arbitrary SQL.

Unknown payloads remain retrievable by ID and exportable. Full-text and semantic
search are replaceable services that build rebuildable indexes from committed
data.

## Deletion and retention

Deletion is explicit and has three phases:

1. A tombstone becomes the current durable state and syncs to other devices.
2. User interfaces and processors cease normal use of the deleted content.
3. Blob bytes and superseded revisions become eligible for physical erasure
   after retention, device-acknowledgement, backup, and legal-policy conditions
   are satisfied.

Deleting a source does not leave a hidden copy inside an event payload, log,
index, or model cache. Derived objects whose source was deleted retain a marker
that their provenance target was deleted, unless the user also deletes them.

## Export contract

The baseline export is a copy of the vault projection (ADR 0007) taken with the
store quiescent, plus an integrity manifest. Together they contain:

- a documented representation of every user-owned envelope and revision —
  Markdown with front matter for the human-readable layer, with the manifest
  carrying anything that layer cannot;
- relationships and provenance;
- original blobs in documented standard formats;
- type and schema identifiers, even if the plugin is unavailable;
- deletion records when needed to reconstruct sync or backup state;
- a manifest of versions, integrity digests, and omitted operational data.

Indexes, retry attempts, cached provider responses, capability grants, and
secrets are not user data and need not be included in the portable export.

