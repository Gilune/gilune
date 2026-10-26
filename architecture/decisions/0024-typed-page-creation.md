# ADR 0024 — Scoped creation of user-authored typed pages

Date: 2026-09-05
Status: Creation, collection forms, scalar editing and portable defaults implemented;
general document apply/refactoring remains open.

## Context

People needs editable pages. Processor output would misrepresent user authorship
and expose unnecessary write authority. Rebuilding a page must retain its chosen
path, source properties and original identity.

## Decision

Use the [page contract](../../contracts/pages/README.md): `pages.types` manifest
contributions, `<plugin-id>/<local-type>` ownership and `pages.create` capability.
The host binds a narrow service to one plugin; the kernel validates declaration,
lifecycle and grant inside the creation transaction. Templates/default folders are
literal initial values. Creation takes explicit source/path after customisation.

Commit objects, revisions, operations and events directly. Persist request identity
and publication path as versioned kernel-owned revision data. Receipts mean durable;
first publication occurs in normal projection, without replacing occupied files.
Retries reuse the initial revision and never reset subsequent edits. Conflicting
paths receive persistent object-specific alerts. No domain dispatch is introduced.

## Evidence and limits

Native and independent SDK consumers test this contract, including sync without the
plugin, moves, permission rejection, retries, collision recovery and preservation.
Platform evidence is in [delivery-plan section 14](../plans/people-and-document-refactoring.md).

This is neither a global legacy schema-ownership registry nor a sandbox. Existing
file refactoring/apply remains P4. Hard-link publication needs an equivalent where
unsupported. Cross-device moves, mixed-version writer gating, structured editing,
and the remaining People workflows remain open. No SQL migration is introduced; native
records and methods are additive and require per-target generated bindings.

Portable defaults extend this decision with explicitly declared companion page
types using `defaults_for`. The kernel resolves current revisions, validates folder
and template source, and includes their versions in the creation fingerprint.
Shared forms create/open ordinary Markdown configuration; plugins consume the
effective template through the SDK. Conflicts block the affected target rather
than selecting a winner. This adds no domain dispatch or second authoritative store.

One-time initial page sets use `pages.initializers` declarations and a dedicated,
visible receipt page type. The kernel commits each set and receipt together and
retains normal history to prevent reseeding after user changes/deletion. Existing
catalogues are preserved as a whole. Independent offline runs retain distinct
objects and surface publication conflicts; no distributed exactly-once guarantee
is claimed. Initialization preparation is separate from read-only queries.

**2026-10-26 (owner) — property sheets.** A page type may carry a property
sheet (`pages.properties`), so an existing page's details are edited where the
page is read rather than only through raw YAML: typed rows (text, switch,
named choice with a swatch tone, read-only with Rename references), read from
the page's own front matter and written one property at a time through the
existing source-preserving property edit. The sheet is pure data owned by the
page type's plugin; the shell draws it and writes nothing the person did not
choose. A value other pages reference (a keyword, a handle) is read-only here,
because changing it is a refactoring across notes, not a one-line edit. The
first sheet is People's, for connections, and it is where a connection's pill
colour is chosen (an earlier swatch in the connections list was dropped: that
list has no navigation entry). No kernel, schema or bindings change.
