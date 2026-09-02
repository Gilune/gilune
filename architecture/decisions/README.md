# Architecture Decision Records

ADRs record choices separately from architectural invariants so proposed
technologies can be rejected without weakening the product principles.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-application-shell-and-kernel-language.md) | Compose Multiplatform shell with an embedded Rust kernel | Accepted for v0 (provisional; device budgets assumed) |
| [0002](0002-local-persistence.md) | SQLite core store plus transactional external blobs | Accepted for v0 |
| [0003](0003-plugin-contract-and-runtime.md) | WIT contracts with WebAssembly components where platforms permit | Proposed |
| [0004](0004-durable-event-processing.md) | Transactional events and at-least-once durable jobs | Accepted for v0 |
| [0005](0005-operation-based-sync.md) | Kernel-owned operation sync with an opaque encrypted relay | Proposed |
| [0006](0006-mobile-plugin-and-ui-profile.md) | Bundled mobile execution and declarative third-party UI | Accepted for v0 |
| [0007](0007-markdown-vault-projection.md) | Markdown vault projection over the kernel store; the vault is the export | Accepted for v0 |
| [0008](0008-headless-sync-agent-and-lan-first-sync.md) | Headless desktop sync agent and LAN-first device sync | Proposed |
| [0009](0009-licensing.md) | MIT repository-wide (single licence, amended 2026-09-01), DCO | Accepted |
| [0010](0010-downloadable-plugins-on-store-builds.md) | Downloadable store plugins via the sandboxed wasm runtime under the VM/interpreter carve-outs; bundled stays v0; test submission before promising | Accepted |
| [0011](0011-desktop-shell-structure-and-plugin-owned-ui.md) | Desktop shell regions and a fixed v0 slot list; all non-chrome UI is a plugin contribution; every plugin but the vault projection is removable | Accepted |
| [0012](0012-markdown-editing-model.md) | The Markdown buffer is always the document; rich presentation renders it and never serialises back | Accepted |
| [0013](0013-transcription-lands-in-the-note.md) | Processor outputs may revise their subject; transcription lands in the capture note; attached captures project no note | Accepted |
| [0014](0014-tabs-and-split-panes.md) | Pane tree with per-note editor buffers: tabs, explicit splits, one buffer per note in v0 | Accepted |
| [0015](0015-tasks-plugin.md) | Tasks plugin: `due:`/`done:` plain-text dates and a caret-anchored inline suggester, over new editor and search seams — including a `filter` block that runs the search DSL in a note behind a required `type:`, and slots a plugin may publish for others | Accepted |
| [0016](0016-windows-distribution-and-automatic-updates.md) | Per-user Windows MSI releases from GitHub Actions with signed, user-approved automatic updates | Proposed |
| [0017](0017-mobile-app.md) | The mobile app: capture is the first frame, live-preview editing under a no-reflow law with always-rendered checkboxes, a formatting row that expands to a panel, and widget-started recording | Accepted |
| [0018](0018-realtime-transcription-and-the-second-provider.md) | Realtime transcription: a display-only live caption during recording, Moonshine as a second provider with one active transcriber at a time, and an FFI processor route so a non-Rust engine writes the committed transcript | Accepted |
| [0019](0019-lan-pairing-and-transport.md) | LAN sync: DNS-SD discovery, one SPAKE2-then-Noise pairing protocol carrying both a 6-digit code and a QR, vault-external device identity, and a kernel/sync split behind a `Transport` trait | Proposed (build ratified 2026-09-02) |

A Proposed ADR becomes Accepted only when its named spike passes. An ADR may be
Accepted for v0 ahead of its spike when the decision is forced by principle or
platform policy (0004, 0006); its spike then validates the implementation, not
the need for the decision. Product decisions with no spike are accepted by
owner ratification (0009, 0010). An owner may also accept provisionally on
partial spike evidence with the assumed remainder recorded in the ADR (0001:
device budgets assumed until hardware exists). If a spike fails, record a new
ADR that supersedes the old one; do not edit away the reason the original
choice was considered.

