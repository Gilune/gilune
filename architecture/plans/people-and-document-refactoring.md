# People and shared document refactoring

Date: 2026-09-05
Status: **Product direction accepted, including self-review decisions. Implementation has not started.**

The owner has agreed the product direction and requested this plan. Proposed
defaults below make the work concrete; they are not shipped public contracts.
Build People and the shared refactoring capability together. People is the
first real consumer, while an unrelated fixture independently tests the public
contract. Refactoring does not belong to the People plugin.

## 1. Product commitments

- Plain text carries the meaning. Gilune adds discovery, navigation, queries
  and editing tools on top. An index rebuild must recover the same current
  connections from the exported documents and portable configuration.
- People owns named connections between a person and another object. Ship
  `assign` and `owner`; users can add, rename, merge or retire keywords.
  Gilune does not enforce responsibility, hierarchy, cardinality, permissions
  or workflow semantics based on those names.
- `@alice.smith` is a person reference. `owner: @alice.smith` is a labelled
  connection. A plain mention does not imply assignment. Tasks does not own
  assignment. People has no dependency on Tasks or any other target-domain
  plugin: no imports, plugin IDs, type names, schemas or plugin-specific slots.
  It remains useful with only the shared document services installed.
- A connection on a list item's continuation line applies to that item.
  Nested items remain separate. Shared Markdown locations provide this scope;
  People does not identify tasks or depend on another domain plugin.
- A typed handle without a person page remains a valid searchable reference.
  Offer explicit "Create person" from lookup; never create a page merely
  because the user typed a reference.
- Bulk renames include all Markdown files in the vault, including files from
  other editors without Gilune metadata. Do not add metadata or require
  adoption. Report unreadable, conflicted or otherwise uneditable files.
- Renaming a keyword or person handle is a document refactoring: update the
  visible occurrences across the reviewed scope. Hidden aliases or database
  bindings must not substitute for updating the text or contradict it.
- Person pages have stable object identity, an editable name/handle and
  freeform Markdown. File moves do not create a different person. Identity
  metadata is visible in the exported page; it is not a secret alternative
  interpretation of references.
- Ship an opinionated default person page, folder and creation template, with
  simple user overrides and a plugin contract for other defaults. Template
  changes initialise future pages; they do not rewrite existing notes.
- Notes remain frictionless. Choosing a person, folder, connection or template
  is never required to capture something.
- People also exercises a shared set-membership query capability: a filter can
  match any of several connection values. Membership belongs to the framework,
  not to People or the plugin defining the selected result type.

## 2. First release and deliberate limits

Deliver person creation/editing, a People list and detail page, `@` lookup,
user-defined connections, insertion through a discoverable menu, backlinks,
connection queries, and safe bulk keyword/handle renames with preview, progress,
resume and guarded undo. Cover ordinary pages and existing inline tasks.

Proposed interaction: `/` opens contributed actions; choosing a connection
opens person lookup and inserts `keyword: @handle`. `/ow` narrows to Owner.
A toolbar action opens the same interaction. `@` offers people directly and
inserts a plain mention. Directly typed Markdown is equally supported; lookup
is not mandatory and incomplete input is left intact. Desktop keyboard and
Android touch interactions share the same provider/context contracts.

Projects and Decisions are future consumers of the same target contract, not
additional plugins in this delivery. Optional personal dates may be ordinary
page data; recurring reminders, calendar integration and notifications are a
follow-on capability. Also defer automatic person inference, contact imports,
arbitrary UI widgets, a general template language and downloadable plugin
execution. These deferrals do not remove the public boundary requirement.

## 3. Current foundations and actual gaps

| Area | Reuse | Required addition |
| --- | --- | --- |
| Plugin lifecycle | Durable declarations, settings, removal tombstones | Portable People definitions and coherent settings/document changes |
| Bundled execution | ADR 0022 owned registrations and collision checks | Contract/module separation; generic editor contexts, live lookup and cross-domain query contributions |
| Objects and projection | Object/revision IDs, projection/reconciliation, unknown-data preservation | Scoped user-authored typed-page creation, editable properties and template/default-path contribution |
| Index and search | Pure indexers, opaque facts, incremental maintenance, complete kernel pages | Source-qualified snapshots, explicit target scope, reference resolution and configuration invalidation |
| Suggestions | Registered value providers and two-stage field selection | Current `/` recogniser is task-specific; providers lack a bounded live-data lookup contract |
| Editing | Shell saves followed by reconciliation, revision history and sync | Shared version-checked text changes, durable multi-document plans and undo |

The durable model's relationship records are a draft logical design; do not
assume a complete relationship CRUD API already exists. A second authoritative
relationship store is not required for connections written in Markdown.
Current search hits expose paths/lines, not immutable source references. The
change feed already carries optional object/revision IDs, but that is not yet
the public snapshot/edit contract.

