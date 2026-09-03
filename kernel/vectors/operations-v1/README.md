# Operation envelope test vectors, version 1

Portable conformance vectors for the Gilune operation envelope — the
versioned public codec ADR 0005 requires and ADR 0019 § 6 places in
`gilune-kernel::operations`. They are plain files, not fixtures compiled
into a test binary, because their purpose is to be readable by an
implementation that is not the Rust one.

`manifest.json` names every file and what a decoder must do with it, so
the set can be checked mechanically.

## The format

An envelope is a single JSON object, encoded **canonically**: compact
(no insignificant whitespace), UTF-8, with object keys in ascending
order at every level. Canonical encoding is not cosmetic — a full
replica forwards a third device's operations byte-exactly, and any
signature added later must verify over the bytes that were signed, so
the same operation must always be the same bytes on every platform.

| Field | Type | Rule |
| --- | --- | --- |
| `version` | integer | Exactly `1`. A higher version halts the stream rather than being guessed at. |
| `op_id` | string | Non-empty, at most 128 characters, no control characters. |
| `device_id` | string | The **originating** device, not the peer that delivered it. Same bounds as `op_id`. |
| `op_seq` | integer | That device's own monotonic sequence, starting at 1. Refused if zero, negative, fractional, or outside `i64`. |
| `kind` | string | Non-empty, at most 64 characters. |
| `target_id` | string | The object the operation is about. Same bounds as `op_id`. |
| `created_ms` | integer | Milliseconds since the epoch, not negative. |
| `payload` | object | Kind-specific. Must be an object. |

Decoders apply a size cap before parsing. The shipped default is in the
low megabytes (`gilune_kernel::limits::Limits::max_envelope_bytes`); the
vectors are checked against a 4096-byte cap so `invalid/oversize.json`
is reachable.

## The kinds

Three kinds are interpreted. Anything else is **preserved and forwarded
verbatim but never applied** (constitution rule 9) — `unknown-kind.json`
is a vector for exactly that, and a decoder that rejects it is wrong.

- **`object-created`** — an object and its first revision, optionally
  with the blob whose bytes follow separately, optionally derived from
  another object. A capture and a processor's output are both this kind;
  they differ in payload, not in name.
- **`object-revised`** — a new revision, naming in `supersedes` exactly
  the heads it replaces. Any head it does not name was concurrent with
  it and survives: this array is where ADR 0005's concurrent-head
  contract lives on the wire.
- **`tombstone`** — the object named by `target_id` is deleted. It names
  in `supersedes` the heads the deleter could see, exactly as a revision
  does; history keeps the object. A head a tombstone does not name was
  written concurrently with the deletion, and a projection keeps that
  file rather than destroying words the deleter never had in front of
  them.

## The origin keys

Two optional payload keys, on `object-revised` and `tombstone` alike:

| Key | Type | Meaning |
| --- | --- | --- |
| `object_origin_device` | string | The device whose `object-created` brought `object_id` into being. |
| `supersedes_origin` | object | Each superseded revision id mapped to the device whose operation carried it. Only ids whose origin the writer knew appear. |

They exist because waiting is only correct while the thing waited for
might still arrive. A revision or a tombstone of an object belonging to a
device the receiver has **revoked** names something that can never come,
and without these keys the receiver cannot know that — it has never seen
the object, so it has no way to look up whose it was. It would halt that
stream for ever, which in a full-replica mesh is one revoked device
silencing a healthy one.

Both keys are **additive and optional**. A decoder must accept an
operation that carries neither: that is what every operation written
before they existed looks like, and `object-revised-concurrent.json` and
`tombstone.json` are kept in the set as exactly that shape.

> Added 2026-09-03. Regenerating this set is a deliberate act — see
> "Using them" below. The two new files were added and the manifest
> rewritten; the thirty-seven files that were already here are
> byte-identical, which is the property that makes this an addition to
> the format rather than a change to it.

`revision.payload` is a **string** containing the object's document, not
a nested object. The document's bytes are what the vault projection
renders and hashes, so they cross unchanged rather than being
re-serialised by every hop.

## Using them

- Every file under `valid/` must decode, and re-encoding the decoded
  result must reproduce the file byte-for-byte.
- Every file under `invalid/` must be refused, for the condition
  `manifest.json` names. "Handled" is not enough: a malformed operation
  must halt a stream, not be half-believed.

The Rust conformance test is
`kernel/crates/gilune-kernel/tests/operation_vectors.rs`. It also
regenerates this set, deliberately behind `--ignored`: these files are a
public format, and changing them must be an act rather than a side
effect of running the suite.
