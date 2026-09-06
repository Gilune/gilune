# Tasks plugin

Tasks depends on the shared SDK and generated contracts, with no dependency on
People or other domain plugins. It owns checkbox state, task dates, query words
and task presentation declarations.

The shipped `TasksScopedIndexer` version 2 consumes the shared Markdown item map.
It recognises the existing bullet-checkbox syntax on an item's first line and
reads date tokens from that item's own content, including continuation lines.
Nested items have separate facts; fenced and indented code examples are excluded
by the shared Markdown parser. Facts attach to the item's snapshot-local location.

The task search type selects `itemScoped=true` with exact-location constraints.
The kernel returns complete item matches, source ranges and facts; `kernelTaskRows`
adapts them for display. Other plugins may contribute query operators through the
generic registry without changing Tasks. Legacy body-line helpers remain for
compatibility tests and the scan oracle; the shipped registration uses scoped input.

Version 2 rebuilds derived task facts. It does not rewrite task documents, add
hidden identity, or migrate task data into another plugin.
