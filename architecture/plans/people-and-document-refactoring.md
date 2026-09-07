# People and shared document refactoring

Date: 2026-09-05
Status: **People and shared refactoring delivery complete. P0–P6 evidence and explicit release limits are recorded in section 39. Reminders, Projects and Decisions remain deferred.**

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

The lookup API reuses `ConstraintOp.Prefix`, with composable alternatives and
declared priorities/sort predicates (section 21). Autocomplete must not scan in
the shell or filter only the first result page.
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

The first framework implementation uses uppercase `IN` / `NOT IN` separated
by whitespace, with strict JSON string lists. A qualified field form remains
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

`FactIn` and `FactLocality` now exist in kernel/FFI bindings. Equality for
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
- The shared contract bounds each list at 256 string values and 65,536 aggregate
  UTF-8 bytes before deduplication. Validate both parsed literals and encoded
  values; domain-specific literal validation stays in the field binding.
  Reject malformed or oversized lists clearly; never truncate them or execute
  a partially accepted list. Empty and missing arguments are different cases.
- Kernel execution performs selection, deduplication, totals, ranking and
  pagination before returning a page. Do not run separately capped searches
  per member and union them in the shell or a plugin. Index freshness and
  generation-bound continuation rules are unchanged.

The first implementation adds set-valued constraints and list-membership
compilation through kernel/FFI and the shared search/filter surfaces. Existing
unqualified scalar expressions and prose retain their meaning; qualified
structured fields reserve their syntax and report an unavailable contributor.
Quote literal filter examples to search for their text. General Boolean expression trees are not required merely to deliver
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

- [x] Write executable golden fixtures for person pages, portable connection
  definitions, insertion, root/item/line scope, explicit person creation,
  unmanaged Markdown inclusion, keyword rename and handle rename.
- [x] Set lexical/collision rules, qualified query syntax, target scope, offset
  units, undo retention, unmanaged-file recovery/sync mechanics and partial-
  catalogue behaviour. Whole-item scope, optional explicit person creation and
  all-Markdown rename coverage are accepted requirements, not open choices.
  Validate the proposed fixed fact schema and query compilation tables above.
- [x] Freeze shared list-literal/membership syntax and limits, field-value
  binding, negation and empty-set semantics using the Alice-or-Bob filter and
  an unrelated plugin fixture. Keep the keyword/handle encoding in People.
- [x] Validate paired fact encoding/normalisation and prototype source-versioned
  file edits, including an external-editor race. Record limits and recovery
  evidence in proposed ADRs.

Exit: export/rebuild semantics and safe change mechanics are demonstrable;
unresolved product choices are explicit rather than hidden in implementation.

### P1 — Public document foundation and plugin boundary

- [x] Expose exact read-only snapshots and validated edit preparation through
  FFI, with verified source identity, protected metadata and exact newline/Unicode
  handling. Include metadata-free files without mandatory adoption. See the
  [document contract](../../contracts/documents/README.md) and section 11.
- [x] Extend preparation into the scoped document-edit authority and journal
  integration required by P4; reading/preparing a snapshot is not an apply API.
- [x] Implement scoped typed user-authored page creation and property-preserving
  projection/reconciliation; preserve unknown properties and content. See sections
  13–14 and the public page contract. Standard page forms/views and portable
  overrides are covered by P2 and section 39.
- [x] Establish build-enforced SDK/domain/host dependencies for the introduced
  capabilities and migrate the relevant existing Tasks registrations. Tasks and
  the unrelated query fixture now compile against the SDK without the host or
  another domain module. See section 12 and the editor/view contracts in section 39.
- [x] Add the generic set-valued fact constraint to kernel/FFI and the shared
  query compiler contract. Test equality equivalence, scope, negation, complete
  totals/pagination and compatibility without depending on the People module.
  Root, body-line and Markdown-item scope are proven by the scoped query suites.
- [x] Publish contracts with conformance tests, version changes and stored
  migration fixtures. Generate native bindings from each target's own binary.

Exit: a test consumer creates/edits a typed page using public APIs, preserves
external edits and round-trips unknown content without importing host internals.

### P2 — People pages and portable definitions

- [x] Initialize default definitions through a shared page-initializer contract,
  preserving existing catalogues and recording durable, portable setup receipts.
- [x] Persist folder/template overrides as ordinary companion Markdown pages,
  resolve them in the kernel and pass effective templates through the public SDK.
- [x] Share source-preserving scalar property editing between desktop and Android,
  including buffer freshness, unknown YAML retention and property-only dirty state.
- [x] Add the independent People module, person/connection page definitions,
  generic indexed collections and creation forms on desktop and Android.
- [x] Extend the generic framework with opt-in full-source indexers, shared YAML
  scalar access and root display facts, preserving legacy body-only callbacks.
- [x] Test portable definition rebuilds, normalized identifier collisions,
  header-only pages, paging and disable/removal through the public SDK/FFI.
- [x] Implement the People schema, list/create/edit/detail contributions,
  default folder/template and user overrides through standard page contracts.
- [x] Implement editable connection definitions with `assign`/`owner` defaults,
  arbitrary additional keywords, retirement and explicit collision states.
- [x] Establish visible identity/handle resolution and export/rebuild fixtures.

Exit: People works without Tasks; pages and definitions remain readable and
recoverable when People is disabled or removed. No reminder engine is required.

### P3 — Mentions, connections, lookup and indexed queries

- [x] Supply the shared source Markdown map, including whole-item continuation
  scope, nested-item separation and snapshot-local source spans.
- [x] Implement People mention/connection recognition and fixed paired facts,
  including structured page-root connections through generic YAML values.
- [x] Persist source-qualified root/item/line facts and generic paged location
  queries, including exact scope, membership and version-checked absence.
- [x] Integrate item locations into complete application search. Tasks independently
  adopts the shared SDK location contract and rebuilds its derived facts; no task
  documents or domain objects migrate. Test combined results without cross-plugin
  dependencies, including an unrelated item type without Tasks installed.
- [x] Add definition-cache invalidation with the reference-resolution/lookup work.
- [x] Implement complete indexed prefix lookup with declared alternatives/ranking,
  bounded provider callbacks and asynchronous context-keyed publication. Exercise
  the shared contract through directory lookup and an unrelated SDK consumer.
- [x] Generalise editor trigger/target contracts and connect lookup; migrate the
  existing task/date insertion flow. Add `/`, toolbar and `@` People flows, including
  explicit create-from-lookup and version-checked text insertion.
- [x] Add cross-domain query contributions. The shared composer combines independently
  registered type/operator constraints; People neither names nor calls another
  domain plugin. Verify that boundary with both Tasks and an unrelated fixture.
- [x] Add person navigation/backlinks and standard People-only query sections.
- [x] Wire typed lists and `IN`/`NOT IN` into the shared parser/filter UI on both
  platforms. People binds selected handles to connection-pair values through
  the public contract. Test the motivating filter without per-person searches
  or People-specific query-engine branches.
- [x] Exercise large person lists, incomplete indexes, ambiguity, cancellation,
  direct Markdown edits and plugin toggles on both UI platforms.

Exit: current connections rebuild from text; item continuations match together,
nested items remain separate, and root facts do not leak into local matches.
Complete lookup/search performs no keystroke vault scan.
People's compiled module has no dependency on another domain implementation.

### P4 — Shared refactoring service and independent consumer

- [x] Implement candidate paging, persisted plans/previews, scoped apply,
  journal recovery, progress, cancellation/resume and guarded undo.
- [x] Integrate version checks, open buffers, filesystem preservation, revision
  history, sync operations, index refresh and removed-plugin recovery.
- [x] Build a small unrelated fixture (for example a plugin-defined text label
  rename) against the public contract, with no People dependency or host switch.
- [x] Add fault injection across planning, replacement, commit, index refresh,
  restart and undo. Prove retries are idempotent and partial results accurate.

Exit: shared refactoring is usable and recoverable independently of People.

### P5 — People rename and merge workflows

- [x] Wire keyword rename, keyword merge and person-handle rename to the shared
  provider contract. Include defining documents and all discovered references.
- [x] Deliver counts/previews, readable diffs, scope selection, progress,
  conflict resolution, partial completion, resume and undo in both shells.
- [x] Keep catalogue state truthful throughout partial application. Surface
  late old-spelling occurrences and allow a new reviewed follow-up plan.

Exit: a user can rename a custom connection throughout a vault, inspect the
updated files elsewhere, restart Gilune and rebuild the same current meaning.

### P6 — Integration, compatibility and performance gate

- [x] Run focused kernel/FFI/plugin tests, the Rust workspace and clippy,
  desktop tests against the packaged release kernel, Android builds and
  available device tests. Record unrun device/platform checks explicitly.
- [x] Test two-device offline edits and out-of-order sync around renames;
  exercise collisions, partial configuration delivery and preserved conflicts.
- [x] Measure small and large vaults, including over 10,000 matches, Unicode
  and large files. Record discovery/apply time, peak memory and lookup latency;
  establish regression budgets from measured baselines, not invented targets.
- [x] Exercise membership sets up to the public limit and result sets spanning
  many pages, with overlapping matches, reordered values, missing namespaces
  and index updates between pages. Verify complete counts and no duplicates.
- [x] Verify export/rebuild, disable/uninstall/re-enable, migration fixtures,
  permission scope, byte preservation and an unrelated consumer.
- [x] Update ADRs and contracts with evidence; document public API limits and
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


## 10. Implementation evidence — first query foundation (2026-09-05)

Delivered the generic `FactAt` / `FactIn` constraints and explicit exact,
document-root and inherited locality through the kernel and FFI. Existing
anchor queries retain their compatibility behaviour. Membership is one kernel
query with complete totals, ranking and pagination; negation complements the
whole set, including missing predicates, and equivalent sets retain cursors.

Added independently owned `search.operators` registrations, field bindings,
required index dependencies, strict bounded JSON string lists and visible
invalid/unavailable-filter errors. Search and filter blocks consume the same
registry. The unrelated row/label fixture runs without Tasks installed and
proves the type and field can compose without referring to each other's IDs,
predicates or types. The kernel does not interpret the fixture's pair encoding.

The large-result fixture exposed repeated display-fact reads when many hits
came from one document. Page hydration now reads its facts once per document,
retaining one document's fact rows in memory and preserving ranked result order.
The 10,051-match test pages every result with no source-file reads; the eight
native membership tests completed in 5.31 seconds on this workstation.

Validation:

- Rust workspace: **462 passed, 0 failed, 2 existing ignored tests**. Existing
  mDNS tests needed local networking outside the sandbox; the full rerun passed.
- Clippy: workspace/all targets, warnings denied, passed.
- Desktop against the rebuilt release kernel: **592 passed, 0 failures/skips**.
- Rebuilt Whisper-enabled Android kernels for arm64-v8a and x86_64. Bindings
  generated independently from both binaries match; desktop bindings came from
  its own release DLL. Android app and instrumented-test APK builds passed.
- No Android device was connected; device execution remains unverified.

This completes the generic membership subtask, not a whole delivery milestone.
At this first gate, source snapshots and Markdown locations were still
unimplemented; section 11 records their subsequent foundation. People pages,
its parser/codec, persisted item facts/search, SDK module separation, typed-page
creation and document refactoring remain outstanding. P0 product fixtures and
the rest of P1 stay open.