Relevant baselines: [indexer](../../contracts/indexer/README.md),
[search](../../contracts/search/README.md),
[bundled extensions](../../contracts/bundled-extensions/README.md),
[data model](../03-data-model.md), and [compatibility](../07-compatibility.md).

## 4. Ownership and public contracts

| Owner | Responsibility |
| --- | --- |
| Kernel/shared services | Versioned document snapshots, validated changes, revision/operation recording, journal/recovery, generic index/query mechanisms including set membership |
| People | Person schema; mention/connection syntax; user keyword catalogue; facts; query compilation; rename proposals and rename-versus-merge policy |
| Other domain plugins | Independently contribute their own facts/types at shared document locations; never expose a domain-specific API that People must call |
| Shell/platform adapters | Standard forms/lists, editor triggers, previews, exact text insertion, progress, local file interaction and user decisions |
| Composition root | Select which implementations ship; no domain dispatch inside generic resolvers or renderers |

Introduce small versioned contracts with conformance fixtures:

1. **Document snapshots and changes.** Full source text/bytes, a source-version
   token, optional managed object/head identity, and protected metadata ranges.
   Changes name exact ranges and replacement text against that snapshot.
2. **Refactoring providers.** A provider creates a generic candidate query and
   proposes edits over supplied snapshots. The host pages candidates, invokes
   the provider and persists the plan. No plugin receives a filesystem walker,
   writable database or global kernel handle.
3. **Editor targets and suggestions.** The host supplies a buffer snapshot,
   caret, generic document/item/line location and cancellation/request identity.
   Contributions use shared editor slots, never another domain plugin's slots.
   Suggestions can request bounded indexed lookup through
   a host service; they must not re-enter Gilune from a native index/search
   callback. Stale responses cannot replace current suggestions.
4. **Page definitions and views.** Typed-page creation, property round-trip,
   initial templates/default paths, and standard list/detail/query sections.
   People contributes definitions; the shell renders those supported shapes.
5. **Set-valued query constraints.** The shared grammar/query builder represents
   lists as typed literal values. A plugin maps those literals to its predicate
   and canonical fact values; the framework compiles and executes membership.
   Plugins must not implement their own `IN` parser, result union or pagination.

Do not give People general write permission over objects owned by Tasks or
another plugin. The shared editor/refactoring service executes user-selected
text edits through a narrowly scoped document-edit authority. Bind approval to
the exact plan/version and affected content; resolve this explicitly with the
existing schema-ownership rules rather than bypassing them.

The new People module must depend on the plugin SDK/contracts rather than the
application host or another domain plugin. Move the relevant Tasks registrations
behind the same build boundary independently. The shared query composer combines
contributions; neither domain plugin constructs queries for the other. Kotlin
packaging remains a bundled implementation choice; a source file named "Plugin"
inside the shell alone is not dependency enforcement.

## 5. Plain-text representation and indexing

### Person pages and connection definitions

Proposed defaults: `People/<handle>.md`, with the kernel's identity/type/revision
metadata, readable `name` and `handle` properties, and a freeform body. Define
one canonical editable location for each property; a heading supplied by the
creation template must not become a second conflicting name store.

Connection definitions and template overrides must be portable, inspectable
text documents managed through normal revision/sync mechanisms. Select their
exact format and placement in milestone P0. They contain keywords and display
preferences, not built-in business meanings. Private plugin settings may cache
preferences but cannot be the only copy required to reconstruct the catalogue.

Define lexical rules once for handles and keywords: character set, case,
whitespace, escaping and token boundaries. Reuse that parser for indexing,
editing, lookup and refactoring. Exclude code spans/fences, escaped syntax,
URLs and email addresses. Do not rewrite ordinary prose matching a keyword.
Publish fixtures for adjacent tokens, repeated connections and Unicode.

Preserve and surface unknown keywords and unresolved/ambiguous handles instead
of silently binding them to the first match. Retiring a keyword removes it from
insertion defaults; existing text remains readable and discoverable. Resolve
handle collisions explicitly, including conflicts introduced by sync. Choosing
an existing keyword as a rename destination is a merge, with an explicit preview.
Distinguish a spelling-only rename of the same normalised keyword/handle from
a merge with a different definition. Such a rename still updates visible text
even though its normalised index key is unchanged. Display names need not be
unique; handles identify references. Deleting a person page does not delete
references from other documents: they remain searchable, unresolved text.

### Concrete fact schema (proposed v1)

People's index namespace is `org.memory.people`. All predicates below have
`ValueKind.Text`. This design uses a fixed set of predicates: adding a user
keyword adds values, not a new predicate, kernel operator or indexer version.
The raw spelling remains in Markdown; only derived keys are normalised.

