# Indexer contract (org:memory, v0.1)

The third seam between the kernel and a plugin, beside the processor
contract and the sync transport: **a pure function from a note's text to
facts about anchors** (ADR 0020 § 2.3).

A processor interprets an object and writes an interpretation. An indexer
says nothing durable at all — it describes what is already in the vault,
and the kernel keeps the description up to date and throws it away when
the plugin goes.

```
index(rel_path, text) -> [fact]
fact   { line: option<u32>, predicate: string, value: string }
anchor = (rel_path, line)          // line absent = the note itself
```

## What the kernel promises

- **The text is the note's body**, front matter split off, and a fact's
  `line` is **1-based within that body** — the same line number the
  shells' search surfaces already carry, so an anchor addresses the
  buffer a person sees. `None` anchors a fact to the note, and a fact
  about a note satisfies every line of it.
- **Called once per changed note per pass**, in the pass that recorded
  the change, from the kernel's change feed (ADR 0020 § 2.1). Never on a
  note the feed did not name; never on the whole vault except a rebuild.
- **Predicates are opaque.** The kernel stores, orders and joins them and
  interprets none of them, exactly as it interprets no event type. No
  domain word appears in kernel code.
- **Facts are replaced whole per note**, so an indexer is a function and
  not an accumulator: line numbers shifting on an edit is not a problem
  it has to solve.
- **The namespace is the plugin's.** It is the plugin id or a name under
  it, and removing the plugin drops it (ADR 0011's removal rule). The
  notes are untouched.

## What an implementation promises

- **Purity and totality.** Same text, same facts. No vault access is
  offered and none is needed.
- **A declared kind per predicate** — text, number or date — at
  registration, so ranges over its values mean something. A value that
  will not take its kind is kept as it came and left out of ordered
  comparisons.
- **A version.** Change what the indexer means by its facts and change
  the version; that namespace, and only that namespace, is derived again
  from the beginning of the feed.
- **Failing is allowed.** An implementation that refuses a note stops its
  own namespace at that note — the pass completes, the refusal is on the
  record, every other namespace carries on — so refusing is a state, not
  a crash.

## Profiles

- **Bundled** — a Rust implementation of
  `gilune_kernel::index::NoteIndexer`.
- **Over the FFI** — `NoteIndexer` as a uniffi foreign trait, implemented
  in Kotlin by the shells, registered per handle and per process with
  `register_indexer` (the `KeyWrap` precedent, ADR 0019 § 4; the
  per-process rule is the external processor's, ADR 0018 § 2). It returns
  a result rather than a bare list, because a foreign implementation can
  throw and a panic must not cross the boundary.
- **Sandboxed** — `index-note(text) -> list<fact>` in the WIT processor
  world. **Deferred**: the WIT half is not written, and this README is
  the contract until it is. A pure function over text is precisely what a
  capability-checked component can do with no grants at all, which is why
  ADR 0020 § 2.3 named that route before there was a host for it.

## Querying what was contributed

Application search uses [`search(request)`](../search/README.md), which
returns complete pages. The anchor API below remains available, with its
original same-anchor join semantics; note scope there collapses matching
anchors and is not document-wide matching. ADR 0021 amends the former
candidate-then-file-evaluation application path.

`query(constraints, scope, limit) -> [anchor]` is a **conjunction**, and
it is the kernel's, not any plugin's: `type:task due:week @alice` is a
join on the anchor across three contributors and nobody wrote the join
(ADR 0020 § 2.4). A plugin owns the words and how they compile to
constraints; the kernel owns the storage and the join. The exact
semantics — the note-scope join rule, what `Ne` means, how a text term
matches — are recorded in that ADR's § 10 K3 entry, which is the
authority for anyone compiling operators.

## Conformance

The kernel's own suite (`gilune-kernel::index`) holds the rules: two
indexers sharing nothing joining on one line, a version bump re-deriving
one namespace alone, removal taking a namespace and nothing else, and a
property test over randomised edit sequences holding
`incremental == rebuilt`. `gilune-ffi`'s `index_surface` holds the
boundary's half, a throwing implementation included.