## 11. Implementation evidence — source-document foundation (2026-09-05)

Added a pure `gilune-document` crate with no kernel, filesystem, database or
application dependency. Its full-source UTF-8 snapshots retain BOM, CRLF,
Unicode and unknown YAML/body bytes. The shared Markdown map assigns continuation
lines and paragraphs to their whole list item while excluding nested items.
The 10,001-item fixture checks disjoint ownership. Item keys remain snapshot-local;
this does not yet change persisted index locations or search result scope.

The kernel/FFI now expose read-only document snapshots and validated change
preparation. A source version binds exact content and observed durable state;
an externally edited file cannot claim its old visible revision marker as its
verified revision. Tests cover head movement with unchanged file bytes, normal
file moves, copied identity markers, quarantine, conflicts and deletion states.
Unmanaged Markdown participates without adoption, metadata insertion or writes.
The existing reconciler can still quarantine external non-Gilune YAML; snapshots
report that state and do not clear it. Richer reconciliation in P1 is required
before claiming all-Markdown refactoring coverage in the running application.

Proposals reject stale sources, overlapping/invalid ranges, Unicode boundary
errors and protected metadata changes. Self-review fixtures also reject structural
YAML/alias changes that would alter identity, inserted closing delimiters and
ambiguous property-range guesses. The contract conservatively protects a complete
header when reserved keys cannot be mapped independently.

The Kotlin conformance consumer uses only generated public bindings, with no
application registry or domain plugin. The same snapshots and offset conversions
are available to both shells. The [v0.1 contract](../../contracts/documents/README.md)
records the parser versions, semantics and current limitations.

Validation against the final implementation:

- Rust workspace: **482 passed, 0 failed, 2 existing ignored tests**. Twenty new
  native conformance tests cover this foundation, including vault isolation.
- Clippy: workspace/all targets with warnings denied, passed.
- Desktop against the final release kernel: **595 passed, 0 failures/skips**.
  Validation exposed a missing Gradle test input: a rebuilt native DLL could
  leave JVM tests cached. The selected native library is now a declared test
  input, and the final desktop run executed after that fix.
- Whisper-enabled Android arm64-v8a and x86_64 kernels rebuilt. Independent
  per-ABI bindings match; desktop bindings came from its own release DLL.
  Android application and instrumented-test APK builds passed.
- Native Android kernel sizes: **7.75 MiB arm64 / 7.86 MiB x86_64**, below the
  existing per-ABI kernel budget. No Android device was connected; device
  execution remains unverified.

[ADR 0023](../decisions/0023-source-document-snapshots.md) records the source
boundary and its remaining integration gates. No milestone is being claimed complete. Remaining work includes scoped apply,
editor coordination, journals/recovery/undo, richer projection/property round-trip,
persisted item facts/search, the Kotlin SDK/domain build boundary and People.


## 12. Implementation evidence — Kotlin plugin build boundary (2026-09-05)

Separated the generated native boundary (`kernelBindings`), public value/callback
contracts (`pluginSdk`), Tasks implementation (`plugins:tasks`) and independent
query consumer (`plugins:contractFixture`) from the application. Tasks now owns
its declaration, indexer, query vocabulary, date resolver/provider and pure
editor-decoration logic in its own compiled module. Generic host registration
and lifecycle resolution remain in the application. The field-slot adapter that
needs the bundled catalogue now lives in the composition root.

The SDK imports no application or domain implementation. It exposes the existing
search/field/decoration contracts under `org.gilune.plugin.sdk`; Tasks uses
`org.gilune.plugins.tasks`. App and test consumers now import those contracts
explicitly. Native bindings retain their existing package/API and are generated
from each platform's binary into `kernelBindings`, with README/build-script/CI
paths updated. The fixture is an application test dependency only.

Added a mandatory build guard over each module's resolved platform compile
configuration. Domains may depend on SDK/bindings, never the application or
another domain implementation. Validation injected a Tasks dependency into the
unrelated fixture through a temporary Gradle init script: the guard rejected it
with an explicit boundary error. The normal dependency graphs pass.

Validation:

- **595 desktop tests passed, zero failures/skips**, against the existing release
  kernel through the new binding/SDK/domain modules.
- Android application and instrumented-test APK builds passed. The product APK
  contains Tasks classes and excludes the independent contract fixture.
- Every normal platform compile dependency graph passed the guard. An injected
  fixture-to-Tasks dependency was rejected specifically as a boundary violation.
- Android ABI binding hashes match at the new module path; each was generated
  from its own native binary. The existing native implementation was unchanged.
- Final platform build with the guard enabled and `git diff --check` passed.
  No Android device was connected; device execution remains unverified.
 No native/schema migration was needed. The source
contract is now [v0.3](../../contracts/bundled-extensions/README.md), with module
instructions in the [SDK README](../../app/pluginSdk/README.md).

Typed-page creation/property round-trip is the next P1 requirement. Inspection
confirmed the existing projection renderer still reconstructs its header and can
lose custom properties on reconciliation; that path needs full preservation
before People pages can use it. No typed-page, ownership, live-lookup or bulk-write
API is claimed by this module extraction. P1 remains open.


## 13. Implementation evidence — preserved page properties (2026-09-05)

Reconciliation now saves the exact front-matter envelope as a versioned,
kernel-owned revision payload field. The existing `text` field remains the body,
and unrelated payload properties remain intact. Projection updates its generated
metadata tokens from authoritative object/head state, preserving unknown fields,
nested YAML, comments, quoting, BOM and line endings. Property removal and
header-only edits become ordinary revisions and survive rebuilds.

Processor revisions inherit the current envelope within the revision transaction;
a domain payload replacement cannot remove or substitute it. Normal revision sync
carries the envelope. Legacy revisions keep their existing renderer; unknown or
invalid envelope versions hold projection back rather than invoking a lossy fallback.
Valid foreign YAML without reserved identity is retained as an unmanaged import.
Unsupported managed metadata shapes and existing quarantines remain explicit.

Native conformance scenarios cover property/body round-trip, restart/rebuild,
CRLF/BOM, property deletion, malformed/duplicate metadata, preserved comments beside
updated kernel fields, replica reconstruction from operations, invalid envelopes,
and processor replacement. A public Kotlin boundary test exercises reconciliation
and reprojection of custom properties against the packaged kernel.

Validation:

- Rust workspace: **490 passed, 0 failed, 2 existing ignored tests**. After the
  final foreign-YAML coverage change, the focused vault suite passed **62 tests**,
  including all **nine new native regression tests**.
- Final Clippy workspace/all-targets checks with warnings denied passed.
- **596 desktop tests passed, zero failures/errors/skips**, against the rebuilt
  release kernel and its generated bindings.
- Both Whisper-enabled Android kernels rebuilt (**7.83 MiB arm64 / 7.95 MiB x86_64**).
  Android application/instrumented-test APK builds and plugin dependency guards
  passed. No device was connected; instrumented execution remains unverified.
- `git diff --check` passed. No public FFI signature or SQL schema change.

The P1 typed-page checkbox remains open: scoped creation/ownership, structured property changes and
template/default-path contributions have not been implemented. Existing quarantine
recovery and a mixed-version writer gate are also still required; older projection
binaries do not understand the new envelope and must not be claimed compatible.


## 14. Implementation evidence — scoped typed-page creation (2026-09-05)

Added the [page contract](../../contracts/pages/README.md) and
[ADR 0024](../decisions/0024-typed-page-creation.md). Plugins declare `pages.types`
with literal templates/default folders under their own `<plugin-id>/<local-type>`
namespace. Creation requires an enabled plugin, declared/granted `pages.create`,
a current definition fingerprint and explicit editable source/path. The kernel
supplies identity/schema metadata and rejects foreign type claims and reserved
source metadata. There is no People/Tasks-specific dispatch.

Creation commits a user-authored object, initial revision/head, ordinary operation
and event in one transaction. The receipt means durable; normal projection then
publishes the first file. Request UUIDs make retry/restart idempotent without
resetting later edits. The requested path and ownership stay in kernel-owned
revision data, retained by processor updates and carried by sync. Pages rebuild
without their plugin. Local file moves retain the object identity.

First projection publication now uses a fully written temporary file and a hard
link that cannot replace an occupied destination. Files appearing after creation
remain intact; an object-specific alert reports the saved-but-unpublished page.
Both shells have shared wording for that condition. Resolving the collision lets
projection recover and clears the alert. Oversized encoded operations roll back
creation rather than committing a page beyond the default sync envelope limit.

The SDK exposes `PageCreationService`; the host binds its plugin identity once.
The unrelated Cards fixture declares a template and creates a page entirely through
the SDK, without importing People, Tasks or the application. Native tests consume
a stored versioned declaration fixture and cover authority, request reuse, stale
forms, restart, path collisions, replica reconstruction, local moves and exact
source preservation. Kotlin tests exercise creation and subsequent external edits
through the generated native boundary.

Validation:

- Rust workspace: **500 passed, zero failures, two existing ignored tests**.
  Nine new native regression tests cover creation and first publication. The
  stored declaration fixture was then rechecked in the eight-test creation suite.
- Workspace/all-targets Clippy with warnings denied passed.
- **598 desktop tests passed, zero failures/errors/skips**, including the SDK
  Cards consumer and the shared collision-alert wording checks.
- Both Whisper-enabled Android kernels rebuilt (**7.91 MiB arm64 / 8.01 MiB x86_64**).
  Independently generated Android ABI bindings match. Desktop bindings were
  generated from its own rebuilt release DLL.
- Android app/test APK builds and plugin dependency guards passed. No Android
  device was connected; device execution remains unverified.
- `git diff --check` passed.

This closes the P1 creation/property foundation checkbox, not P1 as a whole. Scoped existing-file
apply, journal/recovery/undo, standard page UI, portable override management, People
and persisted item facts remain open. The contract explicitly records hard-link
filesystem limits, ancestor-directory race limits, local-only move tracking,
retained retry evidence and the pending mixed-version writer gate. No SQL migration
was needed; new FFI records/methods require per-target generated bindings.


## 15. Implementation evidence — People pages and generic frontmatter access (2026-09-05)

People is now a separate SDK-only module selected at the composition root. It
supplies person and connection-definition page types, literal initial bodies,
default folders, source producers, indexed row presentation and two collections.
Generic desktop/Android forms create these pages and open their Markdown. Person
properties preserve display spelling while normalized handles drive duplicate
checks. Assign and Owner are explicit creation presets; arbitrary keywords and a
boolean retired property are portable page content. Retirement keeps existing
entries visible. This is the catalogue/page foundation, not connection extraction.

Integration exposed a general framework omission: `NoteIndexer` receives the
body only, although the kernel separately indexes frontmatter. Added an opt-in
`SourceIndexer` callback and SDK registration for **any plugin**, plus a shared
pure YAML scalar reader. The unrelated Headers fixture consumes these public
APIs with no People, Tasks or host dependency. Existing body callbacks keep their
current input/line semantics. Source and body callbacks share the same maintenance
read; the kernel knows nothing about the fields or their domain meanings.