| Predicate, relative to `org.memory.people.` | Location | Value | Purpose |
| --- | --- | --- | --- |
| `person.kind` | Person page root | `person` | Membership of the plugin's person-page search type |
| `person.handle` | Person page root | Normalised handle, without `@` | Exact lookup and duplicate-handle detection |
| `person.name` | Person page root | Display name | Directory/lookup presentation; Unicode text preserved |
| `person.name-key` | Person page root | Normalised display name | Indexed name-prefix lookup using the same normaliser as handles |
| `reference` | Where any recognised person reference occurs | Normalised handle | All references, including labelled connections; handle-rename candidates |
| `mention` | Where an unlabelled `@handle` occurs | Normalised handle | Plain mentions only; never an implicit connection |
| `connection` | Where a labelled connection occurs | Canonical `[keyword,handle]` pair | Exact connection query without mixing people and labels |
| `connection.keyword` | Same location as that connection | Normalised keyword | Connections under any person; keyword-rename candidates |
| `connection.person` | Same location as that connection | Normalised handle | All labelled connections to a person, excluding plain mentions |

`pair(keyword, handle)` is a compact JSON array of two normalised strings, in
that order, without optional whitespace. Use one versioned People codec for
indexing and query compilation; it owns string escaping and normalisation.
Proposed normalisation is NFC plus Unicode case folding, with the tables/version
pinned in the shared implementation and golden fixtures in P0. Preserve exact
source bytes separately for display and refactoring. The kernel does equality
on the resulting opaque string; it never parses JSON or interprets a keyword.

A syntactically valid `keyword: @handle` emits connection facts even when that
keyword is unconfigured or the handle cannot be resolved. Configuration controls
insertion choices and warnings, not a hidden interpretation of the text. This
keeps retired/new keywords and partial renames discoverable. Definition edits
alone do not relabel existing facts. Schema/parser changes still require an
indexer version change; ordinary keyword additions do not.

Person definition facts require a declared person page with valid properties.
An invalid definition has a visible diagnostic and does not become an arbitrary
lookup winner. Its body can still contribute ordinary reference facts. Person
object identity comes from the generic source envelope, not from a handle hash.

### Shared locations and evidence

A fact attaches to a generic location, not to a domain object type:

```text
Location = DocumentRoot | MarkdownItem(snapshot_local_key) | BodyLine(one_based_number)
Source = {document_key, snapshot_version, object_id?, revision_id?, rel_path}
Fact = {location, predicate, value}
```

The host binds source identity to emitted facts; plugins cannot fabricate the
source version. For managed documents the envelope carries the object/head;
for unadopted Markdown it carries a host-issued snapshot identity and content
fingerprint. Paths remain navigation data. Locations are relative to a source
snapshot and are not persistent IDs for inline content across arbitrary edits.

People recognises only its own syntax and common Markdown context. It does not
recognise checkboxes, identify tasks, inspect another plugin's type IDs, or ask
another plugin which object it is reading. The shared Markdown service maps
source positions to the nearest enclosing list item, or to a body line when
outside a list. Both ordered and unordered lists use the same rule. All plugins
receive the same snapshot-local item keys and source mapping through the SDK.

A connection anywhere in an item's own content, including continuation lines,
emits facts at that `MarkdownItem`. Nested items have their own keys; facts are
not copied to ancestors or descendants. Exact token spans/physical line numbers
remain source evidence for highlights and edits, not the relationship scope.
An ordinary reference outside a list emits at `BodyLine`. This adds the accepted
whole-item behaviour without generalising all paragraphs into new object types.
Future domain features must use shared locations or extend that generic contract;
they cannot require People integration code.

Keep the existing physical-line API available for legacy callers. Migrate the
relevant bundled indexers and type contributions independently to item locations
and add generic item results to search: count/deduplicate one item, expose its
source spans and matching snippets, and navigate to its source. No People code
performs that migration on behalf of another plugin. Index rebuild/compatibility
fixtures must account for the changed location model.

For explicit whole-page connections, propose a readable front-matter field:

```yaml
connections:
  owner: "@alice.smith"
  reviewer: ["@bob.jones", "@carol.lee"]
```

People emits these at `DocumentRoot`; arbitrary configured or unconfigured
keywords use the same mapping. Inline `owner: @alice.smith` emits at its list
item, or at its body line outside a list. This distinguishes page connections
without guessing from a checkbox or
from installed plugins. A body-only page search can still find pages containing
inline connections. This metadata syntax is a proposed UI/storage default for
review; the fact schema and location rules do not depend on the choice of form.

Extend the indexer input to provide a source snapshot with metadata and the body
mapping; the current body-only `NoteIndexer` cannot derive person definitions or
page connections. Full-source token spans are parsed from that exact snapshot
for refactoring. The index may deduplicate identical facts at one location, so
fact counts are not occurrence counts: preview reparses each candidate snapshot
to count and propose every actual token replacement.

### Worked fact example

In `Launch.md`, body line 4 contains:

```markdown
- [ ] Publish owner: @alice.smith reviewer: @bob.jones
```

People emits the following set at the shared `MarkdownItem` starting on line 4,
without knowing it is a task:

