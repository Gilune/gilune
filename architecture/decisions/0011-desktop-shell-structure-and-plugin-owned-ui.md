# ADR 0011: Desktop Shell Structure and Plugin-Owned UI

Status: **Accepted** (owner ratification 2026-08-31, design session)  
Validation: owner ratification — this is a product and shell-structure
decision, not a spike. The boundary suite and vault tests keep the
underlying invariants honest; nothing here weakens them.

## Context

M3 shipped a desktop shell adequate to prove the FFI boundary, not to use
daily: capture owns half the window, the file tree cannot be collapsed or
added to, the settings dialog holds one text field, and the microphone and
the transcription bootstrap are hardcoded shell chrome.

That last point is the structural fault. ADR 0006 binds third-party UI to
shell-rendered declarative slots, but the first-party app has been
contributing UI by hardcoding it. The consequences are concrete: a plugin
cannot be removed without leaving dead controls, and the capabilities a
plugin uses — including a 75 MiB model download on first run — are
invisible to the user because no surface owns them.

## Decision

### Layout

- Three regions: a navigation rail on the left, a document pane on the
  right, and a persistent capture bar across the bottom. Capture stays
  one keystroke away on desktop without owning half the window.
- The rail lists the vault tree, with creation, rename, delete and reveal
  acting relative to the selected folder.
- Views (inbox, triage queues) are **not modelled**. Triage state that
  must survive sync is a data-model change, not a UI decision. The
  `Captures/` folder already behaves as an inbox that third-party tools
  honour, because reconcile matches files by front-matter ID and follows
  moves.
- The rail's upper region is a declared **navigation slot** that renders
  nothing while empty.

### Slots

The v0 desktop slot list is fixed at five: navigation destination,
capture action, note action, command-palette entry, settings form. New
slots require real first-party pressure and an amendment here (ADR 0006).

### Plugin-owned UI

- Every control beyond the shell's own chrome is a plugin contribution,
  rendered by the shell from the plugin registry. Disabling or removing a
  plugin removes its contributions.
- The plugin settings section shows, per plugin: class and execution
  profile, the permissions its manifest declares, the events it subscribes
  to and emits, and what it adds to the app. Contributions are listed
  because they are what makes removal legible before it is clicked.
- Every plugin except the Markdown vault projection can be disabled and
  removed. The projection is required: the vault is the product surface.
- Removal deletes the plugin's settings, grants and downloaded assets,
  parks its waiting jobs, and **retains every object it created** as
  ordinary user data (plugin lifecycle step 10).
- While a plugin is off — disabled or removed — nothing new is queued for
  it, and jobs already waiting are claimable by nobody. Switching it on
  resumes the work that was waiting; it does not re-run what happened
  while it was off. Backfill stays a deliberate action, which is what a
  re-run action such as `Transcribe again` is for.
- Network use is disclosed and consented in this section before it
  happens. The transcription model download is the first case and is
  currently silent; that is a defect this decision closes.

### Voice capture and transcription are separate plugins

An audio-capture platform adapter owns the microphone and produces audio
sources. The transcription provider owns models, transcription actions and
network use, and consumes those sources.

Capture must never depend on an interpreter (constitution rule 1): a
recording is a preserved source whether or not anything can transcribe it.
The split is what allows transcription to be removed without removing
voice capture, and it is already the shape Android runs under — it
captures, and a paired coordinator transcribes.

### State ownership

Plugin enablement, grants and settings are **vault facts** held in the
kernel store, so the CLI and the headless agent (ADR 0008) observe the same
state as the app. Device-local preferences — vault path, theme, editor
defaults — stay device-local.

### Proposals raised by deletion

Deleting a note raises a tombstone proposal rather than destroying data.
A deletion performed in the app resolves its proposal inline at the point
of deletion. A proposal raised outside the app surfaces as a status
banner until answered. Neither requires a views section.

## Alternatives considered

### Keep the microphone as shell chrome

Simpler, and it is what the code does today. Rejected: either a removed
plugin leaves a dead control, or the shell quietly owns capability
decisions that ADR 0006 assigns to plugins.

### Bind the microphone to the transcription plugin

The literal reading of "remove the plugin, remove its buttons". Rejected:
it makes voice capture depend on an interpreter, contradicting rule 1, and
would leave Android — where transcription never runs — with no microphone.

### Model views now

An inbox or triage queue implies a per-source "handled" fact that must
sync, which is an operation type and a data-model change. Deferred until
there is a reason stronger than a badge.

### A separate settings window

Rejected for v0: a single window keeps shell state simple, and in-window
settings is the prevailing desktop pattern.

## Consequences

- The kernel gains a plugin registry — identity, class, profile,
  enablement, grants, settings — and gates job delivery on it. This is a
  schema migration and the prerequisite for everything else here.
- The FFI grows plugin listing, enablement and removal; the shell stops
  calling `enable_whisper` directly.
- The capture panel is shared with Android, so the microphone becomes a
  contribution there too, and platform availability becomes visible rather
  than implicit.
- Until proposals are surfaced, the tree can create state no UI can
  answer; shipping tree deletion therefore requires shipping resolution.
- Editing behaviour is decided separately in ADR 0012.

## Amendments

**2026-08-31 (owner):** the desktop drops the persistent capture bar.
Capture-first survives as gestures rather than a region: **Ctrl+N**
creates an Untitled note ready to type (Obsidian's naming — Untitled,
Untitled 1, …, renamed from the note's own title), and the top bar
carries the voice-capture plugin's new-note-from-speech microphone. The
capture bar remains the Android surface unchanged. The rail also gains a
second panel: Files and Search behind a small toggle (VSCode's shape),
with **Ctrl+F** focusing vault-wide search; Ctrl+P stays quick-open.

**2026-08-31 (owner):** the rail is resizable by dragging its divider
and collapsible — a top-bar toggle, **Ctrl+B**, or dragging the divider
shut. Width and collapsed state are device-local facts like the theme
and persist in the shell's preferences. A gesture that needs the rail
(Ctrl+F) reopens it.