Property access resolves aliases/merges using the source snapshot's YAML parser,
without building the Markdown item map or hashing the body. The pinned normalization
helper uses full non-Turkic Unicode 9.0 case folding with Unicode 16.0 NFC tables;
plugins explicitly choose this equivalence. Authored spelling is preserved. A table
upgrade requires collision/compatibility review and indexer-version changes.

Search results now carry document-root display facts even with zero requested
lines or an empty body. Collections query and page entirely through the kernel.
Duplicate checks count identifiers across the full collection; displayed rows and
counts must share an index generation. Creation preflight is advisory under
concurrent/offline edits; duplicates remain distinct, visible pages rather than a
first-match binding. There are currently up to 50 additional indexed count queries
per page. On this machine, the 125-page fixture's first page plus 50 identifier
counts took **21 ms**. This is a small measured baseline, not the P6 large-vault gate.

Forms preserve the request UUID/input through uncertain commit/publication errors
while open. Publication still uses the tested typed-page protocol and exposes path
collisions through existing alerts. Bundled first installation grants declared
local page creation; restart does not restore revoked grants or removed plugins.
Collections disappear with their owner, while Markdown and generic editing remain.

Validation:

- **504 native tests passed, zero failures, two existing ignored tests** in the
  final full workspace run. Four new native regressions cover scalar YAML types,
  pinned folding, root display facts and source/body callback compatibility with
  one source read. An earlier full run hit the existing sync port-reuse assertion;
  the rerun and final source-contract run both passed.
- Final workspace/all-targets Clippy with warnings denied passed.
- **609 desktop tests passed, zero failures/errors/skips**, against the rebuilt
  release DLL. These include eight People integration tests, two collection UI
  tests and the unrelated Headers SDK consumer. Early UI runs stalled in Skia;
  the final stable-header/list layout passed both UI tests in the full suite.
- Both Whisper-enabled Android kernels rebuilt (**8.09 MiB arm64 / 8.19 MiB x86_64**).
  Each platform's bindings were generated from its own binary; independently
  generated Android ABI bindings match. App/test APK builds and all plugin
  dependency guards passed. No Android device is attached; device tests are unrun.
- No SQL schema change. FFI callback/record additions require matched regenerated
  bindings and binaries. Final logs are under `app/build/people-source-*`.

P2 remains open: automatic default catalogue initialization, persisted portable
folder/template overrides, Android property editing, dedicated detail/query
sections and reference resolution are not complete. Mentions, labelled/paired
connection facts, live lookup, backlinks and generic refactoring remain later
work. Desktop properties currently use the existing generic editor; editing a
handle or keyword there does not rename references elsewhere. No reminder engine,
hidden aliases or People-to-Tasks dependency was introduced.


## 16. Implementation evidence — shared property editing (2026-09-05)

Added pure property-sheet inspection and scalar-change proposals to the
[document contract](../../contracts/documents/README.md). These APIs are generic;
neither the Rust implementation nor the shared form knows People fields or any
other domain schema. Both desktop and Android now use the same properties form.
Person names/handles and connection labels/retirement can be edited there.

The old desktop implementation reconstructed a flattened header. It has been
removed. The new service locates independently editable scalar tokens, preserves
unchanged source, validates the resulting YAML, and reuses protected-metadata and
source-version checks. Adjacent comments, nested data, quotes on unchanged values,
BOM, existing newlines, identity and body bytes survive. Removing a property removes
its line/inline comment, retaining standalone comments; removing the final property
leaves an empty envelope. Read-only identity and structured values are available
under a disclosure control. Aliases, merges, collections and multiline source
values are not flattened into editable fields.

String fields display interpreted text and quote changed values automatically,
so entering `true` for a name retains a string. New values can be entered as YAML
scalars. Pure proposals perform no file writes. The form captures full source and
applies only the reviewed header if the current buffer still has it; later body
edits and selection remain in place. Android property-only changes now participate
in dirty state, autosave, stale-file protection and leave-editor saves. Leading
BOMs stay in the preserved prelude when no header exists, so adding properties
cannot move the BOM into the body.

A conservative snapshot-signature adjustment permits deleting a user property
beside kernel metadata while retaining its trailing standalone comments. Those
comments may become adjacent to the kernel entry; that adjacency is no longer
misidentified as changed identity bytes. Original protected ranges still reject
edits to the protected bytes, and reserved values/signatures remain validated.

Validation:

- **508 native tests passed, zero failures, two existing ignored tests** in the
  full workspace run. Four new document tests cover exact scalar edits/removal,
  Unicode/BOM/CRLF, comments/quotes/empty values, additions, aliases, protected
  metadata, malformed input and stale versions.
- Final workspace/all-targets Clippy with warnings denied passed.
- **610 desktop tests passed, zero failures/errors/skips**, against the release
  DLL and regenerated bindings. Legacy header-reconstruction assertions were
  replaced with source-preserving boundary tests; shared UI tests cover string
  types, later body edits and concurrent header changes. Dialog rendering was
  verified after deferring presentation until its fields are loaded.
- Both Whisper-enabled Android kernels rebuilt (**8.21 MiB arm64 / 8.32 MiB x86_64**).
  Each platform uses bindings from its own binary; independent Android ABI
  bindings match. App/test APK builds and plugin dependency guards passed.
  The Android property dirty/save regression compiles in the instrumentation
  APK; no device is connected, so its execution remains unverified.
- `git diff --check` passed. No SQL schema change. Evidence logs use the
  `app/build/property-editor-*` prefix.

P2 is still open for portable folder/template overrides, default catalogue
initialization, dedicated detail/query sections and reference resolution. General
filesystem replacement/journal/undo remains P4. Property saving uses the existing
editor guard and reconciliation path, not a newly claimed atomic external-editor
protocol. Editing a handle or keyword here does not yet rename references across
documents; that remains the shared refactoring workflow.

## 17. Implementation evidence — portable creation defaults (2026-09-05)

The [page contract v0.3](../../contracts/pages/README.md) adds an optional
`defaults_for` declaration on a companion page type. Both types belong to the
same plugin; foreign targets, duplicate declarations and chains are rejected.
Defaults are normal managed Markdown documents with a string `folder` property
and a body containing the literal full-source creation template. An empty folder
means the vault root. No private setting, second authoritative store or domain
dispatch is involved. People declares separate companions for its person and
connection types; an unrelated Cards consumer exercises the same SDK/FFI contract.

The kernel reads current revisions to resolve defaults, without reading vault
files or relying on derived search indexes. Multiple objects/heads, malformed
configuration, quarantine and pending deletion block the affected target with a
diagnostic; they never choose a winner or silently restore shipped defaults.
Local duplicate creation is refused transactionally; offline duplicates remain
preserved on sync. Configuration pages retain normal identity/history through
local moves and restarts. Confirmed deletion restores shipped defaults.

Definition fingerprints include the companion declaration, selected revisions
and resolution state. Form preparation reconciles only the known configuration
paths. Creation checks the fingerprint and the recorded file digest, rejecting
unreconciled external edits; saved-but-unpublished defaults cannot be consumed.
These reads prepare/validate creation, not search. This remains an observed file
check, not a filesystem lease against edits immediately afterwards. Unsaved
buffers are not saved defaults, and unmanaged lookalikes are not auto-adopted.

Both collection UIs offer Page defaults. First use creates a portable defaults
page under its declared folder; later use opens that page in the normal editor.
Creation receives the effective template through the public callback, fixing the
previous hardcoded People body. The shared pure property helper applies only
form-supplied fields while preserving other properties, comments and body.
Templates can include their own frontmatter; unsupported field replacements
fail visibly. Existing pages remain unchanged. Changed defaults require reload
and review, with a template preview; entered form values remain available.
Uncertain publication retries retain the original request, as with other pages.

Validation:

- The Rust workspace passed **513 tests**, with zero failures and two existing
  ignored tests. The final focused page suite passed **14 tests**, including one
  subsequently added deletion-resolution test and the final publication guard.
  New coverage includes CRLF/Unicode/template properties, root folders, no-read
  definition lookup, stale sources/forms, malformed config, local duplication,
  offline duplication/sync, moves, restart, disable, retries and deletion.
- Final workspace/all-targets Clippy with warnings denied passed.
- Final **612 desktop tests passed**, zero failures/errors/skips, against the
  rebuilt release binary. The shared UI test creates defaults, edits them outside
  Gilune while a form is open, reloads, creates with the revised folder/template,
  and reopens the existing defaults page. The unrelated Cards SDK test preserves
  unknown template properties and verifies previously created content stays put.
- Both Whisper-enabled Android kernels rebuilt (**8.24 MiB arm64 / 8.35 MiB
  x86_64**). Bindings come from each platform's own binary; independently generated
  Android ABI bindings match. Android app/test APK builds and plugin dependency
  guards passed.
  No device is connected; device execution remains unverified.
- No SQL migration. The native definition record and SDK creation callback
  changed; matched binaries/bindings are required. Logs use the
  `app/build/page-defaults-*` prefix. `git diff --check` passed.

P2 remains open for default catalogue initialization, dedicated detail/query
sections and reference resolution. Mentions, paired connection facts, live lookup,
backlinks and durable refactoring remain later work. This milestone does not
introduce a People dependency on another domain plugin.

## 18. Implementation evidence — initial connection catalogue (2026-09-05)

People now declares Assign and Owner through the generic `pages.initializers`
contribution in [page contract v0.4](../../contracts/pages/README.md). Both shells
prepare enabled contributions at startup/enable and before collection display.
The former Add Assign/Add Owner presets have been removed; New connection remains
available for user-defined keywords. People supplies names, scalar properties,
page types and filenames. No People keyword or schema switch enters the kernel.

Each initializer declares a dedicated receipt type and a bounded set of initial
pages. The shared service applies declared properties to the effective templates,
using effective folders, then commits the pages and a visible Markdown receipt
together through the existing page-creation transaction. A failure after preparing
an earlier page rolls the entire set back. Normal projection publishes afterward;
its existing collision/recovery protocol remains responsible for files.

The receipt is normally `Settings/Initialization/people-catalogue.md`. Its object
history, including deletion, prevents subsequent initialization. Changing receipt
text, renaming/retiring/deleting definitions, restarting or re-enabling the plugin
does not restore defaults. If any target-type history predates initialization,
the complete catalogue is preserved and only a receipt is created; missing default
keywords are not backfilled. Receipt/history retention is an explicit contract.

Fresh-scope discovery reconciles source files and inspects known import/quarantine
paths through shared source snapshots in bounded pages. This handles quoted and
aliased YAML type values without trusting the legacy raw frontmatter fact encoding.
Malformed/unreadable source that prevents determining scope defers setup visibly.
No file is adopted simply to initialize defaults. Existing target history bypasses
this discovery; existing receipts additionally make subsequent calls read no vault
files. Preparation is separate from collection queries and pagination. Failures
remain retryable without preventing vault opening or reading existing collections.

Initialization uses existing sync operations. A seed arriving before its receipt
suppresses another seed set; receipt-first delivery also suppresses reseeding.
Two devices initializing independently while offline preserve distinct objects and
surface occupied-path alerts during publication. This is explicit conflict handling,
not distributed exactly-once execution or a hidden merge. A source check likewise
does not serialize arbitrary external editors. No new SQL migration or operation
kind was introduced; FFI and SDK gained the scoped initialization method/result.

Validation:

