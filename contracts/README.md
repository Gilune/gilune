# Gilune Contracts

The public, versioned contracts third parties build against: WIT
packages, manifest and payload schemas, the vault format, and protocol
test vectors. Everything here is MIT-licensed
(ADR 0009) and versioned per `architecture/07-compatibility.md`.

Two standing rules:

- **Contract namespaces are project-neutral.** The permanent namespaces
  are `memory` / `org.memory`; they predate the Gilune name and must not
  chase it or any future rename.
- A contract lands here only with conformance tests; the spike WIT
  package (`spikes/s3-plugin-contract/wit/`) is the ancestor of the
  first processor contract but migrates through review, not by copy.

Populated from vertical-slice milestone M4 (plugin contract) and M5
(sync test vectors) onward.

- [`processor/`](processor/README.md) — interpreting an object and
  proposing outputs the kernel commits (ADR 0003, 0013, 0018).
- [`indexer/`](indexer/README.md) — describing a note as facts about
  anchors, which the kernel stores and joins (ADR 0020). Its WIT half is
  deferred; the README is the contract meanwhile.
- [`search/`](search/README.md) — complete indexed result pages with
  plugin-owned vocabulary and kernel-owned execution (ADR 0021).
- [`bundled-extensions/`](bundled-extensions/README.md) — executable bundled
  search, suggestion-provider and indexer registrations (ADR 0022).
