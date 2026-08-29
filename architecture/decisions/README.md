# Architecture Decision Records

ADRs record choices separately from architectural invariants so proposed
technologies can be rejected without weakening the product principles.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-application-shell-and-kernel-language.md) | Compose Multiplatform shell with an embedded Rust kernel | Proposed |
| [0002](0002-local-persistence.md) | SQLite core store plus transactional external blobs | Proposed |
| [0003](0003-plugin-contract-and-runtime.md) | WIT contracts with WebAssembly components where platforms permit | Proposed |
| [0004](0004-durable-event-processing.md) | Transactional events and at-least-once durable jobs | Accepted for v0 |
| [0005](0005-operation-based-sync.md) | Kernel-owned operation sync with an opaque encrypted relay | Proposed |
| [0006](0006-mobile-plugin-and-ui-profile.md) | Bundled mobile execution and declarative third-party UI | Accepted for v0 |
| [0007](0007-markdown-vault-projection.md) | Markdown vault projection over the kernel store; the vault is the export | Proposed |

An ADR marked Proposed becomes Accepted only when its named spike passes. If the
spike fails, record a new ADR that supersedes it; do not edit away the reason the
original choice was considered.