- The Rust workspace passed **520 tests**, zero failures, two existing ignored
  tests. The final focused page suite passed **20 tests** after the existing-history
  fast path was added. Six new native regressions exercise transaction rollback,
  retry/restart, edits/deletion, lifecycle/grants, foreign ownership, imported YAML,
  malformed source, partial sync delivery and offline publication conflicts.
- Final workspace/all-targets Clippy with warnings denied passed.
- **614 desktop tests passed**, zero failures/errors/skips. The unrelated Cards
  consumer initializes through the SDK, preserves unknown template properties and
  retains later edits. People tests verify default facts, renamed/retired keywords,
  re-enabling and export; the collection UI verifies default visibility and custom
  connection creation. The final full run used the rebuilt release binary.
- Both Whisper-enabled Android kernels rebuilt (**8.32 MiB arm64 / 8.44 MiB
  x86_64**). Each platform's bindings came from its own binary; independent Android
  ABI bindings match. Android app/test APK builds and plugin dependency guards
  passed. No device is attached, so device execution remains unrun. Evidence logs
  use `app/build/page-initialization-*`. `git diff --check` passed.

P2 still needs dedicated detail/query sections and reference resolution. The next
substantive capability is recognition of mentions and labelled connections, scoped
paired facts, indexed queries and lookup. General refactoring/renames remain later
milestones; directly editing a keyword still does not rename its occurrences.

## 19. Implementation evidence — scoped reference facts (2026-09-05)

People now uses scoped indexer version 3. Plain mentions, labelled keyword/handle
pairs and explicit page-root `connections` mappings emit the fixed text facts
specified above. Recognition retains exact UTF-8 token ranges, uses the shared
Markdown ordinary-text/item map, excludes code/escapes/URLs/emails/HTML and does
not import or name Tasks, Projects or Decisions. Unknown keywords and unresolved
handles retain their lexical facts. Definition validity and reference validity
remain separate; malformed structured connection entries emit diagnostic facts.

The generic source snapshot now supplies typed YAML values to every plugin, with
mapping/sequence/scalar/tagged values and typed keys preserved. `SourceView` and
`SourceValue` are pure SDK helpers. People's property and connection interpretation
stays in its own module; no People property dispatch was added to the kernel.

Kernel derived index schema 3 stores root/line/item facts in the existing facts
table and compact source maps without duplicating prose. Per-namespace source
receipts record successful zero-fact outputs as well as populated results. Every
required contributor must have processed the same source version: absence from
another revision cannot satisfy a negative filter. `queryLocations` exposes exact
fact conjunction/membership, explicit root scope, source-qualified ranges and
complete generation-bound pagination. Legacy line APIs exclude item facts.

An initial correlated query was stopped after exceeding one minute on the large
fixture. Replacing repeated correlated CTE scans with tuple membership and an
exact-positive candidate set brought complete 10,005-item debug discovery to
approximately 1.4 seconds. This measured the entire result set, not first-page
latency. The owner's question prompted a separate release comparison.

Release measurement: one list item per physical line, identical two-fact matches,
same vault/index, median of three warmed runs. First pages contain 50 results;
complete enumeration uses 500 per page. Both paths read zero source files.

| Fixture | New scoped first page | Existing line-search first page | New scoped complete | Existing line-search complete |
| --- | --- | --- | --- | --- |
| 100 items | 0.77 ms | 0.73 ms | 0.73 ms | 0.79 ms |
| 10,005 items | 46.1 ms | 62.6 ms | 0.81 s | 1.60 s |

The old bounded anchor-only query took 44.7 ms for the first 50 matches in the
larger fixture. Existing complete search also computes counts/ranking/snippets;
scoped discovery supplies locations/ranges. These are comparisons of the existing
and new execution paths on equivalent matches, not identical response payloads,
historical production telemetry or measured UI typing latency. The manual
`index::scoped::tests::latency` release test reproduces the comparison and is
excluded from ordinary CI timing gates. No regression budget is asserted yet.

Validation:

- **527 native workspace tests passed**, two existing tests ignored. Seven new
  scoped-index tests cover independent producers, nested/continuation scope,
  paired membership, negatives, empty-output version evidence, lifecycle, rebuild,
  cursor expiry and more than 10,000 matches without source reads. The additional
  manual release benchmark passed. Workspace/all-targets Clippy passed.
- **622 desktop tests passed**, zero failures/errors/skips, using the final release
  DLL. New People and generic SDK tests cover Unicode/source ranges, recognition
  exclusions, structured aliases, invalid data, removal/rebuild, scope queries and
  registration input collisions. Plugin dependency guards passed.
- Both Whisper-enabled Android kernels rebuilt (**8.49 MiB arm64 / 8.63 MiB
  x86_64**). Independent ABI bindings share SHA-256
  `3E0CD5BD1B8AEF1F2E68C73AE6E3BC04B706E81FDCF7FC79E2E6C4A97681BA88`.
  App and instrumentation APK builds passed; no attached device, so device tests
  remain unrun. Logs are under `app/build/scoped-*`.

P3 remains open. The next integration is item-scoped complete application search:
text/residual matching, ranking, grouping/snippets and independently contributed
type/operator constraints. Tasks must migrate independently to shared locations;
People must not know it exists. The new fact-discovery API does not yet provide
those search behaviours or negative-only result universes. People search operators,
lookup, reference resolution and backlinks are still pending. P4/P5 refactoring
apply/journals/undo and actual keyword/handle rename workflows remain later work.

## 20. Implementation evidence — complete item search (2026-09-05)

The kernel now serves complete `search_items` pages over shared Markdown
locations. Fact joins, whole-item text/residual matching, whole-clause negation,
negative-only selection, ranking, grouping, counts and pagination use the indexed
snapshot. Source maps retain ranges and existing indexed lines supply prose; no
source-file reads or Markdown parsing occur during search. Results carry own text,
UTF-16 highlights, exact item facts and source-qualified full/own byte ranges.
Nested items remain independent; containing-page predicates require explicit
scope. Document searches can match contained item facts without promoting those
facts into document-root display properties. Cursors cannot cross result modes.

Derived index schema 4 records scoped namespace mode, rejecting a mode change
without a producer version bump. Source-qualified front-matter receipts support
explicit page filters. This rebuilds disposable data, without rewriting Markdown.

The SDK exposes `SearchType.itemScoped`. Tasks independently adopts it and the
shared scoped-input contract with producer version 2. It contributes checkbox/date
facts on generic Markdown items, including continuation dates. People separately
registers reference, mention, connection and explicit page-connection operators.
The motivating query is now executable in search and embedded filter blocks:

```text
type:task
people.connection:assign IN ["alice", "bob"]
```

`NOT IN` uses the same public typed-set binding. No People implementation imports
or names Tasks; a separate outline type proves the same composition without Tasks
installed. Desktop and Android filter callers receive the shared operator registry.
Android's guarded checkbox adapter uses the first physical line of an item result,
preserving continuation content and nested items. Device execution remains untested.

The legacy scan oracle remains independent and covers one-line compatibility.
Its prose fixtures now use blank lines to separate prose from lists: unindented
prose immediately after a list paragraph is a Markdown lazy continuation and
properly belongs to the item under the new contract. New item-specific tests
separately verify continuation and nested semantics.

Performance, same synthetic fixtures and release benchmark as section 19, median
of three warmed runs; first page is 50 results, complete enumeration is 500/page:

| Corpus | Complete item search, first page | Existing line search, first page | Complete item search, all pages | Existing line search, all pages |
| --- | --- | --- | --- | --- |
| 100 one-line items | 1.04 ms | 0.79 ms | 1.12 ms | 0.95 ms |
| 10,005 one-line items | 94.57 ms | 65.99 ms | 2.08 s | 1.94 s |

Both paths read zero source files. Item search returns richer text/range evidence;
these are equivalent matches, not identical payloads or real UI typing latency.
The new path is still slower. Candidate selection now starts from an available
positive exact fact instead of expanding every source-map item; already-decided
fact conditions are not evaluated twice. Full enumeration still repeats complete
selection/ranking per page. No latency regression budget or performance-gate
completion is claimed; further profiling and representative vault measurements
remain necessary. The earlier 1.4-second debug measurement was complete scoped
discovery, not first-page UI latency.

Validation:

- **531 native workspace tests passed**, with two existing ignored tests and the
  manual benchmark excluded from ordinary runs. Four new native search tests cover
  UTF-8/CRLF/BOM coordinates, nested scope, root filters, negatives, callbacks,
  sorting, caps, cursor invalidation and indexed reads after external edits.
  The manual release benchmark and workspace/all-targets Clippy passed.
- **625 desktop tests passed**, zero failures/errors/skips, against the rebuilt
  release DLL. Integration covers the actual People/type query, filter counts,
  independent outline consumption, plugin removal, rebuilds and continuation dates.
  The full scan-equivalence suite and plugin dependency guards passed.
- Both Whisper-enabled Android libraries rebuilt (**8.58 MiB arm64 / 8.72 MiB
  x86_64**). Independent ABI bindings share SHA-256
  `9F3E89B4B7DD84211C647EF1DB7270795A65536DB0DDC7C4B4E9A103417FD28D`.
  App and instrumentation APK builds passed; no device was attached, so device
  tests, including the added guarded multiline-checkbox regression, were not run.
  Logs are under `app/build/item-search-*`.

P3 remains open for generic async indexed lookup/editor triggers, reference
resolution and invalidation, navigation/backlinks and standard query sections.
P4/P5 shared refactoring plans, apply/recovery/undo and keyword/handle rename
workflows remain open. This section does not claim completion of those milestones.

## 21. Implementation evidence — ranked lookup and directory consumer (2026-09-06)

The shared kernel `lookup` contract now provides root-fact conjunctions plus
ranked alternative conjunctions, deterministic declared sort fields, complete
totals and generation-bound paging. It reuses existing fact constraints, including
prefix, and schema 4 source maps/receipts. Source-root matches are deduplicated
without collapsing distinct files that share an identifier. Only declared
namespaces and source-aligned facts participate. Pending, missing or failed
producers remain visible, including zero-fact evidence for absence checks. Lookup
performs no source-file reads, Markdown parsing or plugin callbacks.

`IndexedLookupRegistration` contributes pure query and row callbacks through
`lookup.indexed`. The host resolves exact enabled owner/contribution identity,
executes one bounded kernel page on a worker, and rechecks lifecycle/settings
before publishing. Callbacks run outside native indexing/search locks and never
receive a store or filesystem handle. `LookupContext` carries request/buffer/caret,
provider and fragment identity. Shared asynchronous publication also keys by plugin
state and index refresh, clearing old rows and discarding late worker results.
There is no persistent lookup cache to invalidate.

People independently declares exact-handle, handle-prefix and normalised-name
prefix alternatives, in that order. Sorting uses normalised name, handle and path.
The provider preserves authored display spelling and returns exact insertion text;
incomplete handles remain visible without insertion. Duplicate handles retain
separate source rows. The independent compiled Labels fixture uses the same SDK
and host service without People or Tasks installed.

The standard page-collection contract now accepts an owned lookup provider. The
People directory's Find field uses it on both platforms: complete prefix matching,
30-row pages, Load more, counts and opening the selected source. Unknown text
creates no page; New person remains an explicit action. The UI does not filter
the first directory page or join per-person searches to implement lookup.

