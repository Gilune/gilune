# Bundled search and suggestion extensions

Status: Kotlin source contract v0.4, exercised by `PluginExtensionsTest` and
`SearchMembershipTest`.

Version 0.4 adds an optional `ScopedIndexerRegistration.createLocal` factory for
the [local scoped-input contract](../indexer/README.md#optional-local-scoped-input).
The original constructor and full-output factory remain available. The generic
host selects the declared registration; it does not recognise plugin domains.
Decision: [ADR 0022](../../architecture/decisions/0022-bundled-extension-registration.md).

This is the composition contract for bundled implementations on desktop and
Android. It is not a downloadable plugin format, WIT ABI, or security sandbox.
The kernel still owns durable plugin state, index storage and query execution.

## Registration

`BundledPluginExtensions` pairs one `PluginDeclaration` with typed executable
registrations. The application composition root supplies these to
`PluginExtensions`; resolvers never select implementations by domain name.

- `SearchTypeRegistration(contributionId, key, create)` exports a search type.
  `create` receives only the owner's current settings string and returns a
  `SearchType` with the registered key. The returned type owns domain vocabulary
  and compiles it to the existing kernel search contract.
- `SearchOperatorRegistration(contributionId, name, create)` exports a global
  filter through `search.operators`. `create(settings, today)` receives only
  its owner's settings and the explicit query date. It returns an operator
  whose registered name is checked, with required index namespaces and an
  optional `setBinding(argument)` for membership filters. The resolver combines
  global operators with the selected type's operators; neither plugin receives
  the other's identity or definition. Membership bindings supply a predicate,
  explicit locality and pure single-value encoder; the framework parses lists
  and compiles one generic `FactIn` constraint. See the
  [query contract](../search/README.md#scoped-facts-and-set-membership).
- `SuggestionProviderRegistration(id, create)` exports a provider through a
  `suggest.values` contribution with that id. `create(fieldKey, settings)`
  receives the field's key and the **provider owner's** settings. Its result
  takes a fragment and explicit date, and returns display/insertion candidates.
- `fieldSlots` exports plugin-owned slots whose contributions carry `key`,
  `label` and `values` strings in their JSON config. `values` names a registered
  suggestion provider. The publisher, field contributor and provider may be
  different plugins. A data-only contributor needs no local executable module.
- `IndexerRegistration(version, predicates, create)` supplies a pure
  `NoteIndexer` under the owning plugin's namespace, using the existing indexer
  contract. The host registers it; the callback never receives a kernel handle.

Factories and callbacks are pure over their inputs. They do not receive a vault
path, database, UI state or global kernel client. Live-data consumers use
`IndexedLookupRegistration` under `lookup.indexed`: a pure query declaration and
row presentation callback, executed through the bounded [lookup contract](../lookup/README.md).
People and the independent Labels fixture exercise this contract. It does not
change legacy synchronous `suggest.values` providers or imply that editor trigger
integration is complete.

## Resolution and lifecycle

Search and suggestion code runs only when its matching owner is locally
available, installed as bundled, enabled, and declares the exact slot/id.
Declarations copied under another owner cannot activate that code. An unknown
implementation or provider remains inactive. Factories interpret their own
settings; the host does not read domain-specific settings keys.

A field is offered only while its slot publisher, contributor and declared
provider are enabled. Missing/blank required config strings and unknown provider
ids offer no field. Colliding field keys, compared case-insensitively, offer
neither conflicting field. Other fields remain available.

The immutable build catalogue rejects duplicate plugin owners, search keys
(case-insensitively, including the reserved `note` type), provider ids, field
slots, global operator names and contribution ids within one owner. The
operator registry rejects collisions with built-ins, the reserved `type` word,
and the selected type's operators, using case-insensitive names. Executable search/provider
registrations must be declared in their owner's bundled manifest. These are
programmer errors, not an order-dependent override mechanism.

Resolution is a snapshot of the supplied installed plugin list. Hosts rebuild
it on plugin/settings changes, as existing desktop and Android callers do.
It does not provide revocable capability handles for already returned callbacks.

Index registration retains ADR 0020's existing policy: disabled owners keep
their derived namespace warm; removed or absent owners are not registered.
The kernel drops the namespace on removal. Registering bundled declarations
again preserves the removal tombstone. This contract does not change the
existing distinction between disabling query vocabulary and index maintenance.

## Compatibility and limits

Tasks uses this contract for its search type, date provider and indexer. Its
existing `task`, `tasks.fields`, `org.gilune.dates`, predicates and settings keys
are preserved. Its declaration gains `suggest.values`; existing vaults receive
that metadata through the normal idempotent declaration refresh. Version 0.2
adds independent query operators and set bindings. Unlike v0.1, it requires the
new native scoped/set constraints and matching generated bindings. No stored
document or index-schema migration is needed. Duplicate operator replacement
is no longer supported; use distinct qualified names. Invalid/unavailable
structured fields are errors, with compatibility fixtures for literal prose.

Version 0.3 moves the contract types to the separately compiled `pluginSdk`
module, under `org.gilune.plugin.sdk`. Tasks moves to `plugins:tasks`, under
`org.gilune.plugins.tasks`, and depends only on the SDK/bindings. Host consumers
must update imports; the application has no compatibility wrappers duplicating
these contracts. The native ABI, stored documents, plugin IDs, predicates and
settings remain unchanged by this source-package migration.

`kernelBindings` compiles each platform's own generated UniFFI code. SDK and domain
compilation checks the resolved platform dependency graph and rejects the app or
another domain implementation. The independent row/label fixture is compiled as
`plugins:contractFixture`, a test-only app dependency with no Tasks dependency.
See the [module contract](../../app/pluginSdk/README.md) for build and binding paths.

The SDK exposes value types and callback contracts; owner/lifecycle resolution
stays in the host. A component ABI, asynchronous data-backed suggestions, richer
page/detail contributions and the remaining domain-specific navigation/editor/settings
dispatch are still outstanding. Bundled Kotlin remains trusted execution.

Conformance tests exercise a second indexed search domain against the real
kernel, independent settings, a second values provider, three-owner field
composition, a slot with no Tasks installation, disable/remove/re-enable,
copied declarations, registration collisions and removal tombstones.

The independent membership fixture uses separate row-type and label-field
plugins in a vault without Tasks installed. It checks same-location joins,
complete encoded pairs, missing values, IN/NOT IN, empty sets, owner checks,
index dependencies and equivalent-query continuation against the release kernel.


## Typed-page service

The additive [page contract](../pages/README.md) supplies `pages.types` declarations
and an owner-bound `PageCreationService` in the SDK. `pageTypeContribution` encodes
literal defaults without a host dependency. This service is exercised by the
independent Cards fixture. Generic page collections and creation forms are now
contributed through the SDK; dedicated detail/query sections remain deferred.
The People module consumes these contracts without depending on another domain.

The additive source-indexer registration exposes exact frontmatter/body source to
any plugin. The independent Headers fixture uses the shared pure YAML reader;
legacy body-only indexers are unchanged. See the [indexer contract](../indexer/README.md)
for source input, line coordinates, versioning and shared-read guarantees.

`ScopedIndexerRegistration` adds exact source, generic Markdown locations and
typed YAML values. Its source-aware facts use document/root/item/line locations;
the SDK rejects combining it with either legacy callback form in one namespace.
The kernel owns source-version evidence, persistence and paged location queries.
`SourceView` and `SourceValue` are pure SDK helpers over that supplied data. People
uses this contract without importing or naming another domain implementation.
