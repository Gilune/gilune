# Bundled plugin SDK

The SDK is compiled separately from the application on desktop and Android.
Plugin code imports `org.gilune.plugin.sdk`; the Tasks implementation imports
that SDK and lives in `org.gilune.plugins.tasks`.

An indexed `SearchType` may set `itemScoped=true` to request complete kernel
search over shared Markdown items. It supplies opaque type-membership constraints;
independent search-operator registrations add their own constraints. This does not
create a dependency between contributing plugins. The host routes the request and
adapts returned items for display. Legacy line extractors are not invoked on this path.

| Module | Responsibility | Allowed project dependencies |
| --- | --- | --- |
| `kernelBindings` | Per-target generated UniFFI bindings | None |
| `pluginSdk` | Search, field, suggestion, decoration and registration contracts | `kernelBindings` |
| `plugins:tasks` | Tasks declarations, indexer, query vocabulary, dates and decoration logic | `pluginSdk` and its bindings |
| `plugins:contractFixture` | Independent row/label query consumer, test dependency only | `pluginSdk` and its bindings |
| `composeApp` | Composition, lifecycle resolution, queries, rendering and platform integration | SDK and selected implementations |

Every SDK/domain Kotlin compilation first verifies its platform compile dependency
graph. Run `./gradlew verifyPluginBoundaries` from `app` to check them directly.
The guard rejects dependencies on the application or another domain implementation;
a new module under `plugins` receives the same rule. It inspects resolved project
dependencies, not just source filenames or import conventions.

Generated bindings belong in `kernelBindings/src/desktopMain/generated` and
`kernelBindings/src/androidMain/generated`. Generate each target from its own
binary; the root README, Android build script and CI use these locations. Generated
files remain ignored. SDK compilation does not include application source folders.
The bindings export the existing native public API and records; plugin callbacks
still receive only the inputs defined by their registration contract, never a
host client or writable database. Trusted bundled Kotlin is not a security sandbox.

`BundledPluginExtensions` pairs a durable declaration with pure search type,
search operator, indexer and value-provider factories. The app's `PluginExtensions`
resolves owner/state/contribution identity and invokes those factories. The Tasks
field-slot adapter lives in the app composition root, so the domain implementation
has no reference to the application's bundled catalogue.

Legacy flat settings helpers retain their existing behaviour for compatibility.
They are not a general JSON/YAML document editor. Source property inspection and
scalar-change proposals use the shared native document contract, preserving the
original source instead of rebuilding a flattened mapping.

The [bundled-extension contract](../../contracts/bundled-extensions/README.md)
describes the exported interfaces and source migration. The independent consumer
in `plugins/contractFixture` is exercised by the app's existing membership tests
against the release kernel, without Tasks installed in its vault.

`IndexedLookupRegistration` contributes pure query/presentation callbacks under
`lookup.indexed`. The host executes the bounded request on a worker and keys
publication by `LookupContext`, plugin state and index refresh. A page collection
may declare its own lookup provider for the standard Find field. The independent
Labels fixture exercises this without People or Tasks. See the
[lookup contract](../../contracts/lookup/README.md).

Editor lookup triggers, richer domain/detail views, a component runtime and
journalled refactoring remain
in the [People/refactoring delivery plan](../../architecture/plans/people-and-document-refactoring.md).


Typed-page consumers use `PageCreationService`, bound by the host to their plugin,
and `pageTypeContribution` for literal template/default-folder declarations.
The [page contract](../../contracts/pages/README.md) specifies capabilities,
namespace ownership, durable receipts and publication limits. The Cards fixture
exercises this service without application or other-domain dependencies.