| Predicate suffix | Value |
| --- | --- |
| `reference` | `alice.smith` |
| `reference` | `bob.jones` |
| `connection` | `["owner","alice.smith"]` |
| `connection` | `["reviewer","bob.jones"]` |
| `connection.keyword` | `owner` |
| `connection.keyword` | `reviewer` |
| `connection.person` | `alice.smith` |
| `connection.person` | `bob.jones` |

Outside that list, `Spoke with @carol.lee` on line 6 emits
`reference=carol.lee` and `mention=carol.lee` at `BodyLine(6)`, with no connection
facts. A page-level Owner field emits at the root, not at either body location.

This multiline form produces the same connection facts at the same item's
location:

```markdown
- [ ] Publish
  owner: @alice.smith reviewer: @bob.jones
  - [ ] Check links assign: @carol.lee
```

The child item's Assign fact belongs only to the child. It neither changes the
parent's connection set nor inherits the parent's Owner/Reviewer facts.

As an integration-test example only, Tasks independently emits
`org.memory.tasks.state=open` at that shared item location. Neither plugin
calls the other.
An unrelated fixture can emit a different type predicate at that same location
and combine with exactly the same People facts, without modifying People.

### Query vocabulary and exact compilation

People registers globally composable, qualified operators in a shared operator
registry. It does not register them into another plugin's operator list or
construct another domain's queries. The generic composer independently resolves
the selected type, each operator and their required index namespaces. Duplicate
operator names are rejected with a diagnostic, never silently overridden.

Proposed canonical vocabulary (the UI can generate it for the user):

| Query term | People compilation: exact generic fact equality |
| --- | --- |
| `people.ref:@alice.smith` | `reference = "alice.smith"` |
| `people.mention:@alice.smith` | `mention = "alice.smith"` |
| `people.connected:@alice.smith` | `connection.person = "alice.smith"` |
| `people.connection:owner` | `connection.keyword = "owner"` |
| `people.connection:owner:@alice.smith` | `connection = '["owner","alice.smith"]'` |
| `people.page-connection:owner:@alice.smith` | Same pair equality, restricted explicitly to `DocumentRoot` |

All predicates above have the full People namespace. A missing/invalid argument
is a diagnostic, not a broader query. Arbitrary keywords are arguments, so a
user-defined `due` or `file` connection cannot replace a built-in operator.
People also registers `type:person`, compiled to `person.kind=person` at the
root. Exact `person.handle` equality serves reference resolution. Autocomplete
uses a bounded generic lookup request: require `person.kind=person` at the
root, then match a normalised fragment against either `person.handle` or
`person.name-key` by prefix. Empty input lists person roots. Deduplicate by
source/object, rank exact handle matches before handle-prefix matches before
name-prefix matches, and break ties by normalised name, handle and source key.
The plugin declares predicates and rank priorities; the kernel performs the
prefix alternatives, ranking, total count and paging over one index generation.

The current fact API already has `ConstraintOp.Prefix`; reuse it. Composable
alternative lookup clauses and the declared lookup ranking contract still need
work. Do not implement autocomplete by scanning in the shell or filtering only
the first result page.
The paged lookup response carries explicit freshness and generation-bound
continuations, like search. No kernel code names a person property.

The key combined-query acceptance example is:

```text
type:task people.connection:owner:@alice.smith

Tasks type compiler:  org.memory.tasks.state EXISTS
People term compiler: org.memory.people.connection EQ ["owner","alice.smith"]
Kernel:               AND both constraints at the same shared item location
```

This example belongs in shared integration tests, not in People's production
code, defaults or views. Person detail sections contribute People-only queries.
Users or independent view contributions can combine those with any registered
type through the generic query UI. An Owner query for Bob does not match the
example line, even though `connection.keyword=owner` and
`connection.person=bob.jones` are both present: the compiler uses the paired
fact, never the conjunction of those two marginal facts.

### Shared set membership: matching any selected value

Owner direction: include this framework extension in the People delivery.
The motivating filter is conceptually:

```text
type: task
assign IN ["alice", "bob"]
```

The final surface syntax remains a P0 choice. A qualified field form must remain
available so a custom connection keyword cannot shadow another operator; for
example, `people.connection:assign IN ["@alice", "@bob"]`. A filter builder can
show "Assign is any of Alice, Bob" and produce the same query representation.
The sample values identify literal handles, not ambiguous display names.

Add a generic constraint shaped like:

```text
FactIn {
    predicate,
    values: [canonical fact value],
    locality
}
```

Names are illustrative public-API design, not shipped bindings. Equality for
each member is exactly the equality used by the existing `Fact EQ` constraint.
This is an existential test over the selected location: at least one stored
value of the predicate equals at least one member of the requested set.

The motivating query compiles independently:

```text
Type contribution:   org.memory.tasks.state EXISTS
People value binding:
    predicate = org.memory.people.connection
    values = [ '["assign","alice"]', '["assign","bob"]' ]
Shared composer:     type constraint AND FactIn(predicate, values, exact-location)
```

