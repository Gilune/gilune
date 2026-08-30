# Architecture Decision Records

ADRs record choices separately from architectural invariants so proposed
technologies can be rejected without weakening the product principles.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-application-shell-and-kernel-language.md) | Compose Multiplatform shell with an embedded Rust kernel | Accepted for v0 (provisional; device budgets assumed) |
| [0002](0002-local-persistence.md) | SQLite core store plus transactional external blobs | Proposed |
| [0003](0003-plugin-contract-and-runtime.md) | WIT contracts with WebAssembly components where platforms permit | Proposed |
| [0004](0004-durable-event-processing.md) | Transactional events and at-least-once durable jobs | Accepted for v0 |
| [0005](0005-operation-based-sync.md) | Kernel-owned operation sync with an opaque encrypted relay | Proposed |
| [0006](0006-mobile-plugin-and-ui-profile.md) | Bundled mobile execution and declarative third-party UI | Accepted for v0 |
| [0007](0007-markdown-vault-projection.md) | Markdown vault projection over the kernel store; the vault is the export | Proposed |
| [0008](0008-headless-sync-agent-and-lan-first-sync.md) | Headless desktop sync agent and LAN-first device sync | Proposed |
| [0009](0009-licensing.md) | MIT OR Apache-2.0 repository-wide, DCO | Accepted |
| [0010](0010-downloadable-plugins-on-store-builds.md) | Downloadable store plugins via the sandboxed wasm runtime under the VM/interpreter carve-outs; bundled stays v0; test submission before promising | Accepted |

A Proposed ADR becomes Accepted only when its named spike passes. An ADR may be
Accepted for v0 ahead of its spike when the decision is forced by principle or
platform policy (0004, 0006); its spike then validates the implementation, not
the need for the decision. If a spike fails, record a new ADR that supersedes
the old one; do not edit away the reason the original choice was considered.