Performance evidence uses 10,006 equivalent indexed roots in a synthetic derived
database fixture, independently of filesystem/indexer throughput. Release median
of three warmed first-page runs: **203.95 ms for 30 rows**; complete enumeration
at 500/page: **4.30 seconds**, zero source reads. The first implementation measured
533.20 ms / 11.52 seconds. The final query aggregates sort values once, computes
totals with ordinary page selection and hydrates only retained roots. This remains
a performance limitation: no typing-latency target, small-vault budget or P6
performance completion is claimed. It is not a comparison against section 20's
different item-search fixture. More selective prefixes and representative vaults
still need measurement before editor performance acceptance.

Validation:

- Four native lookup tests cover ranking/alternatives, literal punctuation,
  missing sort values, root scope, absence, undeclared producers, source-version
  mismatch, cursor invalidation and complete paging beyond 10,000 roots.
- **631 desktop tests passed**, zero failures/errors/skips, against the final
  optimized release DLL. New integration tests cover People and independent SDK
  lookup, Unicode, incomplete/colliding definitions, late-page matches, rebuild,
  removal, registration gates, asynchronous stale-result rejection, directory
  navigation and loading beyond 30 results. Dependency-boundary checks passed.
- Both Whisper-enabled Android kernels rebuilt (**8.65 MiB arm64 / 8.80 MiB
  x86_64**). Independently generated ABI bindings share SHA-256
  `2A8B580E9C260E91481E44B5F66C62EB966E346F7F81A0B3529ED838B90BE23E`.
  App and instrumentation APK builds passed. No device was attached; Android
  runtime/device checks remain unrun. Logs are under `app/build/lookup-*`.

The final native workspace run passed **535 tests**, with two existing ignored
tests and the separate manual item-search benchmark ignored. Workspace/all-targets
Clippy passed. The existing sync timing gate first exceeded its five-second budget
(6.20 seconds) while native/Android builds competed for resources; it passed
unchanged in the complete rerun after those builds finished. The earlier result is
retained in `lookup-workspace-contended.log`; no timing assertion was weakened.

P3 remains open. This delivery supplies indexed lookup and a directory consumer;
it does **not** yet connect `@` or `/` editor triggers, replace the task-specific
trigger recogniser, insert into editor buffers, offer create-from-lookup, resolve
references or provide backlinks. Those must use the public lookup/context seam
and version-checked editing, without People-to-Tasks dependencies. P4/P5 shared
refactoring and rename/merge workflows remain open.

## 22. Lookup profiling evidence (2026-09-06)

The [SQLite profile](../research/lookup-performance-2026-09-06.md) records actual
expanded SQL, query plans, test-only phase instrumentation and controlled
experiments. The warmed first-page median is 198.51 ms: 178.62 ms executing the
main query and 19.22 ms checking producer/source freshness. SQL composition is
0.035 ms. This benchmark contains no Kotlin or DSL translation.

Existing SQL indexes serve equality, but prefix expressions only seek by
predicate. Sort preparation covers all roots even when one matches; complete
enumeration repeats selection/ranking/counts per page. A distinct-key fixture
still takes 130.40 ms for one match and 64.63 ms for none (main SQL only).

Before accepting editor lookup performance, reduce sort preparation to matching
roots, prove an indexable folded-prefix operation and its storage cost, and
replace repeated source-alignment scans only with equivalent maintained evidence.
Re-measure full enumeration afterwards. These are generic kernel improvements;
plugin ownership and shell responsibilities remain unchanged. The investigation
changes no production SQL/indexes and does not mark P3 or P6 complete.

## 23. Candidate-first lookup and indexed prefixes (2026-09-06)

Implemented the two agreed optimizations in generic kernel code. Alternative
constraints produce candidate sets and minimum priorities; only matching roots
drive sort-field reads. Base fact checks at selected roots explicitly use the
existing path index to prevent SQLite choosing repeated broad equality scans.
Negative-only alternatives, duplicate facts, missing/multiple sort values and
source-aligned namespace boundaries retain their semantics.

Derived index schema 5 adds a compact `(predicate, gilune_fold(value))` expression
index. The existing simple fold is stored on fact writes; the shared Prefix
compiler uses Unicode-correct BINARY range bounds. No plugin, DSL or shell API
changes. Schema 4 indexes rebuild through the ordinary disposable-index mechanism,
with tests proving authored source preservation.

[Optimization evidence](../research/lookup-optimization-2026-09-06.md) retains
expanded SQL and query plans. Main SQL for one matching root fell from 130.40 ms
to 0.061 ms, and no-match from 64.63 ms to 0.023 ms. Complete selective calls still
take 20–23 ms because freshness checks are unchanged. Broad first-page median is
156.41 ms in the instrumented run (previously 198.51 ms); the separate scale run
measures 142.80 ms / 3.12 seconds for first 30 / full enumeration (previously
203.95 ms / 4.30 seconds). All measurements are native synthetic release runs,
not a device/UI latency budget. The new index occupies 836 KiB in this fixture.

Tests compare indexed prefixes with the old literal expression over Unicode,
empty/NUL prefixes and punctuation, prove zero read-time folding calls, and cover
index updates/deletes and schema rebuild. A normal test limits selective SQL
work over 10,006 roots to fewer than 3,000 VM steps with no automatic index;
the profile measured 481 steps. This guards bounded work without a timing assertion.

Validation: **538 native workspace tests**, workspace/all-targets Clippy and
**631 desktop tests** passed. Desktop tests used the rebuilt, staged release
kernel and its own generated bindings. Both Android kernels and app/test APKs
built; independently generated ABI bindings matched. No Android device was
attached, so device/runtime checks remain unrun. Binary hashes, sizes and log
locations are recorded in the linked optimization evidence.

Freshness evidence reuse, later-page selection/count reuse and representative
write/device measurements remain open. P3 editor flows and P4/P5 refactoring
delivery are unchanged; no P6 performance completion is claimed.

## 24. Registered ordering and deferred-count prototype (2026-09-06)

At the owner's request, a test-only prototype evaluated reusable ordering rows,
keyset continuation, maintained producer/source alignment and exact counts outside
the page request. [Evidence and production work](../research/lookup-ordered-prototype-2026-09-06.md)
include expanded SQL, query plans, storage/write costs and limits. No production
schema, API or shell behavior changes in this experiment.

With 50,000 matches, complete prototype page calls measure **0.617 ms for 30** and
**4.244 ms for 500**, including display hydration and maintained freshness checks.
Page 19 at 500/page is **4.291 ms**. Full enumeration of all 50,000 is **735.71 ms**;
the previous 50,000-root full enumeration was not measured. The first exact count
still takes **324.19 ms**, requested separately and reused only for the same query
and generation. Foreground pages remain **0.666 ms for 30** during a count on an
independent read connection. The current FFI's Store mutex means that merely
moving the existing count API to a worker would not produce this concurrency.

The ordering adds **8.17 MiB**, with an initial build of **630 ms** for this fixture.
Simulated one-root version/fact/receipt updates and commits measure about **1.4 ms**
both with and without maintenance; this is not a measured zero-overhead guarantee.
A negative-only no-match query still takes **111.55 ms**, so candidate selection
and fallback execution remain necessary. No universal latency budget is claimed.

Conformance covers existing query equivalence, stable pagination, stale cursor/
count rejection, ordering invalidation and maintained mismatch counts compared
with the scan oracle through edits, deletion, movement and rollback. Before
promotion, define owned/bounded layout registration, transactional maintenance
and recovery, page/count API versioning, cancellable read workers, lifecycle
handling, realistic query dispatch and platform validation. The linked report
records this sequence. P3/P6 remain open; this is evidence for the next design,
not a shipped optimization or a completed performance gate.

Validation: all seven normal lookup tests, workspace/all-targets Clippy and the
isolated 50,000-root prototype benchmark passed. No platform binaries were
rebuilt for this test-only experiment; application integration remains pending.

## 25. Real Tasks and People DSL performance baseline (2026-09-06)

At the owner's request, the next benchmark uses actual Markdown, independently
registered Tasks and People indexers, DSL compilation and the release FFI path.
The [query definitions, timings and SQL plan](../research/cross-plugin-search-2026-09-06.md)
distinguish this item-search path from the earlier synthetic root-lookup tests.

The query for open tasks assigned to Alice and due in September 2026 correctly
matches 3,500 of 10,000 tasks and 17,500 of 50,000. Warm first-50 latency is
**138 ms / 1,355 ms** respectively. Enumerating all matches at 500/page takes
**0.99 seconds / 47.44 seconds**; due ordering makes the 50,000-task traversal
**56.18 seconds**. DSL compilation is approximately **0.10 ms**.

A separate read-only native profile shows indexed equality/range access but
five materialized fact sets, temporary sorting and full candidate processing.
SQL composition takes **0.016 ms**, selection **756 ms**. Each page repeats the
work. The native service and desktop timings are separate measurements, not an
isolated measurement of FFI overhead. Correctness assertions cover month bounds,
multi-person membership, completed tasks, continuation fields, role pairing,
page/task separation, unique enumeration and due ordering across pages.

Before accepting the query-performance gate:

- [ ] Compare generic constraint coalescing, implied-constraint removal,
  selective seeds and covering indexes using these actual compiled queries.
- [ ] Extend bounded ordering/count work to source-qualified item targets;
  select the page before reconstructing text and loading display facts.
- [ ] Re-run this fixture plus sparse and mixed-document workloads, preserving
  plugin independence, exact scope and counts, and source/version guarantees.

This change adds diagnostic tests and evidence only. The root-lookup prototype
has not been promoted, and P3/P6 performance acceptance remains open.

## 26. Compact targets and structured-record comparison (2026-09-06)

The owner authorized an architectural prototype rather than another feature-level
optimization. [Implementation, evidence and revised recommendation](../research/record-index-prototype-2026-09-06.md)
compare plugin-declared records with an improved facts representation over the
same real 50,000-task/500-document fixture. All additions are test-only.

Both designs use compact source-qualified targets, coalesced bounds, bounded
candidate selection, SQL ordering/continuation, separate counts and post-page
projection. The repeated run selects 50 Alice/month targets in **0.114/0.125 ms**
for records/facts. Including existing text/fact projection takes **6.19/6.59 ms**;
all 17,500 results take **2.44/2.46 seconds**. A second retained run shows timing
variation; these are native prototype measurements, not application latency.

Records use **6.45 MiB** and facts **8.30 MiB** for the isolated query stores,
excluding shared source text/maps and unrelated data. Initial population from
already extracted data takes **422/462 ms**. Replacing a source's 100 tasks takes
**4.06/3.36 ms** and correctly removes that source's 35 Alice/month matches.
Exact counts remain mixed: Alice takes **232/32 ms**, Alice/Bob **20/31 ms**.
Explicit target-key probes did not remove all record count regressions.

The evidence supports the common target/query foundation first. Structured
records should become an additional plugin contract for correlated typed fields;
scalar facts remain useful. Do not treat this experiment as proof that every
plugin needs a separate physical table, or that facts inherently cause latency.
Keep public logical declarations separate from kernel physical storage choices.

- [ ] Design production compact targets, one query execution contract, page/count
  semantics, producer completeness and compatibility with existing fact producers.