People supplies the keyword/handle encoding for each list element; it does not
know the selected type or implement membership semantics. The kernel sees only
a predicate, values and scope. The same operation must work with a non-People
fixture's scalar facts and with independently registered result types.

Required semantics:

- Match Alice **or** Bob. Multiple matching values or repeated facts produce
  one result per selected target, not one result per person. Grouping to a
  document remains a separate operation.
- `owner: @alice` cannot satisfy an `assign` filter. Membership uses complete
  encoded pairs, preserving the connection keyword/person association.
- Apply membership and the other constraints within their declared scope. A
  connection on a continuation line belongs to its item; a connection on a
  sibling or nested item cannot make another item match. Outside lists,
  unrelated body lines remain separate.
  Document/root scope follows the same explicit rules as single-value queries.
- `NOT IN` is negation of the entire membership clause: none of the selected
  values occurs in scope. A target containing Alice and Carol fails
  `NOT IN [Alice, Bob]`; Carol is not evidence that the condition holds. A
  target with no facts for that predicate passes, unless the query also asks
  for existence.
- `IN []` matches nothing; `NOT IN []` adds no restriction. Repeated values
  and reordered lists have identical meaning. Canonicalise the set for query
  identity so ordering/duplicates do not change continuation semantics.
- Define bounded list size and typed-literal validation in P0. Reject malformed
  or oversized lists clearly; never truncate them or execute a partially
  accepted list. Empty and missing arguments are different cases.
- Kernel execution performs selection, deduplication, totals, ranking and
  pagination before returning a page. Do not run separately capped searches
  per member and union them in the shell or a plugin. Index freshness and
  generation-bound continuation rules are unchanged.

The existing kernel has equality/inequality and clause negation, but no explicit
set-valued fact constraint or user-facing list-membership grammar. Implement
the public membership operation through the kernel/FFI/plugin compiler and
shared query UI together. Existing expressions retain their parsing and
meaning. General Boolean expression trees are not required merely to deliver
membership; prefix alternatives for lookup are a separate query shape.
An unresolved field/provider in a structured membership filter must be reported
as unavailable, not executed as a partial filter or silently reinterpreted as
prose. P0 compatibility fixtures must cover saved queries resembling the new
syntax and the explicit quoting needed to search for literal filter examples.

### Scope, negation and resolution

Add an explicit generic fact-locality option to constraints: exact location
versus the existing inclusion of document facts. The planned exact locations include
shared Markdown items as well as body lines. People compiles ordinary
connection constraints as exact-location; it never selects locality based on
another plugin's type. Retain existing behaviour for old callers until they
opt into the new field, with compatibility fixtures.

- For an item result, all connection constraints bind to that item, including
  its own continuation lines but excluding nested items. For a body-line result,
  they bind only to that line. Root facts are excluded unless the query
  explicitly asks for a page constraint.
- For a document result, ordinary reference/connection terms search root and
  contained item/line locations, following the document-wide term semantics.
  `people.page-connection` restricts its clause to the root. Page connections
  are never copied to item/line locations to implement this search.
- Negation complements the exact positive clause over the same result scope.
  An item/line-level negative excludes that location if it has the pair; a document-level
  negative excludes documents with that pair anywhere in the requested scope.
- Pagination, totals, joins and negatives all execute in the kernel. An
  unavailable required namespace reports incomplete results rather than a
  definitive empty answer.

Resolve a handle with `person.kind=person AND person.handle=<key>` at page roots:
zero definitions is unresolved, one is resolved to the returned object/source,
more than one is ambiguous. Conflicting heads must not be silently collapsed
into a unique definition. Literal reference queries continue to work even when
the person page is missing. Lookup offers explicit creation for a missing person;
accepting ordinary text or typing a reference never creates a page automatically.
Selecting a person whose handle is ambiguous must
show the conflict rather than insert text that cannot distinguish the choices.

Do not persist a person-ID relationship that overrules the written handle.
Lookup is a separate indexed operation outside native callback execution.
Reference facts remain lexical, so changing a person definition invalidates
lookup/derived-view caches by index generation without reindexing every referring
note. Changing catalogue labels likewise refreshes menus/diagnostics only; an
actual rename rewrites the defining text and reference documents, and normal
change-feed maintenance replaces their facts. The old pair disappears from an
updated document, while unchanged documents in a partial rename retain it.

### Fact/query acceptance fixtures

P0/P3 must exercise exact emitted rows and query results for:

- The worked two-person/two-keyword example, including the false Owner/Bob query.
- Single-value equivalence, multi-value `IN`, whole-clause `NOT IN`, missing
  facts, empty/repeated/reordered sets, malformed/oversized lists, and results
  matching several set members. Repeat with unrelated plugin predicates.
