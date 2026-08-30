# Processor contract (org:memory, v0.1)

The seam between the kernel and processors. Two execution profiles share
it (ADR 0003, ADR 0006):

- **Bundled** — a Rust implementation of
  `gilune_kernel::processor::Processor`, statically linked (the store
  builds' only profile, per ADR 0006). The trait mirrors
  [`wit/processor.wit`](wit/processor.wit) member for member.
- **Sandboxed** — a WebAssembly component of the `processor` world,
  capability-checked by the host (validated in Spike S3; product host
  lands when ADR 0003 flips to Accepted).

Contract obligations, both profiles:

- at-least-once delivery: handlers are idempotent, and the kernel
  additionally guarantees one committed run per (processor, idempotency
  key);
- outputs are proposals — the kernel commits them as interpretation
  objects with provenance (constitution rule 2); a processor never
  writes;
- deny-by-default: the host grants only the imports the manifest
  declares, and a standard-library component's implicit WASI imports get
  an empty context (05-plugin-system § Capability model);
- behavioural parity between profiles is a conformance-test requirement
  before the sandboxed profile ships (S3 proved byte-identical outputs
  are achievable).

Versioned per `architecture/07-compatibility.md`; additive change only
within 0.1.x.