- [ ] Integrate bounded page selection and generation-bound counts/read workers;
  profile remaining projection costs instead of adding feature-specific engines.
- [ ] Design owned/versioned structured-record SDK declarations and query bindings,
  with People, Tasks and an unrelated consumer independently using the contract.
- [ ] Resolve count regressions and test different source shapes, cardinalities,
  nullable ordering, lifecycle/recovery and supported platforms before promotion.

Three prototype contract tests, two final benchmark runs, four existing item-search
tests and kernel test-target Clippy passed. No production schema/API/plugin
dependency or application behavior changed. P3/P6 acceptance remains open.

## 27. Production compact-target foundation (2026-09-06)

[ADR 0025](../decisions/0025-compact-index-targets.md) introduces the first production
step from the comparison. Derived schema 6 has private source/target/predicate IDs,
revision-checked fact bindings and two covering indexes. Existing producer inputs
and public source-qualified results remain unchanged. People and Tasks continue
to contribute independently. The location-query API now projects ranges from
target rows; complete task-search selection still uses its previous executor.

- [x] Maintain root/line/item targets through source replacement, text-only
  rebuild, moves, deletion, namespace removal and transactional rollback.
- [x] Preserve stale producer evidence without binding it to a new source revision;
  leave unversioned legacy facts unbound. Rebuild older derived schemas normally.
- [x] Consume target metadata through existing location-query projection and
  cover CRLF/Unicode and independent-producer conformance scenarios.
- [x] Profile large-document target maintenance and remove repeated full-map JSON
  extraction. Store the parser version once per source-map write.
- [ ] Integrate the shared declarative executor, bounded SQL page selection,
  generation-bound independent counts/read workers and the existing search API.
- [ ] Measure complete fact-write/reconcile overhead and temporary index growth;
  remove redundant representations only after all consumers have migrated.

The [isolated in-memory map replacement profile](../research/compact-target-maintenance-2026-09-06.txt)
reports **1.14/9.03/84.73 ms**
for 100/1,000/10,000 items after warm-up, versus **884 ms** at 10,000 items before
removing repeated version extraction. The control with target triggers disabled
takes **0.03/0.35/3.21 ms**. This measures source-map and target-row replacement,
not parsing, plugin execution, fact writes, filesystem reconciliation or durable
commit latency. It does not establish a complete indexing budget.

This transitional schema retains wide fact columns/indexes and source-map JSON;
its storage cost is additional, not the prototype's isolated compact-store size.
The Alice/month prototype results remain evidence for the next executor step,
not the latency of the current application. P3/P6 acceptance remains open.

Validation: **548 native workspace tests passed**, with 11 opt-in/ignored tests;
the maintenance profile was run separately. Release workspace/all-target Clippy
passed with warnings denied. The Whisper-enabled Windows release DLL was rebuilt,
staged, and its Kotlin bindings generated from that binary; the public bindings
are unchanged. **631 desktop tests passed**, with the opt-in benchmark skipped.
Windows DLL SHA-256: `B2A218E39F2A6D6BFA6FCC9A7AE39A87B5F46091B13638CF65B3FEAEB5B093EF`.
Both Whisper-enabled Android release libraries were rebuilt/staged (arm64-v8a
8.66 MiB; x86_64 8.81 MiB). Bindings generated independently from both binaries
match. Android app and test APK builds passed; device tests were not run.

## 28. Expanded DSL and storage gate (2026-09-06)

At the owner's request, [53 actual DSL filters](../research/dsl-matrix-2026-09-06.md)
were compiled with the real plugin registrations and measured against the old
executor. The compact-facts diagnostic adapter covers 38; the 15 remaining cases
are explicitly marked as text/root/grouping or fixture-coverage gaps. Every
supported query checks old count/first-page identity and complete new ordered
enumeration. Selected deep pages are measured independently. Source reads remain
zero during queries. All 320,000 old/current facts match exactly, including duplicate
counts, typed values and source qualifications.

The old executor's negation paths are a priority: `-is:done` takes 15.7 seconds,
and `-due:week` takes 54.7 seconds. Statistics updates do not remove the problem.
The compact prototype's corresponding page projections take about 1 ms, with
counts separate. Missing-date sorting still takes 112 ms, and empty intersections
of common predicates take about 44–45 ms. Broad-count timing depends on connection/
cache state: a fresh-built connection takes 255–270 ms, but a reopened or
cache-reconfigured connection takes about 19–22 ms with the same plan/VM work.
This behavior must be explained before establishing a stable count budget.

Whole-index storage is 131.22 MiB for schema 5 and 152.89 MiB for transitional
schema 6 as built. A full compact-fact storage-only experiment retains all data
and indexes needed for typed/folded lookups, provenance, text and FTS; after
compaction it is 55.15 MiB versus the old index's 125.01 MiB. It does not yet
implement maintenance or public query consumers, and this corpus has no stale/
unbound legacy facts. Do not present that experiment as the shipped index size.

- [x] Run a broad DSL matrix, exact result checks, pagination probes, statistics
  controls, full-data disk comparison and connection/cache reproduction.
- [ ] Replace materialized tuple-membership/negation through the shared executor;
  preserve negated conjunction and missing-value semantics.
- [ ] Resolve empty sort phases, common-but-disjoint predicates and broad-count
  cache behavior, with regression fixtures and representative memory budgets.
- [ ] Cover text/path/root-scope and grouped searches in the new executor.
- [ ] Measure mixed plugins, numeric/multi-valued predicates, different document
  shapes, live maintenance/lag, OS-cold reads and Android before accepting P3/P6.

These additions are diagnostics only. No production executor or plugin contract
changed, and the performance gate remains open.
The DSL export/rebuild, full matrix, control experiments and complete fact-identity
check passed. Three prototype conformance tests and release kernel test-target
Clippy also passed. The statistics control records its one timed-out query explicitly.

## 29. Document-shape and correctness validation (2026-09-06)

The [follow-up validation](../research/search-validation-2026-09-06.md) measures
the same 10,000 real plugin tasks across 1,000, 100, 10 and one source document.
Both orderings of the Alice/open/month DSL query are checked at page sizes 50 and
500, including counts and complete ordered key enumeration. The existing executor
uses schema 6 in this comparison; compact selection is still a diagnostic prototype.

First-50 due-ordered selection takes 0.90–1.36 ms across the four layouts, but
existing display projection reaches 125 ms in the 10,000-task document. Single-task
edit/reindexing rises from about 96 ms at 100 tasks per document to 8.1 seconds
at 10,000. Callback instrumentation attributes about 2.03 seconds of one 8.40-second
edit to People and 42 ms to Tasks; the remaining 6.33 seconds needs native profiling.
Reconcile already maintains the index, so a second settled advance is negligible.

- [x] Compare document shapes and page sizes using actual plugin facts and DSL
  requests; check ordered identities and counts, with no source-file reads.
- [x] Measure whole reconcile/edit cost and separate plugin callback bodies from
  remaining work. Record the limitation instead of extrapolating metadata microbenchmarks.
- [x] Exhaust compact scalar multi-value/negated-conjunction semantics over 128
  fact combinations and 98 constraint pairs, including complete keyset traversal.
- [x] Test typed numeric and correlated-record correctness in the unrelated
  structured-record fixture; test snapshot consistency, cursor expiry and interruption.
- [x] Exercise production target/source/fact bindings with a concurrent read
  snapshot, independent producer failure and subsequent recovery.
- [ ] Bound display projection by selected targets and preserve complete result
  semantics; remove full-source map/fact decoding from the bounded query path.
- [ ] Profile and reduce complete large-document edit/reindex costs, including
  callbacks, FFI, validation, fact/index writes and commits. Compare compact-only
  replacement costs after production maintenance is integrated.
- [ ] Measure numeric DSL performance, OS-cold reads and Android runtime; complete
  the remaining public executor/query-shape and lifecycle gates in sections 27–28.

No production executor, plugin contract or domain dependency changed in this
validation batch. Typed-record conformance does not imply the scalar DSL diagnostic
adapter supports numeric queries. P3/P6 acceptance remains open.

Validation: **553 native tests passed** (17 ignored/opt-in), **631 desktop tests
passed** (4 opt-in methods skipped), and release workspace/all-target Clippy passed
with warnings denied. Both new opt-in Kotlin profiling methods and both 16-case
native shape runs passed separately. The second query run reports faster warm
timings; both runs are preserved in the report rather than selecting one baseline.
Android app/test APK build tasks succeeded; no Android device was attached for
runtime tests. Production binaries and generated bindings were unchanged.

A subsequent isolated full compact-fact/target replacement test for the same
10,000-task document measures **622 ms median (605–709 ms)** across seven samples.
It replaces all 63,505 facts and 20,503 targets, including four fact indexes,
source-map write, foreign keys and durable commit. Parsing, plugin callbacks,
FFI, semantic validation, text/FTS and producer receipts are excluded. This is
component evidence, not a complete new reconcile time or a direct speedup against
the 8–9.5-second production result. All row/target integrity checks and focused
release Clippy passed; full production maintenance integration remains open.

## 30. Complete update profile and production migration priority (2026-09-06)

The [complete update-path profile](../research/update-path-profile-2026-09-06.md)
supports starting integration of the common compact writer and bounded executor.
The median large-source baseline is 8.225 seconds: 4.120 seconds inserting facts,
0.774 seconds inserting text/FTS, 0.492 seconds committing, and 2.035 seconds in
People's callback. Shared source parsing takes 4.6 ms and pre-index filesystem
reconciliation 12.4 ms. Native spans are opt-in and contain no source content.

An isolated write comparison retains all existing wide/compact indexes, text/FTS
maintenance and commits. Resolving predicate/target IDs once per batch instead of
through per-fact triggers reduces total storage work from **6.024 to 2.288 seconds**.
This is evidence for the new writer, not a production route or a complete-edit time.

People now shares a bounded per-call cache of the existing native Unicode
normalizer across recognition and fact construction. Callback time falls to
**18–26 ms**, and the actual complete update falls to **6.202 seconds median**.
The cache has no persisted identity meaning and does not change encoded facts.
The kernel and Tasks gain no People-specific concepts.

- [x] Attribute complete source read/parse, callback/transfer, validation,
  per-namespace deletes/inserts and durable commit costs on the real plugin path.
- [x] Compare current writes with batch-resolved IDs on disposable full-index
  copies, preserving all fact values, typed data, duplicates and current bindings.
- [x] Remove repeated native normalization calls in People; verify Unicode,
  more-than-cache-capacity inputs, repeat calls and all 63,505 fixture facts.
- [x] Integrate the generic batch writer with existing source/version
  validation, owned predicates, producer receipts, failures/stale evidence,
  rollback/retry and index-generation contracts. Resolve targets once per distinct
  referenced location within each producer transaction; keep plugin callbacks
  outside write transactions. See section 31 for the transaction boundary.
- [x] Route existing public queries through the same compact target/fact model
  and bounded selection/projection. Complete remaining query-shape and count/cursor
  gates from sections 27–29, then remove redundant wide storage/index work.
- [x] Measure integrated complete update/query latency and remaining text/FTS
  replacement costs. Do not infer a final latency by adding isolated experiments.

Further isolated model comparisons are lower priority than these integration
steps. This is not approval to bypass the existing equivalence/lifecycle gates or
introduce domain-specific executors. P3/P6 acceptance remains open.

