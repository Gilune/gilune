# Memory Project

## Purpose
Build an open-source, local-first personal context system that helps people capture thoughts with almost no friction, preserve the original source, and derive useful structure such as threads, tasks, people, decisions, questions, and relationships.

## P0 Product Principle — Frictionless Capture

**Capture must be effectively frictionless.**

The user should be able to capture a thought the moment it occurs, ideally in a single action, without choosing a project, folder, tag, title, object type, or workflow. Organisation and interpretation happen afterwards.

Shorthand: **Tap → speak → done.**

Consequences:
- Capture speed beats organisation accuracy.
- Never require structure before saving.
- Failure must favour preservation: if transcription, AI, sync, or plugins fail, the raw capture is still safely stored locally.

## Architectural Direction

- Local-first: phone and desktop clients work offline.
- Android first; iPhone can follow.
- Desktop support for Windows, macOS, and Linux.
- Cloud/self-hosted server used for sync and optional hosted services.
- Server should be cheap and efficient to run.
- Open source throughout; hosted server offering can coexist with self-hosting.
- Raw captures are immutable source material; transcripts and AI-derived information retain provenance back to source.
- Open/exportable data formats; SQLite is a candidate working store, with Markdown/JSON and standard audio formats for export/interchange.

## Plugin-First Principle

The base application should be a very thin microkernel. First-party features should use the same plugin APIs available to third parties.

**Rule: If we cannot build a first-party feature as a plugin, the plugin architecture is not good enough.**

Likely kernel responsibilities:
- Plugin lifecycle
- Capability/permission system
- Event bus
- Generic object and relationship storage
- Blob storage
- Identity/IDs
- Schema registry
- Configuration and secrets
- Transactions/versioning primitives

Domain features should live outside the kernel, including:
- Voice capture
- Transcription
- Tasks
- Threads
- People
- Decisions
- AI/LLM providers
- Embeddings and semantic search
- Sync transports
- Export/import
- Notifications and reviews

## Initial Plugin Boundaries

Potential provider/extensions:
- Capture providers
- Transcription providers
- Language model providers
- Embedding providers
- Sync providers
- Blob providers
- Import/export providers
- Domain plugins such as Tasks, Threads, People, Decisions
- UI extensions

## Processing Model

Example flow:

Voice capture → raw audio capture → transcription → text/content event → AI/domain processors → proposed tasks/threads/entities/relationships.

No stage should need to know the entire workflow. Plugins consume capabilities/events and produce objects, relationships, events, or proposals.

## Important Safety / Ownership Principle

**AI may interpret your memory, but it must never own your memory.**

Raw source remains available, providers are replaceable, data formats are documented, export is complete, the server can be self-hosted, and clients continue working offline.

## Tiny On-Device Router / Intent Layer

The architecture should include an optional, model-agnostic slot for a very small local language model between transcription and downstream plugins/processors.

Initial candidate: **Needle 2** (or a comparable tiny function-calling / intent-routing model).

Intended responsibilities:
- Classify a short capture or infer its intent.
- Select the appropriate plugin/capability.
- Extract simple structured arguments such as dates, people, project/thread references, or action text.
- Decide whether a capture can be handled locally or should be escalated to a larger model.
- Keep immediate post-capture processing cheap enough to run continuously/on-device without adding visible capture friction.

Example flow:

Voice capture → transcription → tiny edge router → plugin/action OR larger-model escalation.

Design constraints:
- The core must not depend specifically on Needle 2; the router is a replaceable provider/plugin capability.
- The tiny model is not the system's main reasoning engine.
- Confidence should gate behaviour: high-confidence routine actions can proceed; uncertain interpretations should remain proposals or be escalated.
- **The original raw capture/transcript remains canonical and must always be preserved.** A tiny model's interpretation must never replace the source.
- Routing failure must never block capture. "Tap → speak → done" remains the P0 product requirement.

This component fits the plugin-first/microkernel direction: plugins expose capabilities/functions, while the edge router maps natural-language captures onto those capabilities without hard-coding domain intent logic into the kernel.
