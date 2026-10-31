# ADR 0029: Transcribing again, loading recordings, and the desktop log

Date: 2026-10-30
Status: **Accepted** (owner, 2026-10-30) — three choices ratified before the
build: ask before replacing edited text, then replace; WAV/AIFF of any PCM
shape on the desktop, MP3/M4A refused in words; desktop now, phone later.
Delivered in one change the same day.

## Context

The owner asked for three things after using the desktop build:

1. a way to transcribe a recording again ("perhaps right click on the wav
   file");
2. a way to load a recording made elsewhere and transcribe it;
3. error logs ("I don't think we have any currently?").

On (3) they were right in practice. Shared code has reported failures through
`GiluneLog` since the external-provider round, and the Android shell sends
that to logcat — but the desktop fell back to standard error, and the
packaged desktop app runs with no console. Every warning was written nowhere,
and a thread that died uncaught left no trace.

On (1), two facts made it more than a menu item:

- A processing run is keyed `{processor_id}:{event_id}`, which makes
  delivery exactly-once — and makes a second answer to the same
  `source-ready` event impossible by construction.
- **Renaming a voice note broke its recording in the kernel.** The rail
  pairs a note with its recording by stem, so renaming or moving the note
  renames the `.wav` too; the kernel kept reading the old path (a processor
  asked for the bytes found nothing), and reconcile adopted the moved file as
  a second, unrelated attachment object, carrying the same bytes to every
  device twice. A renamed voice note is the ordinary case, so "transcribe
  again" would have failed on most of the notes a person would use it on.

## Decision

### 1. Asking again is a new event

`Store::process_again(object_id)` (kernel `reprocess.rs`) commits a new
`source-ready` event about the same source, with its jobs for every enabled
subscription, in one transaction — exactly as a capture commits its first.
Its payload is the head document with `"again": true` (`AGAIN_KEY`). Zero jobs
means nothing subscribed is switched on, and the shell says so rather than
waiting. Only a live source with its preserved bytes held here can be asked.

Everything after is the ordinary path: a lease, a run record under the new
event's key, outputs that name the head they read. The kernel still does not
know what a transcriber is.

Whisper (a kernel processor) transcribes whatever it is given. The app-side
providers (NeMo on the desktop, Moonshine on the phone) skip a subject that
already has words — the gate that keeps a catch-up from rewriting notes — so
`providerAction` now takes the event payload, and `again` overrides the gate.
A request never makes something that is not a recording decodable.

### 2. The person is asked before their words are replaced

`Store::source_for_file(rel_path)` answers which source a vault file holds
the bytes of, its note, and `text_edited`: the head's text (trimmed) differs
from the text of the newest revision a `processor:` origin wrote, or no
processor ever wrote and the text is not blank. The desktop asks before
transcribing such a note again; the dialog says the current text stays in
the note's history and that nothing in the app shows that history yet.

### 3. A moved recording is followed by its bytes

Reconcile, before adopting a non-note file as an attachment, asks
`follow_moved_recording`: is there a live source whose blob has exactly these
bytes and whose recorded path no longer holds them? If so, that blob row now
records this path (`blobs.rel_path`, device-local; a `Moved` change is
recorded), and nothing is adopted. A **copy** — the recorded path still holds
the bytes — is adopted like any other file. The source's document is not
revised and nothing is sent: where a recording sits is this device's fact.

Projection draws a source's `gilune-blob` header and its `![[name]]` link from
the blob row where one is held, so the note points at where its recording
now is. `source_for_file` runs the same rule, so a recording moved before this
change — already adopted as an attachment, its source recorded at a path that
is gone — is found and followed the first time somebody asks for it.

### 4. Loading a recording is a capture

The desktop reads WAV, AIFF and AU through javax.sound, decodes any PCM
variant to 16-bit, and down-mixes (average of channels) and resamples (box
filter down, linear interpolation up) to 16 kHz mono itself, in a stream —
independent of which converters a runtime carries, and never holding an
hour at its original rate in memory. MP3 and M4A are refused in words: the
JVM has no decoder and the owner chose not to add one this round.

The converted WAV goes through `GiluneState.captureRecording`, the
microphone's own path (`captureWav` is now shared): capture, transcribe,
project. The note is then named after the file (`name`, `name 1`, …) and,
from a folder's menu, moved into that folder; its recording moves with it and
§ 3 follows it. The loaded file itself is never touched.

Doors: a top-bar button beside the microphone (note stays in Captures) and
**Transcribe a recording…** on the root's and every folder's context menu,
all shown only while a plugin contributes the record-voice action — the
microphone's own rule. **Transcribe again** is on every WAV/AIFF recording
row in the rail; one that no capture made (a file copied in beside a note) is
transcribed into a new note beside it instead.

### 5. The desktop log

`DesktopLog.install()` runs first in `main`: `GiluneLog.sink` writes to
`<local Gilune dir>/logs/gilune.log` (`%LOCALAPPDATA%\Gilune\logs` on
Windows) as well as standard error, and a default uncaught-exception handler
records what escapes any thread before handing it on. One line per entry,
ISO instant and level first, the stack trace indented beneath; rolled past
1 MiB into one `gilune.1.log`, so the folder stays near 2 MiB. A log that
cannot be written is silent — it must never become the failure. Never in the
vault: a log describes one machine. Every failure `GiluneState` reports in the
status line is now also logged with its stack trace. **Settings ▸ About ▸ Show
the log file** opens the folder. Nothing is sent anywhere.

## Consequences

- A recording's path can now differ between devices (as an attachment's
  could after a collision); the document's `rel_path` is the path it was
  captured at, and the blob row is where it is here.
- Moving a voice note's recording into a local-only folder still leaves the
  kernel looking at the old path: local scope is never adopted, so the
  follow never runs there.
- Transcribing again creates a revision whether or not the words changed.
- The phone has none of this yet: no "transcribe again", no loading, and its
  warnings stay in logcat with no way to export them. Moonshine already obeys
  `again`, so the phone's door is a UI change.
- whisper.cpp's own stderr output is not captured in the log.

## Evidence

Kernel `reprocess/tests.rs` (9): asking again gives a second answer and only
one; the request carries `again`; zero with nothing on; only a live source
with bytes; `text_edited` before words, after the processor, after an edit,
after the edit is undone; a file that is no source; a renamed recording
followed, not adopted, its link redrawn, and processable; a copy stays a
copy; a recording moved before the rule is followed when asked for. Desktop:
`RecordingImportTest` (7: stereo 44.1 kHz, big-endian AIFF at 8 kHz, unsigned
8-bit, canonical kept sample for sample, refusals, the resampler, note
placing), `LogFileTest` (3), `ProviderAgainTest` (3, both suites),
`RecordingsEndToEndTest` (3, real kernel and the real NeMo loop with a
stand-in engine: a loaded file becomes `Meetings/Standup.md` with its
recording followed; transcribing again writes a second answer through a
provider that skips worded notes, and an edit makes the next request one to
ask about; nothing switched on says so). Falsified by disabling each: the
`again` flag at the provider, the follow in reconcile, the copy guard, and the
redrawn link.