Validation: **553 native and 632 desktop tests passed**; normal and profiling
builds passed release Clippy; Android app/test APK builds passed. New profile/control
fixtures ran separately, all 63,505 facts retained their values/locations, and
current source bindings passed integrity checks. Normal Windows bindings regenerated
identically. Android device/runtime tests remain unavailable. Packaged native
resources and public FFI signatures were unchanged.

## 31. Production batch fact writer (2026-09-06)

The [integrated writer report](../research/batch-writer-2026-09-06.md) records the
complete application update falling from **6.202 to 2.595 seconds median** for a
10,000-task document. Fact insertion falls from **4.107 to 0.598 seconds** while
all wide/compact indexes, text/FTS, producer receipts and durable commits remain.
The 100- and 1,000-task document medians are **67 ms** and **290 ms**, respectively.
These are three-sample Windows measurements; Android runtime and OS-cold costs
remain unmeasured. This is full document replacement, not task-level editing.

- [x] Route every production fact insertion, including legacy callback output,
  through the shared transaction-scoped writer; preserve namespace ownership,
  exact source/location binding, typed values, duplicates and empty receipts.
- [x] Remove the redundant insert trigger. Keeping it with a skipped body still
  incurred substantial measured overhead; four isolated SQL controls identify
  that difference. Preserve source/identity lifecycle triggers and frozen old
  insertion SQL in comparison fixtures.
- [x] Compare writer/old-trigger semantics; test large batches, replacement of
  targets between transactions, stale/unversioned output and partial-write rollback
  of facts, dictionary entries and receipts. Exercise normal older-index rebuild.
- [x] Measure complete real-plugin edits after integration and verify all 63,505
  facts against preserved earlier data with no invalid current bindings.
- [x] Completed in section 33: integrate bounded selection and projection through the existing
  public query routes, including remaining text/root/grouped cases, negative-only
  queries, exact counts, continuation and cancellation. Then remove redundant
  wide representation/index work and remeasure query, update and storage costs.

Schema 7 changes disposable index maintenance only. No People/Tasks dependency,
domain-specific kernel logic, plugin callback or public FFI signature is added.
Bindings are shared within each prepared producer batch, never cached across its
commit: the existing independent producer transactions can observe different
source states. Combining those transactions would require a separate coherent
commit design and is not assumed by this optimization. P3/P6 remains open.

Validation: **556 native and 632 desktop tests passed**; normal and profiling
release Clippy passed. The four SQL controls passed all 16 trials separately;
complete profiling and fact/binding equivalence also passed. Windows and both
Android kernels were rebuilt/staged with transcription, with identical bindings
generated independently from each target. Android app/test APK builds passed;
device runtime checks remain unavailable. The tested Windows DLL matches the
packaged resource. Public FFI signatures remain unchanged.

## 32. Production fact-query execution and bounded projection (2026-09-06)

The [target executor report](../research/target-executor-2026-09-06.md) records
the next production integration step. **43 of 53 exported DSL cases** now use
the generic target executor; all 53 return exactly the same complete first pages
as the old executor. Text/relevance requests retain their existing route.

- [x] Execute fact-only item queries over source-qualified targets, including
  whole-clause negation, exact/root/inherited scope, membership, numeric/typed
  comparisons, independent owners and capped sparse candidate discovery.
- [x] Select pages in SQL, preserving path/key continuations, multi-valued minimum
  sort semantics, duplicate elimination, missing-last order and grouping.
- [x] Reconstruct only returned item ranges/facts and their overlapping indexed
  lines. A two-item page from 3,000 items reconstructs two items/two lines, without
  reading source files or whole source-map/body payloads.
- [x] Compare complete ordered traversal on conformance fixtures and all 53
  actual DSL first pages. Run the real application API at 10,000 and 50,000 tasks
  and fully enumerate 17,500 Alice matches in default and due-date order.
- [x] Measure added storage and complete update cost. Two projection indexes add
  **2.93 MiB (2.0%)** to the compacted 50,000-task fixture. Controlled large-document
  edits take **2.724 s median**, compared with the earlier 2.595 s writer run;
  initial slower samples and run/build limitations remain visible in the report.
- [x] Completed in section 33: separate exact count state from bounded page delivery, with
  generation-bound results, independent read execution, cancellation and UI state.
  The compatibility API retains synchronous counts; the new handle runs independently.
- [x] Extend target execution to text/relevance and residual requests and remaining
  document/line/location/lookup consumers. Preserve their complete semantics.
- [x] Optimize remaining sorted/grouped selection and count behavior, then remove
  redundant wide storage/index work once all consumers and lifecycle gates pass.

Through the application API, Alice's open tasks due in September return the first
50 of 17,500 matches in **149 ms median** at 50,000 tasks. The unsorted native
selection itself is below 1 ms and projection about 3 ms; exact counting dominates
the remaining latency. Broad native negative filters improve from seconds to
about 85–86 ms. These are measured Windows results, not Android runtime budgets.

Schema 8 adds private projection indexes and a query execution route. Kernel
semantics remain generic; neither People nor Tasks gains a dependency on the other.
No plugin callback or FFI shape changes. P3/P6 acceptance remains open.

Validation: **560 native and 632 desktop tests passed**, release Clippy passed,
all matrix/application/update checks passed, and all 63,505 update-fixture facts
remain equivalent with valid bindings. Windows and both Android kernels are
rebuilt/staged; per-target bindings are unchanged. Android app/test APK builds
passed; no attached device was available for runtime checks.

## 33. Complete compact storage and query migration (2026-09-06)

The [migration report](../research/compact-migration-2026-09-06.md) closes the
production integration work from sections 30–32. Schema 9 has one physical fact
store: values reference interned locations and owned predicates, with compact
target IDs for execution. The wide table/indexes are removed. A compatibility
view preserves anchor consumers without retaining duplicate physical storage.

- [x] Migrate all item query shapes, including text/relevance and pure residual
  callbacks, to compact targets. Preserve own-item boundaries, negation, ranking,
  grouping, typed values and full ordered traversal. Use bounded indexed line
  windows and conservative document prefilters for text matching.
- [x] Integrate document/line/location/lookup consumers with the same physical
  store. Select eligible document/line pages in SQL before snippet projection;
  retain legacy scope and relevance semantics where required.
- [x] Add independent read-only query handles for pages and generation-bound
  counts, cancellation and expiry. Both shells publish pending totals honestly
  and cancel replaced requests. Keep existing exact-total APIs compatible.
- [x] Write compact facts directly in producer transactions, collect unused
  locations, preserve stale evidence/receipts/rollback, and rebuild old schemas.
  Verify all 320,000 logical facts during storage conversion and all 63,505 facts
  after complete application edit/restore trials, including current bindings.
- [x] Validate all 53 exported DSL cases against the old executor, source and
  plugin lifecycle conformance, concurrent writer/readers, count-generation and
  cancellation behavior. Repeat application queries at 10,000 and 50,000 tasks.
- [x] Measure complete updates and compacted storage; record regressions and
  variable samples alongside improvements rather than declaring a latency budget.
- [x] Rebuild/stage Windows and both Android kernels with Whisper; generate
  matching bindings independently from each binary and exercise the final desktop
  DLL. Update search/indexer/lookup contracts and ADR 0025.

At 50,000 tasks, Alice's open tasks due in September return the first 50 in
**11.3 ms**, with the exact count taking **132.2 ms separately**. Complete
17,500-result enumeration falls from **6.52 to 1.67 seconds**, or **9.25 to
3.62 seconds** ordered by due date. Compacted full-index storage falls from
**148.84 to 73.41 MiB (50.7%)**. These separate runs and the matrix outliers
are retained in the report; this is not a universal constant-time guarantee.

Complete updates remain full-document replacements. Editing one task in a
10,000-task document now takes **3.097 seconds median**, versus **2.724 seconds**
in the preceding schema-8 run. This regression remains explicit; the migration
does not claim improved write latency or task-level incremental maintenance.

Validation: **565 native tests passed** (21 ignored/opt-in), using a passing
serial rerun of the full sync suite after a listener-port race in the workspace
run. **633 desktop tests passed** (five opt-in methods skipped), release
workspace/all-target Clippy passed, and Android app/test APK builds passed.
Application and update benchmarks passed separately. No Android device was
attached, so runtime validation remains unavailable. The migration is complete;
broader People/refactoring P3/P6 acceptance remains open.

## 34. Incremental text storage as the first differential-update step (2026-09-06)

The owner requested a start on incremental database maintenance. The
[implementation report](../research/incremental-text-maintenance-2026-09-06.md)
records the first production change: compare the new text rows with the stored
document instead of replacing every text/FTS entry. Plugin recognition, source
target replacement and producer fact replacement remain complete-document work.

- [x] Retain unchanged text entries and update only coordinates for identical
  moved text. Preserve duplicate counts, field boundaries, Unicode/CRLF, FTS
  integrity, transactional rollback and stale-source binding safeguards.
- [x] Use snapshots supplied by ordinary reconciliation, including external
  edits, without depending on a shell edit stream or adding domain identities.
- [x] Compare against full replacement for single-line and prefix-insertion
  edits at 100, 1,000 and 10,000 items, alternating execution order across five
  trials. Verify complete indexed text and FTS equivalence after each edit.
- [x] Run complete application edit/restore trials with both plugins and audit
  all 63,505 restored facts and their current bindings. Rebuild/stage all shipped
  native kernels and verify unchanged per-target public bindings.
- [x] Design and implement differential fact/target maintenance (section 35) with
  explicit handling of source revisions, shifted locations, independent producer
  commits, lagging producers and rollback. Reusing text rows does not establish
  permission to rebind old plugin evidence to a new snapshot.

For 10,000 items, isolated parsing/source-target/text maintenance falls from
**1.018 seconds to 202 ms** for one changed line, and **1.057 seconds to 236 ms**
for a new prefix paragraph. The complete real-plugin edit falls from **3.097 to
2.100 seconds median (32.2%)**. Full plugin facts still regenerate; this is not
completion of task-level incremental indexing. All samples and comparison limits
are retained in the report.

Validation: **570 native tests passed** (22 ignored/opt-in), **633 desktop tests
passed** (five opt-in methods skipped), release Clippy passed, and both Android
APK builds passed. The manual benchmark passed separately. The final data audit
found no fact differences, invalid/unbound targets or unused locations. Android
device/runtime validation remains unavailable. Schema 9 and public APIs remain
unchanged, and People and Tasks retain independent implementations.

## 35. Incremental fact and target maintenance (2026-09-06)

The [implementation and measurement report](../research/incremental-fact-target-maintenance-2026-09-06.md)
records the next production phase. Schema 10 retains matching target coordinate
rows and compares each producer's complete ordered facts against stored rows.
Changed values update in place; additions/removals change row counts. Revision
changes still detach old evidence and advance retained facts' bindings. People
and Tasks remain independent, and query SQL and public FFI contracts are unchanged.

- [x] Retain unchanged targets and fact values without allowing old producer
  evidence to bind to a new source revision. Preserve ordered duplicates,
  rollback, independent receipts, stale evidence and WAL reader snapshots.
- [x] Compare structural, Unicode/CRLF, reordered, resized and empty outputs
  with full replacement; exercise schema 5–9 rebuilds and failed producers.
