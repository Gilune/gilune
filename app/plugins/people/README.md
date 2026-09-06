# People plugin: page and catalogue foundation

The module depends only on the plugin SDK. It has no imports, identifiers or
predicates belonging to Tasks, Projects or Decisions. The host supplies generic
collections/forms; the plugin supplies page definitions, pure source producers,
row presentation and a pure source-aware indexer.

`People/<normalized-handle>.md` contains editable `name` and `handle` properties,
visible kernel identity metadata and a freeform body initially headed About.
The handle keeps its authored spelling; changing a file path preserves managed
object identity through normal reconciliation. The name property is canonical;
the body does not contain a second generated person name.

`People/Connections/<normalized-keyword>.md` contains `keyword`, `label` and boolean
`retired` properties plus a freeform Notes body. On first setup, the shared page
initializer creates Assign and Owner as ordinary portable pages, with a receipt
under `Settings/Initialization/people-catalogue.md`. Existing catalogues remain as
authored; restart, re-enabling, renaming, retirement or deletion do not top them up.
Retired
entries stay in the directory. Users can create additional keywords and inspect
or edit the exported Markdown in other editors. Keyword renames/merges must wait
for the shared refactoring workflow; editing the property alone does not update
references elsewhere.

## Current facts

Definition facts are text values at the document root under `org.memory.people`:

| Predicate suffix | Value |
| --- | --- |
| `person.kind` | `person` for a declared person page |
| `person.handle` | normalized valid handle |
| `person.handle-display` | exact valid authored handle |
| `person.name` | authored nonblank string name |
| `person.name-key` | normalized name for later lookup |
| `definition.kind` | `connection` for a declared connection page |
| `definition.keyword` | normalized valid keyword |
| `definition.label` | authored nonblank display label |
| `definition.retired` | `true` or `false` from a YAML boolean |
| `definition.problem` | diagnostic when required properties are invalid/missing |

Kinds remain indexed for recognised but incomplete pages so users can find and
repair them. Invalid fields emit no guessed value. YAML type/id text is an authored
claim, not proof of a managed object; editing authority is checked by shared
snapshot/creation APIs. Copied pages and colliding handles remain separate results.
Unparseable YAML cannot produce these facts and is handled by the existing source
reconciliation/diagnostic paths. `type:person` and `type:person-connection` compile
to root-kind equality and require this namespace. Scoped indexer version 3 also
recognises mentions and labelled connections. Ranked directory lookup and search
operators are independently contributed through the SDK registry. Editor lookup
triggers and backlinks remain open.

| Reference predicate suffix | Value |
| --- | --- |
| `reference` | normalized handle, including labelled connections |
| `mention` | normalized handle from a plain mention only |
| `connection` | compact canonical JSON `[keyword,handle]` pair |
| `connection.keyword` | normalized keyword |
| `connection.person` | normalized handle from a labelled connection |
| `reference.problem` | invalid structured connection data, at document root |

`recognisePeople` retains full-source byte ranges for handle/keyword tokens and
uses shared Markdown text/item maps. Inline references attach to the nearest
item or their physical body line. Continuations belong to that item; parent,
nested and sibling item facts remain separate. Escapes, code, HTML, URLs, emails
and link destinations are excluded; ordinary link labels remain eligible.
Labels require a contiguous keyword and colon, followed by optional spaces/tabs
and a reference on the same physical line. A trailing sentence dot is excluded
from the handle. `owner: @alice, @bob` labels Alice only; Bob is a plain mention.

Page-root connections use a `connections` YAML mapping whose values are `@handle`
strings or sequences of those strings. People interprets the shared value tree,
including aliases. Invalid entries emit a visible diagnostic fact while valid
entries remain queryable. All syntactically valid keywords and handles emit facts,
including unconfigured/retired keywords and unresolved people. Catalogue changes
do not rewrite lexical meaning. Duplicate facts at one location collapse.

The generic kernel `queryLocations` API answers exact pairs/membership with
source-qualified results. Application search and embedded filters also support:

| Operator | Meaning |
| --- | --- |
| `people.ref:alice` | Any reference to Alice |
| `people.mention:alice` | Plain mention of Alice |
| `people.connected:alice` | Any labelled connection to Alice |
| `people.connection:owner:@alice` | Exact keyword/person pair |
| `people.connection:owner IN ["alice", "bob"]` | Either person, same keyword |
| `people.page-connection:owner:@alice` | Explicit containing-page connection |

Handle arguments accept an optional `@`; `NOT IN` uses the same typed set binding.
`people.connection:owner` matches the keyword across people. Local predicates
compose with any independently registered result type; document queries match
contained occurrences, item queries require the same item. People registers no
target-domain names or dependencies. The shared composer and kernel perform the join.

## Identifier grammar and equivalence

Handles and keywords use one validator: 1–120 UTF-16 code units, no leading or
trailing dot, ASCII controls/space or the explicit whitespace/control ranges in
`validToken`, and no reserved Markdown/path punctuation listed there. Other Unicode
(including emoji and combining marks) is allowed. Creation accepts a convenience
leading `@` for handles or trailing `:` for keywords, but stores neither.

Equivalence is the shared pinned NFC/full non-Turkic case-fold/NFC function, with
Unicode 9.0 case and Unicode 16.0 NFC tables. Changing these tables requires an
indexer version change and collision review. Case-equivalent handles in separate
folders are diagnosed by indexed count, never silently bound to one page. A create
form checks existing counts, but cannot prevent offline/concurrent duplicates.

Directory creation is implemented on desktop and Android. Both editors use the
shared source-preserving scalar property form. Each collection offers Page defaults:
initially save a folder and Markdown template, then edit the resulting ordinary
document under `Settings/Page defaults`. People declares companion types for person
and connection defaults; the framework resolves and versions them. Its source
producers consume the effective template and apply only their form-owned properties,
preserving other properties and body. Changes affect future pages, including
initial catalogue pages when initialization has not yet run. Reference resolution,
editor lookup triggers and refactoring remain open.

The directory's Find field uses the shared indexed lookup provider. People
normalises the fragment with the same rules as its facts and declares exact-handle,
handle-prefix and name-prefix alternatives, in that priority order. Ties use
normalised name, handle and source path. The kernel supplies complete pages and
counts. Duplicate handles retain separate source rows; incomplete definitions
remain visible and missing handles have no insertion text. Typing does not create
a page. The provider also returns exact `@handle ` insertion text for the future
editor consumer; `@`/`/` editor menus and create-from-lookup are not shipped yet.
