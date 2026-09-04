# Plugin System

Status: **Draft v0**

The plugin model is contract-first. Packaging and execution may vary by platform,
but capability, data, event, provenance, and compatibility rules do not.

## Plugin classes

### Domain plugins

Own schemas, processors, commands, and declarative views for features such as
tasks, people, threads, and decisions.

### Provider plugins

Implement replaceable capabilities such as transcription, language models,
embeddings, routing, blob transport, and sync transport.

Capture content leaving the device is consent-gated: **local-only is the
default** (threat-model workshop, 2026-08-30). A provider that sends
capture content off-device requires explicit per-provider consent with
plain disclosure of what is sent and to whom; nothing leaves the vault
silently. The first-party v0 transcription provider runs locally (on the
capturing device or a paired coordinator), never in the cloud.

**One provider of a replaceable capability is active at a time** (ADR 0018,
2026-09-02). Two providers of one capability is otherwise an unmodelled state:
sharing a processor id, one silently wins and disabling either disables both;
with distinct ids both run, and the slower engine wins the object's head. The
rule is enforced by the host — the shell enables exactly one, and a disabled
plugin's queued jobs park rather than die — while the kernel learns nothing
about what the capability is. Making it a kernel invariant (at most one enabled
plugin per declared capability name) is deferred until a second capability has a
second provider.

### Platform adapters

Trusted application components that implement narrow OS capabilities such as
audio recording, file selection, notifications, background scheduling, and
secure-key storage. They are not general-purpose plugins and do not own domain
schemas.

### Index and projection plugins

Build disposable, rebuildable views such as full-text indexes, embeddings, or
domain-optimised projections from committed objects. The first-party Markdown
vault projection (ADR 0007) is the flagship member of this class and the basis
of the export contract.

Most of them need no index of their own. A plugin that describes the
vault registers a **`NoteIndexer`** instead (ADR 0020 § 2.3,
`contracts/indexer/`): a pure function from a note's text to facts about
anchors, which the kernel stores in one system index under that plugin's
namespace and maintains from its own change feed. Being pure over text,
it needs no vault access at all — which is what will let a sandboxed
downloaded plugin extend the index. Two indexers that have never heard of
each other join on the anchor, and removing a plugin drops its namespace
and leaves the notes (the removal rule below). A private index remains
allowed for data that is not a fact — an embedding index — under the same
feed-and-cursor pattern; facts are the rule and a private index is the
exception.

## Manifest

Every plugin has a signed, immutable manifest. The following is illustrative;
the final schema will be generated from the contract definitions.

```toml
manifest_version = 1
id = "org.memory.tasks"
name = "Tasks"
version = "0.1.0"
publisher = "org.memory"
kernel_api = ">=0.1.0,<0.2.0"
execution = "component"

[[schemas]]
type = "org.memory.tasks/task"
version = 1
resource = "schemas/task-v1.json"

[[subscriptions]]
event = "org.memory.router/action-proposed@1"
handler = "create-task-proposal"
run_on = "origin-device"

[[capabilities]]
name = "objects.read"
types = ["memory.capture/source", "org.memory.transcription/transcript"]

[[capabilities]]
name = "objects.write"
types = ["org.memory.tasks/task"]

[[ui]]
slot = "object.detail.actions"
kind = "command"
command = "convert-to-task"
```

The manifest also records package hashes, signatures, WIT/API package versions,
resource limits, migrations, exported commands, settings schema, supported
platforms, and optional network destinations.

## Capability model

Capabilities are denied by default and scoped as narrowly as practical.

Initial families include:

- `objects.read` and `objects.write`, restricted by type and class;
- `relations.read` and `relations.write`, restricted by relationship type;
- `blobs.read` and `blobs.write`, restricted to referenced or newly staged blobs;
- `events.emit` and `events.subscribe`, restricted by namespaced event types;
- `network.request`, restricted by scheme, destination, method, and purpose;
- `secrets.use`, restricted to named opaque handles and provider operations;
- `platform.audio-record`, `platform.files`, `platform.notifications`, and other
  mediated OS capabilities;
- `processing.schedule`, constrained by execution policy and resource limits;
- `ui.contribute`, restricted to supported declarative slots;
- `plugin-state`, restricted to the plugin's private operational namespace.

