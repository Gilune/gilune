# Gilune

A local-first, open, capture-first personal memory system.

**Tap → speak → done.** Capture is preservation-first and frictionless: the
original recording is durably saved before anything else is allowed to
matter. Your data lives in an Obsidian-openable vault of Markdown and
original media, projected from a small transactional kernel. Features —
transcription, tasks, people, threads — are plugins behind capability
contracts. Devices sync by exchanging immutable operations: LAN-first, any
dumb file store as a carrier, servers optional and self-hostable. AI may
interpret your memory; it must never own it.

**Status: pre-product.** Architecture and validation spikes; no application
yet.

- Product principles: [`sources/README.md`](sources/README.md)
- Architecture package: [`architecture/README.md`](architecture/README.md)
  — constitution → contracts → ADRs → spike plan
- Evidence so far: [`spikes/`](spikes/) — durable capture and vault
  projection (S2), plugin contract and sandbox (S3), sync convergence
  (S4), distribution policy (S5). Each spike README states its evidence
  and its honest gaps.
- Session onboarding and next steps: [`CLAUDE.md`](CLAUDE.md)

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or
[MIT license](LICENSE-MIT) at your option
([ADR 0009](architecture/decisions/0009-licensing.md)). Unless you
explicitly state otherwise, any contribution intentionally submitted for
inclusion in this work by you, as defined in the Apache-2.0 license, shall
be dual licensed as above, without any additional terms or conditions.
