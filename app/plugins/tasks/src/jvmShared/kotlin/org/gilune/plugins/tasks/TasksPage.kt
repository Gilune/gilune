// The Tasks page (owner, 2026-10-19): tasks live inside the notes they
// belong to, so the plugin gives a vault no Tasks folder. It gives it one
// page at the root instead — live lists of the tasks across the vault, as
// worked examples of `filter` blocks to copy and change. Written once per
// vault by the page initializer, which records that it ran: editing the
// page is the point, and deleting it keeps it deleted, on every device.

package org.gilune.plugins.tasks

import org.gilune.plugin.sdk.InitialPage
import org.gilune.plugin.sdk.pageInitializerContribution
import org.gilune.plugin.sdk.pageTypeContribution

const val TASKS_PAGE_TYPE = "$TASKS_PLUGIN/tasks-page"
const val TASKS_PAGE_RECEIPT_TYPE = "$TASKS_PLUGIN/tasks-page-initialization"
const val TASKS_PAGE_INITIALIZER = "$TASKS_PLUGIN/tasks-page"
const val TASKS_PAGE_FILE = "Tasks.md"

/// Every block here must run: `TasksPageTest` evaluates each one.
val TASKS_PAGE_TEMPLATE = """
# Tasks

Every list on this page is live: it is worked out from the tasks in all your notes each time you look. Tasks themselves stay where you wrote them — a checkbox in a meeting note, a project page, a daily note.

Each list is a fenced block labelled `filter`. Put your cursor inside one to see and change its query; one condition per line, and all of them have to match. This page is yours: edit it, add lists, or delete it — it will not come back.

## Overdue

```filter
type:task
is:open
due:overdue
```

## Due today

```filter
type:task
is:open
due:today
```

## Coming up this week

```filter
type:task
is:open
due:week
```

## Not dated yet

```filter
type:task
is:open
due:none
```

## Done this week

```filter
type:task
done:week
```

## Ideas for your own lists

- Only one folder: add the line `path:Projects/`
- Anything mentioning a word: add the word, or a "quoted phrase"
- Leave a folder out: add `-path:Archive/`
- Due before a date: `due:before:2026-12-01`, or `due:before:friday`

The search guide lists every condition you can use.
""".trimStart()

/// The page type, its receipt type, and the one-time initializer that
/// writes `Tasks.md` at the vault root.
fun tasksPageContributions() = listOf(
    pageTypeContribution(TASKS_PAGE_TYPE, "Tasks page", defaultFolder = "", template = TASKS_PAGE_TEMPLATE),
    pageTypeContribution(TASKS_PAGE_RECEIPT_TYPE, "Tasks page initialization",
        defaultFolder = "Settings/Initialization", template = ""),
    pageInitializerContribution(TASKS_PAGE_INITIALIZER, "A Tasks page with example lists", TASKS_PAGE_RECEIPT_TYPE,
        "tasks-page.md", listOf(InitialPage("tasks", TASKS_PAGE_TYPE, TASKS_PAGE_FILE, emptyMap()))),
)