A plugin receives a capability handle, not a global kernel client. The host
validates every call against the installed manifest and current grant.

A standard-library component implicitly imports WASI interfaces (io, clocks,
random, cli) even when its own world imports only the plugin contract (S3
finding, 2026-08-29). The host therefore always supplies a deny-by-default
WASI context — no filesystem, network, or environment, discarded stdio — and
any broader ambient facility must be a declared, granted capability. The
manifest schema accounts for this implicit surface; "innocent" standard
library use is itself a capability question.

High-risk grants can require per-use confirmation. Revocation affects future
calls and waiting jobs but does not retroactively delete user-owned outputs.

## Secrets

Plugins declare the kind of credential they need. The shell collects it using a
trusted UI and stores it in platform secure storage. Plugins receive an opaque
handle usable only through a specific provider/network operation.

Secret plaintext is never placed in:

- plugin settings documents;
- events, job payloads, or processing provenance;
- logs and diagnostic exports;
- sync operations or portable data exports.

## Lifecycle

The lifecycle is:

1. **inspect** — validate manifest, signature, API ranges, schemas, and package
   integrity without executing code;
2. **plan** — show requested capabilities, migrations, conflicts, and platform
   support;
3. **install** — atomically register package metadata and schemas;
4. **grant** — record user/admin capability decisions;
5. **activate** — register subscriptions, commands, and views;
6. **run** — execute through capability handles and resource limits;
7. **quiesce** — stop new work and finish or release leases for upgrade;
8. **upgrade/rollback** — apply compatible migrations transactionally;
9. **disable** — prevent execution while retaining installation and user data;
10. **uninstall** — remove executable/private state and grants, retaining
    user-owned objects as opaque data unless separately deleted by the user.

## Execution profiles

### Bundled profile

Used for mobile-store builds and trusted first-party features. Implementations
are compiled or packaged with the application but register through the same
manifest and generated contracts. Conformance tests verify that they do not use
private storage or data routes. Bundled domain-plugin modules depend on the
generated plugin SDK, not on kernel storage implementation modules; the build
must enforce this dependency direction.

### Sandboxed component profile

The preferred installable desktop/server profile. A WebAssembly component imports
only granted WIT capabilities. The host sets memory, CPU/fuel, output, and network
limits and can terminate the component without corrupting the kernel.

### Trusted native bridge profile

Reserved for reviewed desktop integrations that genuinely require an OS or SDK
facility unavailable through portable capabilities. It runs out of process where
possible, is clearly labelled as trusted, and is never the default third-party
format.

Mobile and desktop builds may expose different installation choices. That is a
distribution fact, not a difference in the logical plugin API.

## UI extensions

Third-party v0 plugins contribute declarative UI rather than arbitrary executable
widgets. Supported contribution types are expected to include:

- commands and command-palette entries;
- object list filters and saved queries;
- object detail cards built from approved field components;
- settings forms generated from a schema;
- capture and review actions;
- badges, status summaries, and navigation destinations composed from standard
  layouts.

The shell owns rendering, accessibility, navigation, and permission prompts.
First-party plugins should use these slots wherever they are sufficient. New
slots are added based on real first-party pressure rather than allowing arbitrary
UI code prematurely.

## Schema ownership

A plugin may mutate only types it owns or types whose owner exposes a specific
command/capability. It may always create its own derived object with provenance
pointing to a readable input.

Schema names are permanent and publisher-namespaced. Ownership transfer requires
a signed migration agreement and an explicit ADR or user-approved installation
plan.

The `memory` namespace is deliberately project-neutral and permanent: it
predates two product renames (Memory Project → Linoema → Gilune), and permanent
names must not chase product branding. Kernel- and platform-produced contracts
live under `memory.*` (for example `memory:objects`, `memory.kernel/…`,
`memory.capture/…`); plugin-owned types and events live under their publisher's
namespace, such as `org.memory.*` for first-party plugins.

## Supply-chain rules

- Packages and manifests are content-addressed and signed.
- Publisher identity and trust source are visible before installation.
- Updating cannot add a capability silently.
- Capability expansion requires a new grant.
- Revoked or known-vulnerable packages can be quarantined without deleting data.
- Reproducible builds and a public transparency log are desirable marketplace
  properties but are not kernel prerequisites for the first vertical slice.
