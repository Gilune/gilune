# Bundled search and suggestion extensions

Status: Kotlin source contract v0.1, exercised by `PluginExtensionsTest`.
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
path, database, UI state or global kernel client. Providers that need live data,
such as a real People lookup, require a further bounded query/snapshot contract;
the test names provider is an executable fixture, not that People implementation.

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
slots and search contribution ids within one owner. Executable search/provider
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
that metadata through the normal idempotent declaration refresh. No stored
document, index schema, native API or generated binding changes are required.

The build still compiles these files in the shared Kotlin application source
set. A separately compiled plugin SDK, dependency enforcement, component ABI,
asynchronous data-backed suggestions, and the remaining domain-specific
navigation/editor/settings dispatch are not delivered by this change.

Conformance tests exercise a second indexed search domain against the real
kernel, independent settings, a second values provider, three-owner field
composition, a slot with no Tasks installation, disable/remove/re-enable,
copied declarations, registration collisions and removal tombstones.
