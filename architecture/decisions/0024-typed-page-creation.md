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