- Plain mentions, repeated identical tokens, multiple people for one keyword,
  unknown/retired keywords, unresolved/ambiguous handles and normalisation.
- Page-root versus item/line scope; multiline item continuations, separate
  nested/sibling items, document queries across multiple locations; explicit
  page clauses; positive and negative paired constraints.
- Independent People-only operation, independent Tasks-only operation, and
  shared query composition with Tasks plus an unrelated fake domain. Removing
  either other domain leaves People's lexical rows and lookup unchanged.
- Keyword/handle rename and partial rename; catalogue-only edits; external file
  edits; person-page moves; duplicate/conflicted definitions after sync.
- Complete paged lookup beyond the first result page, namespace freshness,
  zero vault reads during queries, and equality of incremental versus rebuilt
  facts and results. Recovery derives all relationships from the visible text.

## 6. Shared refactoring workflow and guarantees

The first user operation is a connection rename; person-handle rename exercises
the same contract next. Both include edits to the defining text document and
its references. Connection-specific completion policy stays in People.
Changing a name in the UI launches this workflow rather than saving only a new
setting. If someone edits the definition directly in another tool, preserve
that edit and surface old-keyword occurrences with an offered refactoring;
do not launch unrequested bulk rewrites in response to reconciliation.

Proposed lifecycle:

`discover -> preview -> apply -> completed | partial | cancelled`

An interrupted run resumes from its journal. Preview records provider/version,
scope, relevant configuration versions, index freshness and document snapshots.
Each document has its own pending/applied/conflict/skipped/failure result.
Completion describes the reviewed scope and unresolved occurrences, not a
promise that every offline device has already been renamed.

- Reconcile relevant externally edited files before authoritative discovery;
  make index incompleteness visible. Page through all candidates, with no
  search-display cap hiding occurrences. Broad discovery must run off the UI
  thread; reads needed to prepare or apply edits are separate from kernel search.
- Use full source snapshots for edits. Specify offset units at the boundary
  (proposed: UTF-8 byte offsets with explicit UI conversion), full-file versus
  body offsets, CRLF handling and code-point boundaries. Reject overlaps,
  invalid ranges and edits to protected kernel metadata.
- Before applying, verify the expected source version and current filesystem
  state. Replan changed documents and require a fresh preview for newly changed
  edits. Do not silently apply offsets from an old snapshot.
- Coordinate open editor buffers. Dirty unsaved text must be saved into the
  reviewed snapshot or excluded explicitly. A delayed autosave must not undo
  the refactor; undo stacks and selection state receive the committed change.
- Each document change is recoverable and recorded once with attributable
  refactoring metadata and change-feed notification. Managed documents also
  produce ordinary object revisions and sync operations. Unmanaged Markdown
  receives durable before/after snapshots in the shared journal for recovery
  and undo, without adding metadata or adopting the file. Do not claim its
  history or sync is supplied by managed-object APIs; P0 must establish the
  supported behaviour and limits. A whole-vault rename is not one giant atomic
  transaction.
- Preserve original capture blobs and prior revisions. Only current editable
  document content is changed. Unsupported, quarantined, conflicted, unreadable
  documents need explicit results. Unmanaged status alone is not a reason to
  skip a Markdown file. No silent omission and no automatic adoption or added
  metadata merely to rename its text.
- Do not claim a database transaction serialises an external text editor.
  Prototype and test the filesystem replacement/journal protocol, including
  races and crash windows. Preserve recoverable bytes and expose any remaining
  concurrency limitation before accepting the durability contract.
- Cancellation stops future writes; completed writes remain listed. Retrying
  cannot duplicate revisions or accidentally apply a replacement twice.
- Undo is a new guarded change using recorded before/after content. Preserve
  subsequent user edits; conflicting inverse edits require resolution. Retain
  undo evidence durably and define its retention/removal behaviour.
- While a rename is partial, old and new spellings remain explicit text and
  visible configuration/occurrence states. Never pretend completion by making
  them invisible aliases. A later offline edit using the old keyword is
  surfaced as a new occurrence for a follow-up plan, not silently rewritten.
- Sync managed-document changes through existing operation semantics. Explicitly
  document the sync behaviour of unmanaged files; their inclusion in local
  rename/recovery is required regardless of sync support. Keep
  execution journal/leases local initially so a second device does not execute
  the same plan. Define origin-device undo and recovery limitations clearly.
- On plugin disable/removal, stop invoking its proposal/completion callbacks;
  retain applied text, the journal and generic recovery/undo. Re-enable/resume
  checks provider and configuration versions before continuing.

## 7. Ordered implementation milestones

Each milestone records evidence before it is marked complete. The sequence is
for one implementation effort; it does not require parallel agents.

### P0 — Freeze examples and resolve contract choices

- [ ] Write executable golden fixtures for person pages, portable connection
  definitions, insertion, root/item/line scope, explicit person creation,
  unmanaged Markdown inclusion, keyword rename and handle rename.
