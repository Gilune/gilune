# ADR 0026: Shared editor contributions and reviewed document refactoring

Date: 2026-09-07
Status: Implemented; release validation recorded in the People delivery plan.

## Decision

People owns person mentions, connection keywords, paired facts and rename/merge
policy. It does not import Tasks. Independently registered editor completions,
document views and refactoring providers use SDK contracts. Tasks contributes
its existing date insertion through the same completion contract. The shell
renders standard menus, creation forms, links, query sections and review UI.

Lookup proposals contain indexed queries rather than filesystem access. The host
pages results on a worker, keys them to the complete editor context, and checks
identifier uniqueness again on selection. Connection selection advances to person
lookup without modifying the buffer; selection inserts `keyword: @handle` as one
literal edit. Toolbar and typed triggers use the same provider and Markdown map.

The kernel adds generic journalled source changes underneath providers. It owns
version checks, protected metadata, durable receipts, filesystem preservation and
recovery. A per-document transaction records the normal managed revision and the
idempotent application receipt. A vault-wide refactor is deliberately not one
atomic transaction. The provider's definition is applied after the reviewed
references so partial work cannot pretend catalogue completion.

## Why

The portable document is the meaning-bearing artefact. Renaming therefore updates
visible tokens, including metadata-free Markdown. Hidden identifier aliases would
make exported text disagree with the application. Giving a plugin broad write
authority or requiring it to know another plugin's schema would break the same
boundary that independent indexed facts establish.

The new contracts are justified by public invariants and an unrelated consumer.
They are not People-specific dispatch added to a kernel or renderer.

## Consequences and evidence

The [refactoring contract](../../contracts/refactoring/README.md) specifies review
tokens, per-document states, partial/cancelled work, conservative guarded undo,
local retention and the external-editor concurrency limit. Readable metadata
entry ranges are generic source data; their interpretation remains in plugins.

Ordinary unmanaged files receive local change receipts and recovery without
adoption. They cannot gain managed operation sync without acquiring an object
identity. Managed pages continue through the existing revision/sync mechanisms.
Reminders, Projects and Decisions remain outside this release.

Evidence includes an independent label provider, five crash windows, racing
external writes, ordinary two-device sync, 10,001-reference enumeration and
rebuild, and shared editor/dialog tests. There is no claim of physical-device or
arbitrary external-editor lock interoperability. Recovery preserves conflicting
bytes and requires an explicit reviewed resolution.
