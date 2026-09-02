# Processor contract (org:memory, v0.1)

The seam between the kernel and processors. Three execution profiles
share it (ADR 0003, ADR 0006, ADR 0018 § 2):

- **Bundled** — a Rust implementation of
  `gilune_kernel::processor::Processor`, statically linked (the store
  builds' only profile, per ADR 0006). The trait mirrors
  [`wit/processor.wit`](wit/processor.wit) member for member:
  `output-target` is `processor::OutputTarget`, kebab-case for
  camel-case, and `proposed-output.target` is its `target` field.
- **Sandboxed** — a WebAssembly component of the `processor` world,
  capability-checked by the host (validated in Spike S3; product host
  lands when ADR 0003 flips to Accepted).
- **External over the FFI** — an implementation carried by the host
  application, in whatever language it is written, reaching the same
  contract through four boundary calls (ADR 0018 § 2):
  `register_external_processor`, `claim_job`, `submit_output`,
  `fail_job`. **The same contract in a different transport, not a
  different contract**: the run record, the derived IDs, the
  `processor:{id}` origin, the operation and the event are the kernel's
  and are identical, so an output submitted this way *is* a processor
  output (ADR 0013's 2026-09-02 amendment). It is **not** the sandbox
  and confers nothing on store builds — downloadable still means the
  wasm profile (ADR 0010).

  One deliberate difference of transport, recorded rather than hidden:
  `host-objects.subject-blob` returns the bytes whole, while the FFI
  route streams them chunk by chunk, digest-verified before the first
  chunk crosses. The promise is the same — a processor never sees bytes
  the kernel cannot vouch for — but a boundary copy of a whole recording
  is what ADR 0001 forbids, so the shape differs.

Contract obligations, all profiles:

- at-least-once delivery: handlers are idempotent, and the kernel
  additionally guarantees one committed run per (processor, idempotency
  key);
- outputs are proposals — the kernel commits them, with provenance for a
  `new-object` and as a head-replacing revision of the subject for a
  `revise-subject` (ADR 0013); a processor never writes;
- `revise-subject` outputs carry no type of their own: `type-id` and
  `type-schema` are ignored, the revision's origin is `processor:{id}`,
  and the change is announced as `org.memory.revision-added` — the same
  operation and event shapes an external edit uses, so sync semantics do
  not change;
- deny-by-default: the host grants only the imports the manifest
  declares, and a standard-library component's implicit WASI imports get
  an empty context (05-plugin-system § Capability model);
- behavioural parity between profiles is a conformance-test requirement
  before the sandboxed profile ships (S3 proved byte-identical outputs
  are achievable).

Versioned per `architecture/07-compatibility.md`; additive change only
within 0.1.x.