- [ ] Set lexical/collision rules, qualified query syntax, target scope, offset
  units, undo retention, unmanaged-file recovery/sync mechanics and partial-
  catalogue behaviour. Whole-item scope, optional explicit person creation and
  all-Markdown rename coverage are accepted requirements, not open choices.
  Validate the proposed fixed fact schema and query compilation tables above.
- [ ] Freeze shared list-literal/membership syntax and limits, field-value
  binding, negation and empty-set semantics using the Alice-or-Bob filter and
  an unrelated plugin fixture. Keep the keyword/handle encoding in People.
- [ ] Validate paired fact encoding/normalisation and prototype source-versioned
  file edits, including an external-editor race. Record limits and recovery
  evidence in proposed ADRs.

Exit: export/rebuild semantics and safe change mechanics are demonstrable;
unresolved product choices are explicit rather than hidden in implementation.

### P1 — Public document foundation and plugin boundary

- [ ] Introduce scoped snapshot and validated-edit contracts, source identity
  through FFI, protected metadata and exact newline/Unicode handling. Include
  metadata-free files through snapshot/journal APIs without mandatory adoption.
- [ ] Implement typed user-authored page creation and property-preserving
  projection/reconciliation; preserve unknown properties and content.
- [ ] Establish build-enforced SDK/domain/host dependencies for the introduced
  capabilities and migrate the relevant existing Tasks registrations.
- [ ] Add the generic set-valued fact constraint to kernel/FFI and the shared
  query compiler contract. Test equality equivalence, scope, negation, complete
  totals/pagination and compatibility without depending on the People module.
- [ ] Publish contracts with conformance tests, version changes and stored
  migration fixtures. Generate native bindings from each target's own binary.

Exit: a test consumer creates/edits a typed page using public APIs, preserves
external edits and round-trips unknown content without importing host internals.

### P2 — People pages and portable definitions

- [ ] Implement the People schema, list/create/edit/detail contributions,
  default folder/template and user overrides through standard page contracts.
- [ ] Implement editable connection definitions with `assign`/`owner` defaults,
  arbitrary additional keywords, retirement and explicit collision states.
- [ ] Establish visible identity/handle resolution and export/rebuild fixtures.

Exit: People works without Tasks; pages and definitions remain readable and
recoverable when People is disabled or removed. No reminder engine is required.

### P3 — Mentions, connections, lookup and indexed queries

- [ ] Implement the shared parser, fixed paired-fact schema, definition-cache
  invalidation, shared Markdown item mapping and root/item/line locality
  constraints. Migrate relevant legacy indexers/types to the shared item model
  and test item-level results without cross-plugin dependencies.
- [ ] Generalise trigger/context and async lookup contracts; migrate the
  existing task/date insertion flow. Implement complete indexed prefix lookup
  with declared alternatives/ranking. Add `/`, toolbar and `@` People flows.
- [ ] Add cross-domain query contributions, person navigation/backlinks and
  standard People-only query sections. The shared composer combines independently
  registered type/operator constraints; People neither names nor calls another
  domain plugin. Verify that boundary with both Tasks and an unrelated fixture.
- [ ] Wire typed lists and `IN`/`NOT IN` into the shared parser/filter UI on both
  platforms. People binds selected handles to connection-pair values through
  the public contract. Test the motivating filter without per-person searches
  or People-specific query-engine branches.
- [ ] Exercise large person lists, incomplete indexes, ambiguity, cancellation,
  direct Markdown edits and plugin toggles on both UI platforms.

Exit: current connections rebuild from text; item continuations match together,
nested items remain separate, and root facts do not leak into local matches.
Complete lookup/search performs no keystroke vault scan.
People's compiled module has no dependency on another domain implementation.

### P4 — Shared refactoring service and independent consumer

- [ ] Implement candidate paging, persisted plans/previews, scoped apply,
  journal recovery, progress, cancellation/resume and guarded undo.
- [ ] Integrate version checks, open buffers, filesystem preservation, revision
  history, sync operations, index refresh and removed-plugin recovery.
- [ ] Build a small unrelated fixture (for example a plugin-defined text label
  rename) against the public contract, with no People dependency or host switch.
- [ ] Add fault injection across planning, replacement, commit, index refresh,
  restart and undo. Prove retries are idempotent and partial results accurate.

Exit: shared refactoring is usable and recoverable independently of People.

### P5 — People rename and merge workflows

- [ ] Wire keyword rename, keyword merge and person-handle rename to the shared
  provider contract. Include defining documents and all discovered references.
- [ ] Deliver counts/previews, readable diffs, scope selection, progress,
  conflict resolution, partial completion, resume and undo in both shells.
- [ ] Keep catalogue state truthful throughout partial application. Surface
  late old-spelling occurrences and allow a new reviewed follow-up plan.

Exit: a user can rename a custom connection throughout a vault, inspect the
updated files elsewhere, restart Gilune and rebuild the same current meaning.

