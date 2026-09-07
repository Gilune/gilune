# Editor and document view contributions — v0.1

Bundled plugins contribute `EditorCompletionRegistration` at `editor.completions`
and `DocumentViewRegistration` at `document.views`. The registry enforces unique
IDs and enabled-owner declarations. People and Tasks independently compile
against the SDK; no domain plugin imports another domain implementation.

`EditorContext` carries immutable text, UTF-16 caret, document identity, current
date, optional continuation and toolbar invocation. A registered field slot
receives its resolved fields. The provider proposes an exact replacement range
ending at the caret, static choices and/or a bounded indexed lookup. Provider
callbacks run on a worker outside native query callbacks. The host requests 30
rows at a time and offers an explicit Load more action.

An `EditorOption` inserts literal text, advances an opaque provider-owned stage,
or opens a standard page creation form. Advancing a stage does not change the
document. A creation completion receives the resulting page snapshot and returns
an insertion option. The host rechecks document identity, full text/caret and
enabled registration after asynchronous work. Selection changes, dismissal,
provider removal and stale results cannot edit another buffer.

Options may require unique indexed definitions. The host repeats those bounded
queries on acceptance. People uses this for both the person handle and an active
connection keyword, so renamed, retired or ambiguous definitions cannot silently
produce a stale insertion. Plugin/configuration/index refresh invalidates visible
lookup work. Unresolved text remains intact and can still be indexed.

The shared Markdown source map excludes code, frontmatter and non-text spans;
providers add their lexical exclusions. Toolbar invocation checks a virtual token
at the caret so an empty paragraph works without allowing insertion inside code.
Incomplete invalid frontmatter produces no completion until its structure is
valid. Editor coordinates convert explicitly to full UTF-8 source offsets when
crossing into document/refactoring APIs.

Document views contribute a title, reference lookups and generic location-query
sections. The shell renders the Links dialog, executes only the chosen section,
pages results, reports missing/ambiguous identities and opens the selected source
page. People contributes mentions and connection backlinks using its own facts.
Navigation currently opens the source page; list-item matches do not promise
historical item identity or automatic caret placement within the page.

`EditorCompletionTest`, `PeopleEditorUiTest`, `PeopleUiDeviceTest` and the existing
Tasks editor tests exercise the seam, including explicit create-and-insert,
two-stage connections/dates, syntax exclusions, definition changes, removability,
plain text preservation and reference navigation. The shared kernel lookup suite
supplies complete paging, index-freshness and no-source-read coverage.