- [x] Measure complete application edits against a fresh previous-DLL baseline,
  profile the remaining costs and audit all 63,505 restored facts and bindings.
- [x] Rebuild and stage shipped kernels, verify per-target generated bindings,
  and run native, desktop, Clippy and Android build validation.
- [x] Investigate reducing revision-binding writes and commit overhead while
  preserving exact source provenance and independent producer failure behavior.
  Establish correctness and read/write performance evidence before changing
  publication or provenance contracts.

The 10,000-task edit improves from **1.733 to 1.652 seconds median (4.7%)** in
fresh comparison runs. The earlier baseline measured 2.100 seconds elsewhere;
it is not used to inflate this improvement. Instrumented medians attribute about
452 ms to source/target maintenance, 747 ms to fact comparison/writes and 291 ms
to durable commits. This does not yet make updates proportional to changed tasks.

Validation: **574 native tests passed** (22 ignored/opt-in), **633 desktop tests
passed** (five opt-in methods skipped), release Clippy and Android APK builds
passed. A sync timing failure during a concurrent build was followed by a passing
full isolated sync suite and a 4.165-second isolated timing repeat. The final
fact audit found no differences or invalid bindings. No Android device was
attached. Broader People/refactoring P3/P6 acceptance remains open.

## 36. Local plugin inputs and coherent publication (2026-09-06)

The owner asked to carry changed input through processing instead of treating
each edit as a complete producer replacement. The
[implementation report](../research/local-input-publication-2026-09-06.md)
records the local-input contract and schema-11 publication work.

- [x] Add an opt-in, domain-independent local scoped-indexer contract. Compare
  source inputs and parser eligibility, reuse successful local outputs, and
  remap shifted locations. Retain the full-input contract for other producers.
- [x] Exercise the contract independently in People and Tasks, through the SDK
  and FFI. Test full/local equivalence, metadata, fences, nesting, Unicode,
  insertions, deletion and failed callbacks.
- [x] Prepare aligned one-path outputs before the write transaction; validate
  cursor/version state again before publishing. Preserve missing/failed owners'
  old source evidence, rollback, receipts and independent reader snapshots.
- [x] Reuse coordinate slots and compare facts within each reused location, so
  inserting or removing one item's facts does not shift all later comparisons.
  Replace pathological tuple membership in target removal.
- [x] Measure complete 10,000-task edits, including cold registrations and
  length-changing edits. Record the remaining absolute-coordinate and provenance
  writes rather than declaring fully local storage or a completed latency budget.
- [x] Investigate block-relative positions and coarser revision-validity records
  so edits do not rewrite absolute coordinates or source versions for every later
  location. Preserve source-qualified query results and measure read-path costs.

Warm complete edits measure about 380 ms for a same-length value change and
680–770 ms for longer lines, insertion or deletion. Cold samples remain about
0.8–1.2 seconds. Full Markdown context parsing and assembled-fact comparison
remain; opted-in plugin recognition is local. The cache is disposable and bounded.
Broader People/refactoring acceptance remains open.

## 37. Position storage investigation (2026-09-06)

The owner asked to remove coordinate-moving costs while preserving query speed.
The [experiment and delivery design](../research/revision-position-storage-2026-09-06.md)
separate compact fact selection from revision-qualified position data. This is a
test-only position-block experiment. The subsequent
[shared-frame delivery](../research/shared-revision-frames-2026-09-07.md) moves
revision validity into production schema 12. The
[position-block delivery](../research/position-block-delivery-2026-09-07.md)
completes geometry/order migration in schema 13.

- [x] Audit uses of target IDs, order keys, byte ranges, line numbers and source
  revisions. Keep plugin facts and the compact filtering indexes independent of
  the position representation.
- [x] Prototype bounded position blocks, direct item-slot projection, indexed
  snippet reconstruction and sequential text reads. Compare actual exported DSL
  results, counts and cursors against the current executor.
- [x] Measure coordinate publication for a longer first line in a 10,000-task
  document, including encoding and committed SQLite writes. Check all resulting
  item ranges against the new parsed snapshot.
- [x] Deliver private slot allocation and order maintenance for insertions,
  deletions and reordering; preserve generation-qualified pagination.
- [x] Normalise source frames and integrate real producer failure, stale evidence,
  coherent publication, rollback and garbage collection. Keep the conservative
  replacement path when all affected evidence cannot advance together.
- [x] Migrate all public item/root/line and legacy location readers, plus indexed
  prose coordinates. Freeze the checked encoding and schema rebuild fixtures.
- [x] Repeat the query matrix and end-to-end update tests on the actual migrated
  schema, with the old coordinate representation removed. Preserve query latency
  and publish the remaining complete-fact-processing cost separately.

The experiment demonstrates a way to replace thousands of indexed coordinate
mutations with compact geometry writes. It does not make the whole edit pipeline
local or establish a final end-to-end latency target.

## 38. Relative position storage delivered (2026-09-07)

The [delivery report](../research/position-block-delivery-2026-09-07.md) records
production schema 13, using relational blocks and private sparse order labels.
Current fact/text coordinates follow retained slots; stale producer evidence
retains its original source coordinates. Position changes no longer rewrite
every following target, fact location and text row. Queries resolve only the
returned geometry and preserve indexed selection/pagination.

All 118 old/new query comparisons preserve results. The Alice/open/due-month
query over 50,000 tasks improves from 5.110 to 4.081 ms for the first 50; the
first 500 improves from 28.858 to 18.520 ms. Vacuumed storage grows 3.9–6.5%
against shared-frame schema 12. Complete 10,000-task edits improve from
621 to 449 ms for a longer line, 720 to 506 ms for a prefix, and 703 to 441 ms
for deletion. Normal same-length edits improve from 360 to 310 ms. These are
fresh measured comparisons, not a completed latency target.

Whole-document geometry/fact comparison and adapter costs remain, measured
separately in the report. A future phase can narrow those inputs further; this
delivery does not pretend that replacing coordinate writes removes all work
proportional to document size. Broader People/refactoring acceptance remains open.


## 39. People editor and shared refactoring delivery (2026-09-07)

P0–P5 now have implementations and executable acceptance coverage. The new SDK
seams are editor completions, document views and refactoring providers. People
owns its syntax, definitions, reference facts and rename/merge proposals; it does
not import Tasks. Tasks independently contributes its existing date insertion.
The unrelated label fixture uses the same refactoring API without either plugin.

Delivered interactions: @ lookup; connection selection followed by person lookup;
explicit create-and-insert; toolbar insertion; reference navigation and indexed
backlink sections; keyword rename/merge and person-handle rename; paged immutable
previews with folder/file selection; progress, cancellation/resume, new previews
for conflicts/late occurrences; generic guarded undo and retained-copy restoration.
The shared page form publishes asynchronously loaded defaults on the UI thread;
Android testing caught and now covers its previously disabled Create state.

The public contracts are [editor contributions](../../contracts/editor-contributions/README.md),
[source documents v0.3](../../contracts/documents/README.md), and
[refactoring](../../contracts/refactoring/README.md). [ADR 0026](../decisions/0026-shared-editor-and-document-refactoring.md)
records ownership and tradeoffs. Durable schema 9 adds local journals; derived
index schema 13 and the query executor do not change. Platform bindings are
produced from the corresponding native binary.

Explicit release limits:

- Unmanaged Markdown participates without adoption. It has local journal/change
  receipts, not managed revision-sync identity; existing file transfer remains
  responsible for those files. Managed edits use ordinary sync operations.
- Definition changes apply after all other reviewed documents. Partial/new
  spellings remain visible and can be unresolved until completion. Exclusions
  leave pending work; a new folder scope defines a new completion claim.
- Journal/undo evidence and displaced inodes remain local until local application
  data is removed. They consume full-document storage and are not automatically
  expired. Retained bytes can be inspected and restored through a new preview.
- An arbitrary editor ignoring locks cannot be serialised. A late write through
  a displaced handle is preserved in its retained inode, not silently merged.
  Guarded undo can conservatively conflict inside a large changed region.
- Source navigation opens the page. Exact caret navigation and persistent inline
  item identity are not promised. Clean editor reloads retain their existing
  undo-stack reset behaviour; bulk undo is durable in the refactoring history.

Final local release evidence:

| Check | Result |
| --- | --- |
| Rust workspace | 605 passed, 24 ignored, zero failures |
| Clippy, all workspace targets, warnings denied | Passed |
| Full desktop suite against release kernel | 659 tests: 654 passed, 5 skipped, zero failures |
| Final packaged-kernel desktop refactoring/UI regression run | 13 passed |
| Final full-source editor-context regression run | 19 desktop and 17 Android checks passed, zero failures |
| Full Android API 36 x86_64 emulator suite | XML reports 892 tests: 877 passed, 15 skipped, zero failures |
| Final native refactoring suite | 11 passed; separate ignored child harness is executed by the five-window crash test |
| Android native packaging | arm64-v8a and x86_64 rebuilt; bindings generated from the corresponding Android binary |
| Dependency and whitespace checks | Passed; no People → Tasks or SDK → app dependency |

Android skips concern optional voice models/real-engine probes and pre-existing
IME spike cases. No physical-device, release-signing or app-store acceptance is
claimed. The first broad run caught the obsolete mobile suggestion-widget test
selector; it now exercises the shared menu and the final full run is green.

The final editor review also corrected both shells to pass full source, including
frontmatter, to completion providers and translate insertion offsets back into
the visible body. Real-editor regressions cover a body beginning with a Markdown
rule, exact frontmatter preservation, People insertion and Tasks date completion.
Completion acceptance and create-and-insert return explicitly to the UI thread
after native lookup validation; the combined Android run caught and now covers
the otherwise unobserved programmatic edit that left autosave unscheduled.

The final 10,001-reference fixture changes all references plus the defining page,
pages every resulting location, and confirms identical facts after rebuilding:

| Platform/run | Discovery + preview | Apply | Sampled used-heap peak |
| --- | --- | --- | --- |
| Windows release kernel / final focused run | 5,071 ms | 2,829 ms | 138 MiB |
| Android API 36 emulator / final full run | 6,532 ms | 2,935 ms | 47 MiB |

Heap is sampled every 10 ms across fixture preparation/application and includes
runtime/plugin allocations; native allocations and OS working-set peaks are not
measured by this probe. These are loaded developer-machine samples, not latency
SLAs. Earlier isolated Windows samples were 3,503/1,862 ms. The enduring regression
checks are complete enumeration/rebuild, bounded entry pages and no source-file
reads from lookup/query execution. Existing large-vault lookup, membership and
mixed-plugin query baselines in sections 22–38 remain applicable because this
delivery leaves their executor and index schema unchanged.

Conformance also covers CRLF/BOM/Unicode preservation, ambiguous destinations
introduced after preview, dirty buffers, user-selected exclusions, late old-name
occurrences, merge/retirement, removed-provider undo, retained-copy restoration,
ordinary offline/out-of-order sync, moved-parent recovery and prevention of an
inverse property edit binding to matching body prose.

Sections 7 and 39 are the current completion record. Earlier dated sections retain
historical investigation checklists; their unchecked entries describe the state
at that checkpoint. The [People user guide](../../docs/guide/people.html) explains
creation, connections, queries, rename previews and recovery.
