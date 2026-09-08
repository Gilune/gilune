# GitHub issue fixes — 2026-09-08

| Issue | Change |
| --- | --- |
| [#1](https://github.com/Gilune/gilune/issues/1) | Folder context menus offer New note and New folder. Newly created empty folders appear immediately. |
| [#2](https://github.com/Gilune/gilune/issues/2) | Tab menus offer Close all but this, Close to the right, and persistent colours. Bulk close preserves unsaved buffers when disk contents conflict. |
| [#3](https://github.com/Gilune/gilune/issues/3) | Removed the permanent Insert button; typed completion triggers remain available. |
| [#4](https://github.com/Gilune/gilune/issues/4) | Desktop and Android About show the app version beside the kernel version, using the release build's version input. |
| [#5](https://github.com/Gilune/gilune/issues/5) | Desktop note actions now contain Rename references, with the existing preview, apply and undo workflow. Removed the permanent Refactor button from the desktop header. |
| [#6](https://github.com/Gilune/gilune/issues/6) | New notes open in Edit mode. Naming a note also makes its body editable by mouse. |
| [#7](https://github.com/Gilune/gilune/issues/7) | Removed the separate People connections navigation tab; connection definitions and lookups remain available. |
| [#8](https://github.com/Gilune/gilune/issues/8) | Plugin settings control sidebar visibility. Right-clicking a plugin navigation icon offers Hide; visibility is stored on this device independently of plugin enablement. |
| [#9](https://github.com/Gilune/gilune/issues/9) | The People navigation destination uses a People icon. |
| [#10](https://github.com/Gilune/gilune/issues/10) | Delete on a focused note or folder opens the existing confirmation dialog. Empty-folder deletion refreshes the tree immediately. |
| [#11](https://github.com/Gilune/gilune/issues/11) | Folder expansion no longer adds a selection highlight alongside the open note. |
| [#12](https://github.com/Gilune/gilune/issues/12) | The configured People folder offers Create person in its desktop context menu. It defaults to People and follows the existing portable Page defaults setting. Creation uses the normal person fields, template and duplicate-handle checks; disabled plugins or revoked creation permission remove the action. |

The earlier requested Edit/Read control and task-overview checkboxes are also
included in the working changes. Completing an overview task updates its source
note through the existing decoration rules and refuses stale or unsaved sources.

Validation: the full desktop suite reported 691 tests, zero failures and five
skips. All seven final issue UI tests passed after adding the empty-folder
deletion regression. Both Android APKs build, and the full Android API 36
instrumentation suite reported 898 tests, zero failures and 15 skips. Passing runs do not resolve the
intermittent renderer hang documented in [CI stability](ci-stability.md).

The subsequent docking report also has a Windows display-change mitigation:
refresh the existing drawing surface after monitor/scale changes instead of
requiring a manual window resize. Real-window tests cover canvas repair and
retention of unsaved editor state; physical docking confirmation is still needed.

Desktop Settings now opens on General, with independent Edit and Read typography
tabs. Each offers Sans serif, Serif or Monospace, font size, line height, a live
preview and a reset for that mode. Read also offers spacing between blocks.
Preferences persist on this device; headings, code and overview results scale
with the note without changing its Markdown. Typography and related rendering
validation: 72 desktop tests, zero failures.

Issue #12 validation: 35 desktop regressions passed, including creation in the
default/custom/root folder, live defaults changes, templates, duplicate-handle
refusal, cancellation, plugin/permission removal, existing collection forms and
tree dragging. Android main and instrumentation sources compile. The native
rendering stall encountered during investigation remains documented separately.