### P6 — Integration, compatibility and performance gate

- [ ] Run focused kernel/FFI/plugin tests, the Rust workspace and clippy,
  desktop tests against the packaged release kernel, Android builds and
  available device tests. Record unrun device/platform checks explicitly.
- [ ] Test two-device offline edits and out-of-order sync around renames;
  exercise collisions, partial configuration delivery and preserved conflicts.
- [ ] Measure small and large vaults, including over 10,000 matches, Unicode
  and large files. Record discovery/apply time, peak memory and lookup latency;
  establish regression budgets from measured baselines, not invented targets.
- [ ] Exercise membership sets up to the public limit and result sets spanning
  many pages, with overlapping matches, reordered values, missing namespaces
  and index updates between pages. Verify complete counts and no duplicates.
- [ ] Verify export/rebuild, disable/uninstall/re-enable, migration fixtures,
  permission scope, byte preservation and an unrelated consumer.
- [ ] Update ADRs and contracts with evidence; document public API limits and
  user-facing rename/recovery behaviour. Mark only proven milestones complete.

## 8. Release acceptance scenarios

1. Create Alice and Bob, add a `reviewer` keyword, and insert connections through
   the UI and an external editor. Both routes produce equivalent indexed facts.
2. Query Alice's `owner` connections without matching Bob's `reviewer` connection
   on the same target. A page owner does not implicitly own its inline tasks.
3. Rename `owner` to `lead` across multiple pages and tasks. Every applied token
   and the portable definition change visibly; prose/code remain byte-identical.
4. Rename Alice's handle. Update recognised mentions/connections and the person
   definition; reject ambiguous collisions without losing any original text.
5. Interrupt a rename, restart, resume and undo. No applied edit disappears from
   the report, no retry doubles an edit, and subsequent user work is preserved.
6. Edit a candidate in an open buffer or another editor after preview. Gilune
   detects the changed source instead of applying stale ranges or autosaving
   the pre-rename buffer over the result.
7. Receive an offline old-keyword edit after completion. Preserve it, expose the
   remaining occurrence and create a follow-up plan only when requested.
8. Remove People during a partial rename. Text remains readable; shared recovery
   and guarded undo remain available; no domain callback runs while removed.
9. Export the vault and portable definitions, discard derived indexes, and
   rebuild. Current connections and custom keywords retain their visible meaning.
10. Exercise the same refactoring API with the unrelated fixture. Generic
    kernel/host modules contain no People keywords, schemas or identity checks;
    shipped plugin selection is confined to the composition root. People contains
    no other domain's plugin IDs, type names, predicates, slots or imports.
11. Independently combine People's paired facts with two different domain types
    through the shared query composer. Verify same-location joins, false-pair
    exclusion, root/item/line scope and negation without changes to People's code.
12. Filter for tasks with an `assign` connection to Alice or Bob. Include a
    target connected to both only once, exclude Owner-only and other-item
    matches, and verify `NOT IN`, empty sets and paging. Repeat membership with
    a non-People predicate to prove this is a shared framework capability.
13. Put a connection on a list item's continuation line and query the item.
    Match its own content across lines, but never inherit connections from or
    into a nested item. Ordered and unordered lists follow the same rule.
14. Type an unknown handle. Preserve/search the reference with no new page;
    offer explicit creation in lookup and create the page only when selected.
15. Rename across a mixed vault of managed pages and metadata-free Markdown
    created in another editor. Include both, add no metadata, report genuine
    failures, and prove interrupted recovery and guarded undo for both forms.

## 9. Self-review decisions

Reviewed and accepted by the owner, 2026-09-05. All three recommendations are
requirements of this delivery; implementation and validation remain outstanding.

| Choice | Accepted behaviour | Consequence for the plan |
| --- | --- | --- |
| A connection on an indented continuation line of a list item | Apply to the whole item; nested items remain separate | Shared Markdown item locations replace physical-line-only matching for list items. People remains independent of other domain plugins; source spans support exact edits. |
| A typed handle with no person page | Preserve the reference and offer explicit creation from lookup; do not create a page automatically | No page-creation requirement on typing; unresolved references are valid searchable text. |
| Bulk rename scope for Markdown created outside Gilune | Include it without adding Gilune metadata, reporting unreadable/conflicted files explicitly | Snapshot/journal/undo support for unmanaged files is required. Establish sync/history limits without assuming managed-object APIs already cover them. |

P0 implements the accepted behaviours in fixtures and establishes the third
choice's recovery/sync mechanics. Offset encoding, list limits, normaliser
version, storage format and recovery protocol can be settled through the
existing implementation/conformance work without further product questions.

The largest delivery risk is scope: this is People plus shared editing, query,
lookup and plugin-boundary work. Preserve the staged gates and independently
testable consumers. Implement only the standard views/templates and contracts
needed by those consumers; do not expand into a general plugin UI or template
language while delivering this slice.
